import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Java 21, dependency-free Claude MCP event evaluator and independent Eclipse inventory gate.
 * Offline: java dev-e2e/ClaudeEvidenceGate.java grade --scenario boot-quote --transcript PATH [--before PATH --after PATH]
 * Live:    java dev-e2e/ClaudeEvidenceGate.java run --scenario boot-quote --approve-claude --approve-fixture-post
 * Only 'run' invokes the existing token-costing DevE2E Claude trial, with explicit approval.
 * No source/IDE/global Claude configuration is modified by this tool.
 */
public final class ClaudeEvidenceGate {
    private ClaudeEvidenceGate() {}
    private static final String PREFIX="mcp__eclipse-debugger__";
    private static final Set<String> SCENARIOS=Set.of("boot-quote","validation","junit");

    public static void main(String[] args) throws Exception {
        if(args.length<1) throw new IllegalArgumentException("Usage: run|grade|snapshot --scenario NAME ... (see docs/claude-evidence-gate.md)");
        String action=args[0]; Map<String,String> flags=new LinkedHashMap<>(); Set<String> approvals=new HashSet<>();
        for(int i=1;i<args.length;i++) {
            if(!args[i].startsWith("--")) throw new IllegalArgumentException("Unknown argument "+args[i]);
            if(Set.of("--approve-claude","--approve-fixture-post").contains(args[i])) approvals.add(args[i]);
            else { if(i+1>=args.length)throw new IllegalArgumentException("Missing value for "+args[i]); flags.put(args[i++].substring(2),args[i]); }
        }
        String scenario=flags.get("scenario");
        if(!"snapshot".equals(action) && !SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Select --scenario boot-quote|validation|junit");
        if("grade".equals(action)){
            Path file=requiredFile(flags,"transcript");
            Path before=optionalFile(flags,"before"),after=optionalFile(flags,"after");
            Report result=grade(scenario,file,before,after);
            Path output=flags.containsKey("output")?Path.of(flags.get("output")):file.resolveSibling(file.getFileName().toString().replace(".claude.jsonl",".evidence-verdict.json"));
            if(output.equals(file)) throw new IllegalArgumentException("Refusing to overwrite transcript");
            save(output,result.toJson());
            System.out.println("EVIDENCE "+scenario+" => "+result.verdict+"; "+result.summary()+"; report="+output);
            if(!result.verdict.equals("PASS")) System.exit(2);
        } else if("snapshot".equals(action)){
            Path root=Path.of(flags.getOrDefault("root",".")).toRealPath();
            Path target=Path.of(require(flags,"output"));
            save(target, snapshot(root)); System.out.println("SNAPSHOT: "+target);
        } else if("run".equals(action)){
            if(!approvals.contains("--approve-claude")) throw new IllegalArgumentException("Explicit --approve-claude required (usage/cost)");
            if(!"junit".equals(scenario)&&!approvals.contains("--approve-fixture-post")) throw new IllegalArgumentException("Explicit --approve-fixture-post required for HTTP fixture POST");
            Path root=Path.of(flags.getOrDefault("root",".")).toRealPath();
            if(!Files.isRegularFile(root.resolve("pom.xml"))||!Files.isRegularFile(root.resolve("dev-e2e/DevE2E.java"))) throw new IllegalArgumentException("Invalid repo root: "+root);
            run(root,scenario,approvals);
        } else throw new IllegalArgumentException("Unknown mode: "+action);
    }
    private static String require(Map<String,String> a,String k){String v=a.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing --"+k);return v;}
    private static Path requiredFile(Map<String,String> a,String k)throws Exception {Path p=Path.of(require(a,k)).toAbsolutePath().normalize();if(!Files.isRegularFile(p))throw new IOException("Missing file "+p);return p;}
    private static Path optionalFile(Map<String,String> a,String k)throws Exception{return a.containsKey(k)?requiredFile(a,k):null;}
    private static void save(Path p,String body)throws IOException {Path dir=p.toAbsolutePath().getParent();if(dir!=null)Files.createDirectories(dir);Files.writeString(p,body,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}

    private static void run(Path root,String scenario,Set<String> approvals)throws Exception{
        Path evidence=root.resolve("evaluation/reports/claude-trials"); Files.createDirectories(evidence);
        Set<String> seen;
        try(var stream=Files.list(evidence)){
            seen=stream.filter(x->x.getFileName().toString().startsWith(scenario+"-")&&x.toString().endsWith(".claude.jsonl")).map(x->x.getFileName().toString()).collect(Collectors.toSet());
        }
        String runId="gate-"+scenario+"-"+Instant.now().toString().replaceAll("[^0-9TZ]", "")+"-"+System.nanoTime();
        Path before=evidence.resolve(runId+".before.json"),after=evidence.resolve(runId+".after.json");
        // Verify the bridge and baseline BEFORE invoking any paid agent work.
        save(before,snapshot(root));
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        List<String> cmd=new ArrayList<>(List.of(java,"dev-e2e/DevE2E.java","test","--tests","claude:"+scenario,"--approve-claude"));
        if(approvals.contains("--approve-fixture-post"))cmd.add("--approve-fixture-post");
        int exit=-1;String error="";
        try {
            Process p=new ProcessBuilder(cmd).directory(root.toFile()).inheritIO().start();
            if(p.waitFor(780,TimeUnit.SECONDS))exit=p.exitValue();
            else {p.destroy(); if(!p.waitFor(10,TimeUnit.SECONDS))p.destroyForcibly(); error="RUN_TIMEOUT";}
        }catch(Exception e){error="RUN_ERROR: "+e.getClass().getSimpleName();}
        // Independent read-only bridge inventory AFTER CLI exits, even on failure.
        String snapshotError="";
        try {save(after,snapshot(root));}catch(Exception e){snapshotError=e.getClass().getSimpleName()+": "+e.getMessage();}
        List<Path> newRuns=new ArrayList<>();
        try(var stream=Files.list(evidence)){
            stream.filter(x->x.getFileName().toString().startsWith(scenario+"-")&&x.toString().endsWith(".claude.jsonl")&&!seen.contains(x.getFileName().toString())).forEach(newRuns::add);
        }
        if(newRuns.size()!=1){
            System.err.println("EVIDENCE NOT VERIFIED: expected exactly one new transcript; found "+newRuns.size()+". CLI exit="+exit+" "+error+"; snapshot error="+snapshotError);
            System.err.println("Before="+before+" After="+after+". Investigate and do not claim PASS.");System.exit(3);
        }
        Path transcript=newRuns.get(0);
        Report result=grade(scenario,transcript,before,Files.exists(after)?after:null);
        if(exit!=0||!error.isEmpty())result.fail("Claude/DevE2E process failed: exit="+exit+" "+error);
        if(!snapshotError.isEmpty())result.partial("Independent post-trial snapshot failed: "+snapshotError);
        Path verdict=transcript.resolveSibling(transcript.getFileName().toString().replace(".claude.jsonl",".evidence-verdict.json"));
        save(verdict,result.toJson());
        System.out.println("EVIDENCE "+scenario+" => "+result.verdict+"; "+result.summary()+"; report="+verdict);
        if(!result.verdict.equals("PASS"))System.exit(2);
    }
    private static String snapshot(Path root)throws Exception{
        Properties p=new Properties();try(InputStream is=Files.newInputStream(root.resolve("dev-e2e/e2e.properties"))){p.load(is);}
        int port=Integer.parseInt(requireProp(p,"bridgePort"));
        if(port<1025||port>65535||port==8765) throw new IllegalArgumentException("Bridge must use dedicated nondefault port");
        String ws=requireProp(p,"workspace").replace("${LOCALAPPDATA}",System.getenv().getOrDefault("LOCALAPPDATA","")).replace("${user.home}",System.getProperty("user.home"));
        if(!Files.isRegularFile(Path.of(ws).resolve("spring-boot-debugging/.agentdebug-fixture")))throw new IOException("Missing disposable workspace marker; refusing inventory");
        Path token=Path.of(System.getProperty("user.home"),".eclipse-agentdebug","bridge-"+port+".token");
        String key=Files.readString(token,StandardCharsets.UTF_8).trim();if(key.isEmpty())throw new IOException("Empty bridge authentication token");
        Map<String,Object> map=new LinkedHashMap<>();map.put("captureTime",Instant.now().toString());map.put("port",port);
        for(String command:List.of("GET_E2E_INFO","LIST_DEBUG_SESSIONS","LIST_BREAKPOINTS","LIST_EXCEPTION_BREAKPOINTS","LIST_RUN_CONFIGURATIONS")){
            String response;
            try(Socket socket=new Socket()){
                socket.connect(new InetSocketAddress("127.0.0.1",port),2000);socket.setSoTimeout(10000);
                try(var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
                    var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8))){
                    out.println("AUTH "+key+" "+command);response=in.readLine();
                }
            }
            if(response==null)throw new IOException("No response to "+command);
            Map<String,Object> decoded=obj(Json.parse(response));
            if(!"success".equals(str(decoded.get("status"))))throw new IOException("Bridge inventory failed for "+command+": "+str(decoded.get("message")));
            map.put(command,decoded);
        }
        return Json.render(map)+"\n";
    }
    private static String requireProp(Properties p,String k){String s=p.getProperty(k);if(s==null||s.isBlank())throw new IllegalArgumentException("Missing "+k);return s.trim();}

    static final class Event {
        final String name;final Map<String,Object> input,reply;final boolean error;final int line;
        Event(String n,Map<String,Object> a,Map<String,Object> b,boolean e,int l){name=n;input=a;reply=b;error=e;line=l;}
        boolean success(){return !error && ("success".equals(str(reply.get("status")))||"COMPLETE".equals(str(reply.get("status")))||"PENDING".equals(str(reply.get("status"))));}
    }
    static final class Report {
        final String scenario,source;String verdict="PARTIAL";final List<String> checks=new ArrayList<>(),issues=new ArrayList<>();
        int mcpCalls;boolean runtime,cleanupReply,independentCleanup;
        Report(String s,String p){scenario=s;source=p;}
        void ok(String s){checks.add(s);}void partial(String s){issues.add(s);if("PASS".equals(verdict))verdict="PARTIAL";}
        void fail(String s){issues.add(s);verdict="FAIL";}
        String summary(){return "MCP calls="+mcpCalls+", runtime="+runtime+", cleanupResponse="+cleanupReply+", independentCleanup="+independentCleanup+", issues="+issues.size();}
        String toJson(){Map<String,Object> m=new LinkedHashMap<>();m.put("scenario",scenario);m.put("transcript",source);m.put("verdict",verdict);m.put("mcpCalls",mcpCalls);m.put("runtimeEvidenceVerified",runtime);m.put("cleanupResponseVerified",cleanupReply);m.put("independentCleanupVerified",independentCleanup);m.put("checks",checks);m.put("issues",issues);m.put("gradedAt",Instant.now().toString());return Json.render(m)+"\n";}
    }
    static Report grade(String scenario,Path transcript,Path before,Path after)throws Exception{
        Report r=new Report(scenario,transcript.toAbsolutePath().toString());
        List<Event> events=parseEvents(transcript,r);r.mcpCalls=events.size();
        if(events.isEmpty()){r.fail("No correlated MCP calls/results");return r;}
        List<Event> begin=with(events,"begin_debug_investigation"),finish=with(events,"finish_debug_investigation");
        Event started=null,ended=null;
        for(Event e:begin)if(e.success()&&"NORMAL".equals(str(e.reply.get("mode")))&&!str(e.reply.get("investigationId")).isBlank()){started=e;break;}
        if(started==null)r.fail("Missing successful NORMAL investigation");else r.ok("NORMAL investigationId confirmed from MCP response");
        String investigation=started==null?"":str(started.reply.get("investigationId"));
        for(Event e:finish)if(e.success()&&investigation.equals(str(e.input.get("investigationId")))&&investigation.equals(str(e.reply.get("investigationId")))&&"CLEANUP".equals(str(e.reply.get("action")))&&Boolean.TRUE.equals(e.reply.get("completed"))){ended=e;break;}
        r.cleanupReply=ended!=null;
        if(ended==null)r.fail("Missing successful CLEANUP response for same investigation");else r.ok("Owned investigation finish acknowledged by Eclipse");
        if("boot-quote".equals(scenario)||"validation".equals(scenario))gradeHttp(scenario,events,started,ended,r);
        else gradeJunit(events,started,ended,r);
        if(before==null||after==null){r.partial("No independent before/after Eclipse inventory; runtime evidence alone is not full PASS");}
        else compareInventories(before,after,r);
        if("PARTIAL".equals(r.verdict)&&r.issues.isEmpty())r.verdict="PASS";
        return r;
    }
    private static void gradeHttp(String scenario,List<Event> events,Event started,Event ended,Report r){
        Event app=find(events,"start_java_application",e->e.success()&&"spring-boot-debugging".equals(str(e.input.get("projectName")))&&str(e.input.get("mainType")).endsWith(".OrdersApplication"));
        if(app==null)r.fail("No successful owned OrdersApplication launch");
        else r.ok("Correct OrdersApplication selected and started");
        String launch=app==null?"":str(app.reply.get("launchName"));
        if(app!=null&&started!=null&&!str(app.input.get("investigationId")).equals(str(started.reply.get("investigationId"))))r.fail("Launch investigationId differs from NORMAL investigation");
        if(app!=null&&Boolean.TRUE.equals(app.reply.get("reusedSession")))r.fail("Reused session rather than investigation-owned fixture launch");
        Event sent=find(events,"start_fixture_http_request",e->"PENDING".equals(str(e.reply.get("status")))&&("boot-quote".equals(scenario)?"discount":"invalid-quantity").equals(str(e.input.get("scenario")))
             &&launch.equals(str(e.input.get("launchName")))&&str(e.reply.get("url")).matches("http://127\\.0\\.0\\.1:[0-9]+/orders/quote")
             &&"POST".equals(str(e.reply.get("method"))));
        // The predicate above enforces discount for boot-quote and invalid-quantity for validation.
        if(sent==null)r.fail("No approved scoped asynchronous fixture POST with actual requestId");
        else r.ok("Asynchronous POST to loopback Orders fixture established");
        String request=sent==null?"":str(sent.reply.get("requestId"));
        if(request.isBlank())r.fail("HTTP requestId absent");
        if(sent!=null){
            Map<String,Object> body=parseMaybe(str(sent.reply.get("bodySent")));
            int q="boot-quote".equals(scenario)?2:0;
            if(number(body.get("quantity"))!=q||number(body.get("discountPercent"))!=10||number(body.get("unitPrice"))!=60)r.fail("Fixture POST input oracle mismatch");
            else r.ok("Verified actual submitted quantity, unit price and discount");
        }
        Event pause=find(events,"wait_for_debug_pause",e->e.line>(sent==null?0:sent.line)&&launch.equals(str(e.input.get("launchName")))&&"success".equals(str(e.reply.get("status")))&&hasSuspendedHttpWorker(e.reply));
        if(pause==null)r.fail("No request-scoped suspended HTTP worker observed");else r.ok("Real HTTP worker suspension observed");
        Event stack=find(events,"get_stack_frames",e->pause!=null&&e.line>pause.line&&e.success()&&launch.equals(str(e.input.get("launchName")))&&suspendedAt(pause.reply,e.input.get("threadIndex"))&&hasFrame(e.reply,"OrderService","quote"));
        if(stack==null)r.fail("Missing application OrderService.quote stack frame after HTTP suspension");else r.ok("Application service stack frame observed");
        Event vars=find(events,"get_frame_variables",e->stack!=null&&e.line>=stack.line&&e.success()&&launch.equals(str(e.input.get("launchName")))&&Objects.equals(stack.input.get("threadIndex"),e.input.get("threadIndex"))&&(("boot-quote".equals(scenario)&&hasVar(e.reply,"percent","10")&&hasVar(e.reply,"rate","0"))||("validation".equals(scenario)&&hasVar(e.reply,"quantity","0"))));
        if(vars==null)r.fail("Missing expected defect-related runtime variables");else r.ok("Runtime defect values observed in suspended frame");
        if("validation".equals(scenario)){
            Event exception=find(events,"set_exception_breakpoint",e->e.success()&&"java.lang.IllegalArgumentException".equals(str(e.input.get("exceptionName"))));
            if(exception==null)r.fail("No confirmed IllegalArgumentException exception breakpoint");
            else r.ok("Exception breakpoint established");
            if(vars!=null&&!hasThrowing(vars.reply,"java.lang.IllegalArgumentException"))r.fail("Missing thrown IllegalArgumentException value at service frame");
            else if(vars!=null)r.ok("Observed thrown IllegalArgumentException in application frame");
        }
        Event resumed=find(events,"control_debug_session",e->vars!=null&&e.line>vars.line&&e.success()&&"RESUME".equals(str(e.input.get("action")))&&Boolean.TRUE.equals(e.reply.get("accepted"))&&launch.equals(str(e.input.get("launchName")))&&Objects.equals(vars.input.get("threadIndex"),e.input.get("threadIndex")));
        if(resumed==null)r.fail("No successful resume after inspecting application values");else r.ok("Suspended investigation resumed");
        Event response=find(events,"get_fixture_http_request",e->resumed!=null&&e.line>resumed.line&&request.equals(str(e.input.get("requestId")))&&"COMPLETE".equals(str(e.reply.get("status")))&&request.equals(str(e.reply.get("requestId")))&&number(e.reply.get("httpStatus"))==("boot-quote".equals(scenario)?200:400));
        if(response==null)r.fail("No COMPLETE HTTP response for the same requestId and expected status");
        else{
            Map<String,Object> body=parseMaybe(str(response.reply.get("body")));
            boolean correct="boot-quote".equals(scenario)?"120.00".equals(String.valueOf(body.get("total"))):"Quantity must be positive".equals(str(body.get("error")));
            if(!correct)r.fail("HTTP response body differs from independent oracle");else r.ok("Actual HTTP status and body verified");
        }
        if(response!=null&&ended!=null&&ended.line<response.line)r.fail("Cleanup was called before the HTTP response completed");
        if(sent!=null&&response!=null&&number(sent.reply.get("pid"))>0&&number(response.reply.get("fixturePid"))!=number(sent.reply.get("pid")))r.fail("HTTP response PID differs from approved fixture launch");
        if(started!=null&&sent!=null&&!str(sent.input.get("investigationId")).equals(str(started.reply.get("investigationId"))))r.fail("HTTP request used unrelated investigationId");
        r.runtime=app!=null&&sent!=null&&pause!=null&&stack!=null&&vars!=null&&resumed!=null&&response!=null&&!r.issues.stream().anyMatch(x->x.startsWith("HTTP response body")||x.startsWith("Fixture POST"));
    }
    private static void gradeJunit(List<Event> events,Event started,Event ended,Report r){
        Event junit=find(events,"start_junit_test",e->e.success()&&str(e.input).contains("OrderServiceTest")&&str(e.input).contains("calculatesDiscount"));
        if(junit==null)r.fail("No verified selected OrderServiceTest.calculatesDiscount JUnit launch");else r.ok("Selected single JUnit test launch observed");
        Event stack=find(events,"get_stack_frames",e->e.success()&&hasFrame(e.reply,"OrderService","quote"));
        if(stack==null)r.partial("No independently correlated application frame for JUnit diagnosis");else r.ok("JUnit application frame observed");
        Event vars=find(events,"get_frame_variables",e->e.success()&&hasVar(e.reply,"rate","0"));
        if(vars==null)r.partial("No directly observed incorrect rate=0 in JUnit tool results");else r.ok("JUnit first incorrect value observed");
        r.runtime=junit!=null&&stack!=null&&vars!=null;
    }
    private static void compareInventories(Path before,Path after,Report r)throws Exception{
        Map<String,Object> b=obj(Json.parse(Files.readString(before))),a=obj(Json.parse(Files.readString(after)));
        for(String key:List.of("LIST_DEBUG_SESSIONS","LIST_BREAKPOINTS","LIST_EXCEPTION_BREAKPOINTS","LIST_RUN_CONFIGURATIONS")){
            Map<String,Object> x=obj(b.get(key)),y=obj(a.get(key));
            if(!"success".equals(str(x.get("status")))||!"success".equals(str(y.get("status")))){r.partial("Inventory query not successful: "+key);continue;}
            String field=switch(key){case "LIST_DEBUG_SESSIONS"->"sessions";case "LIST_BREAKPOINTS"->"breakpoints";case "LIST_EXCEPTION_BREAKPOINTS"->"breakpoints";default->"configurations";};
            if(!(x.get(field) instanceof List)||!(y.get(field) instanceof List)){r.partial("Snapshot missing "+field);continue;}
            // Full normalized inventory equality is intentionally stricter than counts: an extra
            // session and a removed user session cannot cancel one another out.
            List<String> left=canonItems(arr(x.get(field))),right=canonItems(arr(y.get(field)));
            if(!left.equals(right))r.fail("Independent "+field+" inventory changed; inspect snapshots for ownership safety");
            else r.ok("Independent "+field+" inventory preserved across run");
        }
        r.independentCleanup=r.issues.stream().noneMatch(s->s.startsWith("Independent ")||s.startsWith("Inventory ")||s.startsWith("Snapshot "));
        if(!r.independentCleanup)r.partial("Independent cleanup inventory not confirmed");
    }
    private static List<String> canonItems(List<Object> items){return items.stream().map(Json::render).sorted().toList();}
    private static boolean suspendedAt(Map<String,Object> m,Object index){for(Object v:arr(m.get("threads"))){Map<String,Object> t=obj(v);if(Boolean.TRUE.equals(t.get("suspended"))&&str(t.get("name")).startsWith("http-")&&Objects.equals(t.get("index"),index))return true;}return false;}
    private static boolean hasSuspendedHttpWorker(Map<String,Object> m){for(Object v:arr(m.get("threads"))){Map<String,Object> t=obj(v);if(Boolean.TRUE.equals(t.get("suspended"))&&str(t.get("name")).startsWith("http-"))return true;}return false;}
    private static boolean hasFrame(Map<String,Object> m,String klass,String method){for(Object v:arr(m.get("frames"))){Map<String,Object> f=obj(v);if(str(f.get("className")).endsWith("."+klass)&&method.equals(str(f.get("methodName"))))return true;}return false;}
    private static boolean hasThrowing(Map<String,Object> m,String exception){
        for(Object v:arr(m.get("variables"))){Map<String,Object> x=obj(v);
            if(str(x.get("name")).contains("is throwing")&&exception.equals(str(x.get("type"))))return true;
        }
        return false;
    }
    private static boolean hasVar(Map<String,Object> m,String name,String value){for(Object v:arr(m.get("variables"))){Map<String,Object> f=obj(v);if(name.equals(str(f.get("name")))&&value.equals(str(f.get("value"))))return true;}return false;}
    private static List<Event> with(List<Event> l,String suffix){return l.stream().filter(e->e.name.equals(suffix)).toList();}
    private static Event find(List<Event> e,String n,java.util.function.Predicate<Event> predicate){for(Event x:e)if(x.name.equals(n)&&predicate.test(x))return x;return null;}
    private static Map<String,Object> parseMaybe(String s){try{return obj(Json.parse(s));}catch(Exception e){return Map.of();}}
    private static String str(Object o){return o==null?"":String.valueOf(o);}
    private static int number(Object o){try{return Integer.parseInt(str(o));}catch(Exception e){return -1;}}
    private static List<Object> arr(Object o){return o instanceof List<?> l?new ArrayList<>(l):List.of();}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o){return o instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}

    private static List<Event> parseEvents(Path transcript,Report report)throws Exception{
        Map<String,Event> used=new LinkedHashMap<>();List<Event> events=new ArrayList<>();int line=0;
        try(BufferedReader in=Files.newBufferedReader(transcript,StandardCharsets.UTF_8)){
            for(String raw;(raw=in.readLine())!=null;){line++;if(raw.isBlank())continue;
                Map<String,Object> root;
                try{root=obj(Json.parse(raw));}catch(Exception ex){report.fail("Invalid transcript JSON at line "+line);continue;}
                String type=str(root.get("type"));Map<String,Object> msg=obj(root.get("message"));
                if("assistant".equals(type)){
                    for(Object c:arr(msg.get("content"))){Map<String,Object> x=obj(c);
                        if("tool_use".equals(str(x.get("type")))){
                            String n=str(x.get("name"));if(!n.startsWith(PREFIX))continue;
                            String id=str(x.get("id"));if(id.isBlank()||used.containsKey(id))continue;
                            Event e=new Event(n.substring(PREFIX.length()),obj(x.get("input")),new LinkedHashMap<>(),false,line);
                            used.put(id,e);events.add(e);
                        }
                    }
                }else if("user".equals(type)){
                    for(Object c:arr(msg.get("content"))){Map<String,Object> x=obj(c);
                        if(!"tool_result".equals(str(x.get("type"))))continue;
                        String id=str(x.get("tool_use_id"));Event usedEvent=used.get(id);if(usedEvent==null)continue;
                        if(!usedEvent.reply.isEmpty())continue;
                        Object body=x.get("content");String text="";
                        if(body instanceof String s)text=s;
                        else for(Object part:arr(body)){
                            Map<String,Object> node=obj(part);if("text".equals(str(node.get("type")))){text=str(node.get("text"));break;}
                        }
                        Map<String,Object> content=parseMaybe(text);
                        if(text.startsWith("Eclipse bridge response:"))content=new LinkedHashMap<>(Map.of("status","success","text",text));
                        usedEvent.reply.putAll(content);
                        if(Boolean.TRUE.equals(x.get("is_error"))||"error".equals(str(content.get("status"))))usedEvent.reply.put("_failure",true);
                    }
                }
            }
        }
        // No transcript means no runtime proof. Verify each result, not merely the tool name.
        List<Event> checked=new ArrayList<>();for(Event e:events){checked.add(new Event(e.name,e.input,e.reply,Boolean.TRUE.equals(e.reply.get("_failure")),e.line));}
        return checked;
    }

    /** Small strict JSON reader/writer for Claude stream-json, no Maven/Python dependencies. */
    static final class Json {
        final String s;int i;Json(String s){this.s=s;}
        static Object parse(String s){Json p=new Json(s);Object v=p.value();p.ws();if(p.i!=s.length())throw new IllegalArgumentException("Trailing JSON at "+p.i);return v;}
        void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        char next(){if(i>=s.length())throw new IllegalArgumentException("EOF in JSON");return s.charAt(i++);}
        Object value(){ws();char c=next();switch(c){case '{':{Map<String,Object> m=new LinkedHashMap<>();ws();if(s.charAt(i)=='}'){i++;return m;}do{ws();if(next()!='"')throw new IllegalArgumentException("JSON key");String k=quoted();ws();if(next()!=':')throw new IllegalArgumentException("JSON colon");m.put(k,value());ws();c=next();}while(c==',');if(c!='}')throw new IllegalArgumentException("JSON object");return m;}
        case '[':{List<Object> l=new ArrayList<>();ws();if(s.charAt(i)==']'){i++;return l;}do{l.add(value());ws();c=next();}while(c==',');if(c!=']')throw new IllegalArgumentException("JSON list");return l;}
        case '"':return quoted();case 't':literal("rue");return true;case 'f':literal("alse");return false;case 'n':literal("ull");return null;
        default:if(c=='-'||Character.isDigit(c)){int start=i-1;while(i<s.length()&&"0123456789+-.eE".indexOf(s.charAt(i))>=0)i++;String n=s.substring(start,i);return new java.math.BigDecimal(n);}throw new IllegalArgumentException("Unexpected JSON character "+c);}}
        void literal(String suffix){for(int j=0;j<suffix.length();j++)if(next()!=suffix.charAt(j))throw new IllegalArgumentException("Bad literal");}
        String quoted(){StringBuilder b=new StringBuilder();while(true){char c=next();if(c=='"')return b.toString();if(c=='\\'){char e=next();switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{int v=0;for(int j=0;j<4;j++)v=(v<<4)+Character.digit(next(),16);b.append((char)v);}default->throw new IllegalArgumentException("Bad JSON escape");}}else{if(c<32)throw new IllegalArgumentException("Control char");b.append(c);}}}
        static String render(Object v){if(v==null)return"null";if(v instanceof String s)return '"'+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+'"';if(v instanceof Map<?,?> m){List<String> kv=new ArrayList<>();for(var e:m.entrySet())kv.add(render(str(e.getKey()))+":"+render(e.getValue()));return"{"+String.join(",",kv)+"}";}if(v instanceof List<?> l)return"["+l.stream().map(Json::render).collect(Collectors.joining(","))+"]";return v.toString();}
    }
}
