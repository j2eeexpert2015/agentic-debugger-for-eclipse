package org.learningfromexperience.agentdebug.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in direct Spring Boot HTTP arithmetic investigation (SB-04/SB-05 fixture slice).
 * Uses the existing Java fixture HTTP client and Eclipse JDT bridge. A new NORMAL
 * investigation owns the launch and breakpoint. Never reuses a running application.
 * This is NOT evidence of autonomous Claude root-cause selection.
 */
public final class SpringBootQuoteVerifier {
    private static final String PROJECT = "spring-boot-debugging";
    private static final String SERVICE = "org.learningfromexperience.bootfixture.orders.OrderService";
    private static final String MAIN = "org.learningfromexperience.bootfixture.orders.OrdersApplication";
    private final PhaseOneBridgeVerifier.Bridge bridge;
    private final Path root, workspace, output;
    private final int port;
    private final Map<String,Object> evidence = new LinkedHashMap<>();

    private SpringBootQuoteVerifier(Path root, Path workspace, Path output, int port) throws Exception {
        this.root = root.toRealPath(); this.workspace = workspace.toRealPath();
        this.output = output; this.port = port;
        this.bridge = new PhaseOneBridgeVerifier.Bridge();
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> options = new LinkedHashMap<>(); boolean approved=false;
        for (int i=0;i<args.length;i++) {
            if ("--approve-fixture-post".equals(args[i])) {approved=true;continue;}
            if(!args[i].startsWith("--") || i+1>=args.length)throw new IllegalArgumentException("Bad arguments");
            options.put(args[i++].substring(2),args[i]);
        }
        require(approved,"Explicit --approve-fixture-post required before any bridge/network call");
        int port=Integer.parseInt(required(options,"port"));
        require(port!=8765 && port>=1024 && port<=65535 &&
                port==Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT","8765")),
                "Dedicated AGENTDEBUG_PORT required");
        new SpringBootQuoteVerifier(Path.of(required(options,"root")),Path.of(required(options,"workspace")),
                Path.of(required(options,"output")),port).run();
    }
    private void run() throws Exception {
        String owner = null;
        try {
            Map<String,Object> info=bridge.call("GET_E2E_INFO",false);
            require(Path.of(string(info.get("workspace"))).toRealPath().equals(workspace),"Wrong Eclipse workspace");
            require(string(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"),
                    "Unexpected plugin identity");
            require(Files.isRegularFile(workspace.resolve(PROJECT+"/.agentdebug-fixture")),"No reserved Boot fixture marker");
            owner=string(bridge.call("BEGIN_INVESTIGATION",false,"NORMAL").get("investigationId"));
            require(!owner.isBlank(),"Missing investigation ID");
            execute(owner);
            evidence.put("status","PASS");
        } catch(Exception | AssertionError failure) {
            evidence.put("status","FAIL"); evidence.put("reason",failure.toString());
        } finally {
            if (owner != null) {
                try {
                    Map<String,Object> finish=bridge.call("FINISH_INVESTIGATION",true,owner);
                    evidence.put("cleanup","success".equals(finish.get("status")));
                    if (!Boolean.TRUE.equals(evidence.get("cleanup"))) evidence.put("cleanupResponse",finish);
                } catch(Exception err){evidence.put("cleanup",false);evidence.put("cleanupError",err.toString());}
            } else evidence.put("cleanup",false);
            Map<String,Object> report = new LinkedHashMap<>();
            report.put("scope","SB-04/SB-05 deterministic arithmetic fixture, NOT Claude agent behavior");
            report.put("evidence",evidence); report.put("executions",bridge.traces);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output,PhaseOneBridgeVerifier.Json.stringify(report),StandardCharsets.UTF_8);
            System.out.println("boot-quote => "+evidence);
            System.out.println("Report: "+output);
        }
        require("PASS".equals(evidence.get("status"))&&Boolean.TRUE.equals(evidence.get("cleanup")),
                "Spring Boot quote test failed; inspect "+output);
    }
    private void execute(String owner) throws Exception {
        Path source=root.resolve("examples/spring-boot-debugging/src/main/java/")
                .resolve(SERVICE.replace('.','/')+".java");
        List<String> lines=Files.readAllLines(source,StandardCharsets.UTF_8);
        int line=-1;
        for(int i=0;i<lines.size();i++)if(lines.get(i).contains("BigDecimal subtotal =")) {
            require(line==-1,"Ambiguous source breakpoint");line=i+1;
        }
        require(line>0,"Service source marker missing");
        bridge.call("SET_BREAKPOINT",false,SERVICE,line,owner);
        Map<String,Object> start=bridge.call("START_JAVA_APPLICATION",false,PROJECT,MAIN,owner);
        require(!Boolean.TRUE.equals(start.get("reusedSession")),"Existing Boot session must not be borrowed");
        String launch=string(start.get("launchName")); require(!launch.isBlank(),"No launch identity");
        Path tmp=Files.createTempDirectory("agentdebug-boot-quote-");
        Path receipt=tmp.resolve("result.json"),log=tmp.resolve("client.log");
        Path java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java");
        Path classes=root.resolve("testing/target/phase2-classes");
        List<String> command=new ArrayList<>(List.of(java.toString(),"-cp",classes.toString(),
                JavaFixtureQuoteClient.class.getName(),"--workspace",workspace.toString(),
                "--bridge-port",Integer.toString(port),"--launch-name",launch,
                "--receipt",receipt.toString(),"--approve-fixture-post"));
        Process client=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(155).toNanos();
            int thread=-1;
            while(System.nanoTime()<deadline) {
                if (!client.isAlive()) throw new AssertionError("HTTP client exited early ("+client.exitValue()+"): "+read(log));
                Map<String,Object> threads=bridge.call("GET_THREADS",true,launch);
                if ("success".equals(threads.get("status"))) for(Object item:arr(threads.get("threads"))) {
                    Map<String,Object> t=map(item);
                    if (!Boolean.TRUE.equals(t.get("suspended")))continue;
                    int candidate=number(t.get("index"));
                    Map<String,Object> stack=bridge.call("GET_STACK",true,launch,candidate);
                    if (!"success".equals(stack.get("status")))continue;
                    List<Object> frames=arr(stack.get("frames"));
                    if(!frames.isEmpty()) {
                        Map<String,Object> top=map(frames.get(0));
                        if(SERVICE.equals(top.get("className"))&&number(top.get("lineNumber"))==line){thread=candidate;break;}
                    }
                }
                if(thread>=0)break;
                Thread.sleep(200);
            }
            require(thread>=0,"No quote service suspension; client output: "+read(log));
            Map<String,Object> vars=bridge.call("GET_VARIABLES",false,launch,thread,0);
            Map<String,String> locals=new LinkedHashMap<>();
            for(Object item:arr(vars.get("variables"))) {
                Map<String,Object> v=map(item);
                locals.put(string(v.get("name")),string(v.get("value")));
            }
            require("10".equals(locals.get("percent")) && "0".equals(locals.get("rate")),
                    "Expected first wrong value percent=10 rate=0; actual "+locals);
            evidence.put("wrongValue","percent=10, rate=0");
            evidence.put("breakpointLine",line);
            bridge.call("CONTROL_DEBUG",false,launch,thread,"RESUME",owner);
            require(client.waitFor(45,TimeUnit.SECONDS),"HTTP client timeout: "+read(log));
            require(client.exitValue()==0,"HTTP client error: "+read(log));
            Map<String,Object> result=map(PhaseOneBridgeVerifier.Json.parse(Files.readString(receipt)));
            require(number(result.get("status"))==200,"Expected HTTP 200: "+result);
            require(string(result.get("body")).contains("120.00"),
                    "Expected incorrect total 120.00 for fixture: "+result);
            evidence.put("httpStatus",result.get("status"));
            evidence.put("httpBody",result.get("body"));
            evidence.put("scope","real HTTP request, service-frame locals, observed incorrect response");
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            if (Files.isRegularFile(log))evidence.put("httpClientLog",log.toString());
            Files.deleteIfExists(receipt);
        }
    }
    private static String read(Path p){try {return Files.isRegularFile(p)?Files.readString(p):"(no client log)";}catch(Exception e){return e.toString();}}
    private static String required(Map<String,String> options,String key){String s=options.get(key);if(s==null||s.isBlank())throw new IllegalArgumentException("Missing --"+key);return s;}
    private static String string(Object o){return o==null?"":o.toString();}
    private static int number(Object o){return o instanceof Number n?n.intValue():Integer.parseInt(string(o));}
    @SuppressWarnings("unchecked")private static List<Object> arr(Object o){return o instanceof List<?> l?(List<Object>)l:List.of();}
    @SuppressWarnings("unchecked")private static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
