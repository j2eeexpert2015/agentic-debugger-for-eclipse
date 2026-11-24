import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** No CLI calls, no Eclipse and no Claude usage. Replays invented structured MCP tool events. */
public final class ClaudeEvidenceGateSelfTest {
    private static final List<String> lines=new ArrayList<>();
    private static int next;
    private static void add(String name,Map<String,Object> input,Map<String,Object> reply){
        String id="selftest-"+(++next);
        var use=Map.of("type","assistant","message",Map.of("content",List.of(Map.of("type","tool_use","id",id,"name","mcp__eclipse-debugger__"+name,"input",input))));
        var res=Map.of("type","user","message",Map.of("content",List.of(Map.of("type","tool_result","tool_use_id",id,"content",List.of(Map.of("type","text","text",ClaudeEvidenceGate.Json.render(reply)))))));
        lines.add(ClaudeEvidenceGate.Json.render(use));lines.add(ClaudeEvidenceGate.Json.render(res));
    }
    private static Map<String,Object> m(Object... args){Map<String,Object> t=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)t.put((String)args[i],args[i+1]);return t;}
    private static String rawState(boolean extra){return ClaudeEvidenceGate.Json.render(m("port",21766,"GET_E2E_INFO",m("status","success"),"LIST_DEBUG_SESSIONS",m("status","success","sessions",extra?List.of(m("name","leftover")):List.of()),"LIST_BREAKPOINTS",m("status","success","breakpoints",List.of()),"LIST_EXCEPTION_BREAKPOINTS",m("status","success","breakpoints",List.of()),"LIST_RUN_CONFIGURATIONS",m("status","success","configurations",List.of())));}
    public static void main(String[] args)throws Exception{
        Path tmp=Files.createTempDirectory("claude-evidence-selftest-");
        Path before=tmp.resolve("before.json"),after=tmp.resolve("after.json"),bad=tmp.resolve("bad.json"),trace=tmp.resolve("sample.claude.jsonl");
        Files.writeString(before,rawState(false));Files.writeString(after,rawState(false));Files.writeString(bad,rawState(true));
        String investigation="inv-test",launch="Agentic Debugger - spring-boot-debugging - OrdersApplication",request="request-test";
        add("begin_debug_investigation",Map.of(),m("status","success","investigationId",investigation,"mode","NORMAL"));
        add("start_java_application",m("projectName","spring-boot-debugging","mainType","org.learningfromexperience.bootfixture.orders.OrdersApplication","investigationId",investigation),m("status","success","launchName",launch));
        add("start_fixture_http_request",m("scenario","discount","launchName",launch,"investigationId",investigation),m("status","PENDING","method","POST","url","http://127.0.0.1:65000/orders/quote","requestId",request,"bodySent","{\"quantity\":2,\"unitPrice\":60,\"discountPercent\":10}"));
        add("wait_for_debug_pause",m("launchName",launch),m("status","success","threads",List.of(m("name","http-nio-worker","suspended",true,"index",3))));
        add("get_stack_frames",m("launchName",launch,"threadIndex",3),m("status","success","frames",List.of(m("className","org.learningfromexperience.bootfixture.orders.OrderService","methodName","quote"))));
        add("get_frame_variables",m("launchName",launch,"threadIndex",3,"frameIndex",0),m("status","success","variables",List.of(m("name","percent","value","10"),m("name","rate","value","0"))));
        add("control_debug_session",m("launchName",launch,"threadIndex",3,"action","RESUME","investigationId",investigation),m("status","success","accepted",true));
        add("get_fixture_http_request",m("requestId",request),m("status","COMPLETE","requestId",request,"httpStatus",200,"body","{\"total\":120.00}"));
        add("finish_debug_investigation",m("investigationId",investigation),m("status","success","investigationId",investigation,"action","CLEANUP","completed",true));
        Files.write(trace,lines,StandardCharsets.UTF_8);
        String pass=ClaudeEvidenceGate.grade("boot-quote",trace,before,after).verdict;
        String changed=ClaudeEvidenceGate.grade("boot-quote",trace,before,bad).verdict;
        String noInventory=ClaudeEvidenceGate.grade("boot-quote",trace,null,null).verdict;
        var modified=new ArrayList<>(lines);
        for(int i=0;i<modified.size();i++)if(modified.get(i).contains("\\\"rate\\\""))modified.set(i,modified.get(i).replace("\\\"rate\\\"","\\\"notRate\\\""));
        Path missing=tmp.resolve("missing.jsonl");Files.write(missing,modified,StandardCharsets.UTF_8);
        String fake=ClaudeEvidenceGate.grade("boot-quote",missing,before,after).verdict;
        if(!"PASS".equals(pass)||!"FAIL".equals(changed)||!"PARTIAL".equals(noInventory)||!"FAIL".equals(fake))throw new AssertionError("Expected PASS FAIL PARTIAL FAIL; got "+pass+" "+changed+" "+noInventory+" "+fake);
        System.out.println("SELFTEST PASS: valid runtime + unchanged inventory=PASS; altered inventory=FAIL; missing inventory=PARTIAL; incorrect runtime=FAIL");
    }
}
