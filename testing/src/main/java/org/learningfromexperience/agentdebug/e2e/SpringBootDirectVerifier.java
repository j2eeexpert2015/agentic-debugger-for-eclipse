package org.learningfromexperience.agentdebug.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Additional direct, skill-free Spring Boot acceptance checks. This test is deliberately
 * separate from PhaseTwoBridgeVerifier: its already-verified validation/ownership tests
 * remain unchanged. Each started JVM belongs to a NORMAL investigation and is cleaned up.
 *
 * Covered: Boot main discovery (SB-01 fixture case), owned startup and readiness (SB-03
 * successful-start case). NOT covered: multi-app ambiguity, failed startup, STS launch
 * delegates, Claude decisions, or the full concurrent ownership matrix.
 */
public final class SpringBootDirectVerifier {
    private static final String PROJECT = "spring-boot-debugging";
    private static final String MAIN = "org.learningfromexperience.bootfixture.orders.OrdersApplication";
    private static final Pattern READY = Pattern.compile("Tomcat started on port (\\d+)");
    private final PhaseOneBridgeVerifier.Bridge bridge;
    private final Path workspace;
    private final Path output;
    private final Map<String,Map<String,Object>> results = new LinkedHashMap<>();

    private SpringBootDirectVerifier(Path workspace, Path output) throws Exception {
        this.workspace = workspace.toRealPath();
        this.output = output;
        this.bridge = new PhaseOneBridgeVerifier.Bridge();
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 == args.length) throw new IllegalArgumentException(usage());
            opts.put(args[i++].substring(2), args[i]);
        }
        String selected = opts.getOrDefault("scenario", "all");
        if (!List.of("all", "discovery", "readiness").contains(selected)) throw new IllegalArgumentException(usage());
        Path ws = Path.of(required(opts,"workspace"));
        Path report = Path.of(required(opts,"output"));
        int port = Integer.parseInt(required(opts,"port"));
        if (port == 8765 || port < 1024 || port > 65535
                || Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765")) != port)
            throw new IllegalArgumentException("Dedicated port and AGENTDEBUG_PORT must match");
        SpringBootDirectVerifier runner = new SpringBootDirectVerifier(ws, report);
        runner.preflight();
        runner.run(selected);
    }

    private void preflight() throws Exception {
        Map<String,Object> info = bridge.call("GET_E2E_INFO",false);
        require("success".equals(info.get("status")),"Bridge identity failed");
        require(Path.of(string(info.get("workspace"))).toRealPath().equals(workspace),
                "Refusing unrelated Eclipse workspace");
        require(string(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"),
                "Installed debugger bundle not verified");
        require(Files.isRegularFile(workspace.resolve(PROJECT+"/.agentdebug-fixture")),
                "Missing dedicated Boot fixture marker");
    }

    private void run(String selected) throws Exception {
        for (String scenario : "all".equals(selected) ? List.of("discovery","readiness") : List.of(selected)) {
            Map<String,Object> result = new LinkedHashMap<>();
            String owner = null;
            try {
                if (scenario.equals("discovery")) {
                    discover(result);
                    result.put("cleanup",true); // read-only scenario
                } else {
                    owner = string(bridge.call("BEGIN_INVESTIGATION",false,"NORMAL").get("investigationId"));
                    require(!owner.isBlank(),"No investigation ID");
                    readiness(owner,result);
                }
                result.put("status","PASS");
            } catch (Exception | AssertionError failure) {
                result.put("status","FAIL");
                result.put("reason",failure.toString());
            } finally {
                if (owner != null) {
                    try {
                        Map<String,Object> end = bridge.call("FINISH_INVESTIGATION",true,owner);
                        result.put("cleanup","success".equals(end.get("status")));
                        if (!Boolean.TRUE.equals(result.get("cleanup"))) result.put("cleanupResponse",end);
                    } catch (Exception error) {result.put("cleanup",false);result.put("cleanupError",error.toString());}
                }
                results.put(scenario,result);
                System.out.println("boot-"+scenario+" => "+result);
            }
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("scope","direct Spring Boot discovery and successful readiness; not full SB-01..SB-10 acceptance");
        report.put("workspace",workspace.toString());
        report.put("scenarios",results);
        report.put("executions",bridge.traces);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,PhaseOneBridgeVerifier.Json.stringify(report),StandardCharsets.UTF_8);
        require(results.values().stream().allMatch(x->"PASS".equals(x.get("status"))
                    && Boolean.TRUE.equals(x.get("cleanup"))),"Spring Boot direct tests failed: "+output);
        System.out.println("PASS: "+output);
    }

    private void discover(Map<String,Object> evidence) throws Exception {
        Map<String,Object> list = bridge.call("LIST_JAVA_APPLICATIONS",false);
        long matches = arr(list.get("applications")).stream().map(SpringBootDirectVerifier::map)
                .filter(a-> PROJECT.equals(a.get("project")) && MAIN.equals(a.get("mainType"))).count();
        require(matches==1,"Expected one identifiable Spring Boot fixture main; found "+matches);
        evidence.put("fixtureMain",MAIN);
        evidence.put("matches",matches);
        evidence.put("scope","one known fixture; ambiguity with two Boot applications not tested");
    }

    private void readiness(String owner,Map<String,Object> evidence) throws Exception {
        Map<String,Object> app = bridge.call("START_JAVA_APPLICATION",false,PROJECT,MAIN,owner);
        require(!Boolean.TRUE.equals(app.get("reusedSession")),
                "Refusing to borrow an existing debug process for the startup test");
        String launch = string(app.get("launchName"));
        require(!launch.isBlank(),"No launch identity");
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while (System.nanoTime()<deadline) {
            Map<String,Object> output = bridge.call("GET_PROCESS_OUTPUT",true,launch);
            if ("success".equals(output.get("status"))) {
                for (Object item:arr(output.get("processes"))) {
                    Map<String,Object> p=map(item);
                    String text=string(p.get("stdout"))+"\n"+string(p.get("console"));
                    var match=READY.matcher(text);
                    if (match.find()) {
                        int port=Integer.parseInt(match.group(1));
                        require(port>0 && port<=65535,"Invalid reported Spring Boot port");
                        Map<String,Object> state=bridge.call("GET_DEBUG_STATE",false,launch);
                        require(!Boolean.TRUE.equals(state.get("terminated")),"Boot launch terminated");
                        evidence.put("launch",launch); evidence.put("serverPort",port);
                        evidence.put("scope","successful startup and running state only");
                        return;
                    }
                    if (text.contains("APPLICATION FAILED TO START") || text.contains("APPLICATION FAILED"))
                        throw new AssertionError("Spring Boot fixture failed startup: "+text.substring(Math.max(0,text.length()-1000)));
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Spring Boot fixture did not become ready within 90 seconds");
    }

    private static String usage() {return "--workspace PATH --port PORT --output PATH [--scenario all|discovery|readiness]";}
    private static String required(Map<String,String> m,String key) {
        String v=m.get(key); if (v==null || v.isBlank()) throw new IllegalArgumentException("Missing --"+key);return v;
    }
    private static String string(Object o) {return o==null?"":o.toString();}
    private static void require(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") private static List<Object> arr(Object o){return o instanceof List<?> x?(List<Object>)x:List.of();}
}
