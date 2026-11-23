package org.learningfromexperience.agentdebug.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Additional *direct* Eclipse JDT acceptance cases, not autonomous agent tests.
 *  Runs exclusively against a verified disposable workspace and installed debugger.
 *  Never changes a pre-existing JVM, project, launch configuration, or user marker.
 */
public final class BootGapVerifier {
    private static final String PROJECT = "spring-boot-debugging";
    private static final String ORDER_MAIN = "org.learningfromexperience.bootfixture.orders.OrdersApplication";
    private static final String INVENTORY_MAIN = "org.learningfromexperience.bootfixture.inventory.InventoryApplication";
    private static final String ORDER_TEST = "org.learningfromexperience.bootfixture.orders.OrderServiceTest";
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final List<String> ALL = List.of("two-mains", "junit-invalid-method", "two-services");
    private final Path root, workspace, output;
    private final PhaseOneBridgeVerifier.Bridge bridge;
    private final Map<String,Object> results = new LinkedHashMap<>();

    private BootGapVerifier(Path root, Path workspace, Path output) throws Exception {
        this.root = root.toRealPath();
        this.workspace = workspace.toRealPath();
        this.output = output;
        this.bridge = new PhaseOneBridgeVerifier.Bridge();
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> opts = new LinkedHashMap<>();
        for(int i=0;i<args.length;i++) {
            if(!args[i].startsWith("--") || i+1>=args.length) throw new IllegalArgumentException(usage());
            opts.put(args[i++].substring(2),args[i]);
        }
        String scenario = opts.getOrDefault("scenario","all");
        if(!scenario.equals("all") && !ALL.contains(scenario)) throw new IllegalArgumentException(usage());
        Path root = Path.of(required(opts,"root"));
        Path workspace = Path.of(required(opts,"workspace"));
        Path output = Path.of(required(opts,"output"));
        int port = Integer.parseInt(required(opts,"port"));
        if(port < 1024 || port > 65535 || port == 8765 ||
                Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT","8765")) != port)
            throw new IllegalArgumentException("Use a dedicated nondefault AGENTDEBUG_PORT equal to --port");
        BootGapVerifier v = new BootGapVerifier(root,workspace,output);
        v.preflight();
        v.run(scenario);
    }

    private void preflight() throws Exception {
        require(Files.isRegularFile(root.resolve("pom.xml")),"Not a repository root");
        require(Files.isRegularFile(workspace.resolve(PROJECT+"/.agentdebug-fixture")),
                "Disposable Spring Boot fixture marker missing; refusing test");
        Map<String,Object> info = bridge.call("GET_E2E_INFO",false);
        require("success".equals(info.get("status")),"Bridge not ready");
        require(Path.of(s(info.get("workspace"))).toRealPath().equals(workspace),
                "Wrong Eclipse workspace");
        require(s(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"),
                "Not an installed debugger bundle");
    }

    private void run(String requested) throws Exception {
        for(String scenario:requested.equals("all")?ALL:List.of(requested)) {
            Map<String,Object> evidence = new LinkedHashMap<>();
            try {
                switch(scenario) {
                    case "two-mains" -> twoMains(evidence);
                    case "junit-invalid-method" -> junitInvalid(evidence);
                    case "two-services" -> twoServices(evidence);
                    default -> throw new IllegalArgumentException(scenario);
                }
                evidence.put("status",Boolean.TRUE.equals(evidence.get("cleanup"))?"PASS":"FAIL");
            } catch(Exception | AssertionError e) {
                evidence.put("status","FAIL"); evidence.put("reason",e.toString());
                evidence.putIfAbsent("cleanup",false);
            }
            results.put(scenario,evidence);
            System.out.println("boot-gap/"+scenario+" => "+evidence);
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("generatedAt",Instant.now().toString());
        report.put("scope","Direct installed-Eclipse acceptance; NOT agent selection, STS, or full multi-service matrix");
        report.put("workspace",workspace.toString());
        report.put("scenarios",results);
        report.put("executions",bridge.traces);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,PhaseOneBridgeVerifier.Json.stringify(report),StandardCharsets.UTF_8);
        System.out.println("Report: "+output);
        require(results.values().stream().map(BootGapVerifier::map)
                .allMatch(x->"PASS".equals(x.get("status")) && Boolean.TRUE.equals(x.get("cleanup"))),
                "Boot gap checks failed; review "+output);
    }

    private void twoMains(Map<String,Object> evidence) throws Exception {
        Object before = sessionSnapshot();
        List<Map<String,Object>> apps = rows(bridge.call("LIST_JAVA_APPLICATIONS",false).get("applications"));
        long orders=apps.stream().filter(x->PROJECT.equals(x.get("project")) && ORDER_MAIN.equals(x.get("mainType"))).count();
        long inventory=apps.stream().filter(x->PROJECT.equals(x.get("project")) && INVENTORY_MAIN.equals(x.get("mainType"))).count();
        require(orders==1 && inventory==1,"Both distinct Boot mains must be discoverable (orders="+orders+", inventory="+inventory+")");
        require(before.equals(sessionSnapshot()),"Read-only discovery unexpectedly changed sessions");
        evidence.put("ordersMain",ORDER_MAIN);
        evidence.put("inventoryMain",INVENTORY_MAIN);
        evidence.put("scope","Two actual mains in same fixture project; not Claude ambiguity selection");
        evidence.put("cleanup",true);
    }

    private void junitInvalid(Map<String,Object> evidence) throws Exception {
        String owner = begin();
        boolean clean = false;
        try {
            Object before = sessionSnapshot();
            Map<String,Object> bad = bridge.call("START_JUNIT_TEST",true,PROJECT,ORDER_TEST,
                    "agentdebugMethodThatDoesNotExist",owner);
            require("error".equals(bad.get("status")),"Missing JUnit method unexpectedly launched: "+bad);
            require(before.equals(sessionSnapshot()),"Missing JUnit method changed active debug sessions");
            evidence.put("missingMethodRejected",true);
        } finally {
            clean = "success".equals(bridge.call("FINISH_INVESTIGATION",true,owner).get("status"));
            evidence.put("cleanup",clean);
        }
    }

    private void twoServices(Map<String,Object> evidence) throws Exception {
        // Each Java VM has a separate NORMAL owner. No attempt to start/borrow user-owned sessions.
        String ownerOrders=null, ownerInventory=null;
        boolean cleanupOrders=false, cleanupInventory=false;
        try {
            ownerOrders=begin();
            String orderLaunch = startOwned(ownerOrders,ORDER_MAIN);
            int ordersPort = waitReady(orderLaunch,Duration.ofSeconds(100));
            ownerInventory=begin();
            String inventoryLaunch = startOwned(ownerInventory,INVENTORY_MAIN);
            int inventoryPort = waitReady(inventoryLaunch,Duration.ofSeconds(100));
            require(!orderLaunch.equals(inventoryLaunch),"Both services share a debugger launch");
            require(ordersPort!=inventoryPort,"Two services reported the same HTTP port");
            require(alive(orderLaunch) && alive(inventoryLaunch),"Both owned JVMs must be running together");
            // End the first owned investigation. The second must remain alive and ready.
            cleanupOrders = "success".equals(bridge.call("FINISH_INVESTIGATION",true,ownerOrders).get("status"));
            require(cleanupOrders,"Orders investigation did not clean up");
            ownerOrders=null;
            require(alive(inventoryLaunch),"Finishing Orders investigation killed unrelated Inventory JVM");
            evidence.put("ordersPort",ordersPort);
            evidence.put("inventoryPort",inventoryPort);
            evidence.put("inventoryPreservedAfterOrdersCleanup",true);
            evidence.put("scope","Two simultaneously running fixture JVMs; broader user-owned marker matrix not tested");
        } finally {
            // Attempt BOTH owner cleanups independently even if the first fails.
            if(ownerOrders!=null) {
                try { cleanupOrders = "success".equals(bridge.call("FINISH_INVESTIGATION",true,ownerOrders).get("status")); }
                catch(Exception error) { evidence.put("ordersCleanupError",error.toString()); }
            }
            if(ownerInventory!=null) {
                try { cleanupInventory = "success".equals(bridge.call("FINISH_INVESTIGATION",true,ownerInventory).get("status")); }
                catch(Exception error) { evidence.put("inventoryCleanupError",error.toString()); }
            }
            evidence.put("cleanupOrders",cleanupOrders);
            evidence.put("cleanupInventory",cleanupInventory);
            evidence.put("cleanup",cleanupOrders && cleanupInventory);
        }
    }

    private String begin() throws Exception {
        String owner=s(bridge.call("BEGIN_INVESTIGATION",false,"NORMAL").get("investigationId"));
        require(!owner.isBlank(),"Investigation ID missing");return owner;
    }
    private String startOwned(String owner,String main) throws Exception {
        Map<String,Object> start=bridge.call("START_JAVA_APPLICATION",false,PROJECT,main,owner);
        require(!Boolean.TRUE.equals(start.get("reusedSession")),
                "Existing debug session found: refusing to borrow another developer's JVM");
        String launch=s(start.get("launchName"));
        require(!launch.isBlank(),"No launch identity");return launch;
    }
    private int waitReady(String launch,Duration duration) throws Exception {
        long deadline=System.nanoTime()+duration.toNanos();
        while(System.nanoTime()<deadline) {
            Map<String,Object> output=bridge.call("GET_PROCESS_OUTPUT",true,launch);
            if("success".equals(output.get("status"))) {
                for(Map<String,Object> process:rows(output.get("processes"))) {
                    String content=s(process.get("stdout"))+"\n"+s(process.get("console"));
                    Matcher matcher=PORT.matcher(content);
                    if(matcher.find())return Integer.parseInt(matcher.group(1));
                    if(content.contains("APPLICATION FAILED TO START")) throw new IllegalStateException("Fixture failed to start: "+launch);
                }
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Bounded startup readiness timed out: "+launch);
    }
    private boolean alive(String launch) throws Exception {
        Map<String,Object> state=bridge.call("GET_DEBUG_STATE",true,launch);
        return "success".equals(state.get("status")) && !Boolean.TRUE.equals(state.get("terminated"));
    }
    private Object sessionSnapshot() throws Exception {
        return bridge.call("LIST_DEBUG_SESSIONS",false).get("sessions");
    }
    private static String required(Map<String,String> m,String k) {
        String v=m.get(k); if(v==null || v.isBlank())throw new IllegalArgumentException("Missing --"+k);return v;
    }
    private static String usage() {return "--root REPO --workspace DISPOSABLE_WORKSPACE --port BRIDGE_PORT --scenario all|two-mains|junit-invalid-method|two-services --output REPORT";}
    private static String s(Object v) {return v==null?"":v.toString();}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object obj) {return (Map<String,Object>)obj;}
    private static List<Map<String,Object>> rows(Object input) {
        if(!(input instanceof List<?> list))return List.of();
        List<Map<String,Object>> result=new ArrayList<>();
        for(Object item:list) result.add(map(item));
        return result;
    }
}
