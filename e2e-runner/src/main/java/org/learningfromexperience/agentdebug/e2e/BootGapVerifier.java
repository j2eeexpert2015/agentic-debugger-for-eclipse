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
    private static final String FAILURE_PKG = "org.learningfromexperience.bootfixture.failures.";
    private static final String ORDER_TEST = "org.learningfromexperience.bootfixture.orders.OrderServiceTest";
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final List<String> ALL = List.of("two-mains", "junit-invalid-method", "two-services", "startup-failure", "readiness-timeout", "port-conflict", "owned-isolation");
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
                    case "startup-failure" -> failureMode("StartupFailureApplication", "AGENTDEBUG_EXPECTED_STARTUP_FAILURE", evidence);
                    case "port-conflict" -> failureMode("PortConflictApplication", "AGENTDEBUG_EXPECTED_PORT_CONFLICT", evidence);
                    case "readiness-timeout" -> readinessTimeout(evidence);
                    case "owned-isolation" -> ownedIsolation(evidence);
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

    /** Failure cases require independent PID lifecycle evidence, not just JDI target state.
     * If Eclipse suspends a thread after the expected marker, resume only this investigation's
     * fixture JVM. Never resume a borrowed launch or treat an unknown state as termination.
     */
    private void failureMode(String main, String marker, Map<String,Object> evidence) throws Exception {
        String owner = begin();
        boolean cleaned = false;
        String launch = "";
        boolean markerObserved = false;
        boolean unexpectedSuccess = false;
        boolean exited = false;
        boolean observedLive = false;
        long fixturePid = -1;
        int controlledResumes = 0;
        String lastState = "not-queried";
        String outputSample = "";
        String lastSuspension = "none";
        try {
            launch = startOwned(owner, FAILURE_PKG + main);
            evidence.put("launchName", launch);
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                String current = processOutput(launch);
                markerObserved |= current.contains(marker);
                unexpectedSuccess |= current.contains("AGENTDEBUG_UNEXPECTED_")
                        || current.contains("Tomcat started on port");
                if (!current.isBlank()) outputSample = current.substring(Math.max(0,current.length()-1200));
                if (fixturePid <= 0) {
                    Matcher match = Pattern.compile("AGENTDEBUG_FIXTURE_PID=(\\d+)").matcher(current);
                    if (match.find()) fixturePid = Long.parseLong(match.group(1));
                }
                Map<String,Object> state = bridge.call("GET_DEBUG_STATE", true, launch);
                lastState = s(state.get("status")) + " terminated=" + s(state.get("terminated"))
                        + " suspended=" + s(state.get("suspended"))
                        + " disconnected=" + s(state.get("disconnected"));
                boolean debugTerminated = Boolean.TRUE.equals(state.get("terminated"));
                if (fixturePid > 0) {
                    var handle = ProcessHandle.of(fixturePid);
                    observedLive |= handle.isPresent() && handle.get().isAlive();
                    exited = handle.isEmpty() || !handle.get().isAlive();
                }
                if (markerObserved && !exited && Boolean.TRUE.equals(state.get("suspended"))) {
                    Map<String,Object> threads = bridge.call("GET_THREADS", true, launch);
                    if ("success".equals(threads.get("status"))) {
                        for (Map<String,Object> thread : rows(threads.get("threads"))) {
                            if (!Boolean.TRUE.equals(thread.get("suspended"))) continue;
                            int index = ((Number)thread.get("index")).intValue();
                            Map<String,Object> stack = bridge.call("GET_STACK", true, launch, Integer.toString(index));
                            List<Map<String,Object>> frames = rows(stack.get("frames"));
                            String top = frames.isEmpty() ? "unavailable" : s(frames.get(0).get("className"))
                                    + "." + s(frames.get(0).get("methodName")) + ":"
                                    + s(frames.get(0).get("lineNumber"));
                            lastSuspension = "thread=" + s(thread.get("name")) + ", top=" + top;
                            evidence.put("lastSuspension", lastSuspension);
                            // Strictly own investigation; finite controlled resumes after the exact
                            // failure marker; never change an unrelated session or breakpoint.
                            if (controlledResumes < 3) {
                                Map<String,Object> answer = bridge.call("CONTROL_DEBUG", true,
                                        launch, Integer.toString(index), "RESUME", owner);
                                if ("success".equals(answer.get("status"))) controlledResumes++;
                                else evidence.put("resumeError", answer);
                            }
                            break; // one thread per polling iteration, bounded by resume count
                        }
                    }
                }
                if (unexpectedSuccess || (markerObserved && exited && fixturePid > 0)) break;
                // A JDI event alone is not proof of OS termination; keep observing if PID unknown.
                if (debugTerminated && fixturePid <= 0 && markerObserved) break;
                Thread.sleep(200);
            }
            evidence.put("markerObserved", markerObserved);
            evidence.put("fixturePid", fixturePid > 0 ? fixturePid : null);
            evidence.put("osProcessExitObserved", exited && fixturePid > 0);
            evidence.put("osProcessLiveObserved", observedLive);
            evidence.put("controlledResumes", controlledResumes);
            evidence.put("unexpectedSuccessObserved", unexpectedSuccess);
            evidence.put("lastDebugState", lastState);
            evidence.put("lastSuspension", lastSuspension);
            require(!unexpectedSuccess, "Failed Boot fixture unexpectedly reported success: " + main);
            require(markerObserved, "Expected failure marker never observed for " + main
                    + " (state=" + lastState + "; output=" + outputSample + ")");
            require(fixturePid > 0, "Could not identify fixture JVM PID; refusing to infer termination: " + main
                    + " (state=" + lastState + "; output=" + outputSample + ")");
            require(exited, "Expected Boot failure observed but fixture JVM is still alive: " + main
                    + " PID=" + fixturePid + " (state=" + lastState + "; suspension=" + lastSuspension
                    + "; output=" + outputSample + ")");
            evidence.put("expectedMarker", marker);
            evidence.put("startupSucceeded", false);
            evidence.put("jvmTerminated", true);
            evidence.put("scope", "Installed Eclipse failure with independent OS PID exit confirmation; no Claude evaluation");
        } finally {
            try { cleaned = "success".equals(bridge.call("FINISH_INVESTIGATION", true, owner).get("status")); }
            finally { evidence.put("cleanup", cleaned); }
        }
    }

    /** Bounded negative readiness assertion; tests the direct verifier, not an MCP readiness API. */
    private void readinessTimeout(Map<String,Object> evidence) throws Exception {
        String owner=begin();
        boolean cleaned=false;
        try {
            String launch=startOwned(owner, FAILURE_PKG + "NeverReadyApplication");
            long start=System.nanoTime();
            long until=start+Duration.ofSeconds(6).toNanos();
            boolean marker=false;
            while(System.nanoTime()<until) {
                String out=processOutput(launch);
                marker |= out.contains("AGENTDEBUG_NONWEB_STARTED");
                require(!out.contains("Tomcat started on port"),"A non-web fixture started a listening web server");
                Thread.sleep(200);
            }
            require(marker,"Non-web Spring application never reached running fixture state");
            require(alive(launch),"Never-ready fixture terminated before timeout");
            evidence.put("timeoutMillis",Duration.ofNanos(System.nanoTime()-start).toMillis());
            evidence.put("readinessDetected",false);
            evidence.put("jvmAliveUntilCleanup",true);
            evidence.put("scope","Bounded absence of HTTP readiness; no false success from Java launch");
        } finally {
            try { cleaned="success".equals(bridge.call("FINISH_INVESTIGATION",true,owner).get("status")); }
            finally { evidence.put("cleanup",cleaned); }
        }
    }

    /** Stronger two-owner isolation: finishing one owned JVM cannot terminate the other.
     * Does not mutate or inspect any developer-owned breakpoint markers. */
    private void ownedIsolation(Map<String,Object> evidence) throws Exception {
        String first=null, second=null;
        boolean cleanedFirst=false,cleanedSecond=false;
        try {
            Object before=sessionSnapshot();
            first=begin();
            String firstLaunch=startOwned(first, ORDER_MAIN);
            int firstPort=waitReady(firstLaunch,Duration.ofSeconds(100));
            second=begin();
            String secondLaunch=startOwned(second, INVENTORY_MAIN);
            int secondPort=waitReady(secondLaunch,Duration.ofSeconds(100));
            require(firstPort!=secondPort,"Both fixtures share the same HTTP port");
            require(alive(firstLaunch) && alive(secondLaunch),"One JVM was not alive before cleanup");
            cleanedSecond="success".equals(bridge.call("FINISH_INVESTIGATION",true,second).get("status"));
            require(cleanedSecond,"Inventory cleanup failed"); second=null;
            require(alive(firstLaunch),"Inventory cleanup terminated the Orders JVM");
            evidence.put("ordersPreservedAfterInventoryCleanup",true);
            evidence.put("baselineSessions",before instanceof List<?> l ? l.size() : -1);
            evidence.put("scope","Two NORMAL-owned fixture JVMs; user-owned breakpoints not exercised");
        } finally {
            if(second!=null) try { cleanedSecond="success".equals(bridge.call("FINISH_INVESTIGATION",true,second).get("status")); }
                catch(Exception e) {evidence.put("secondCleanupError",e.toString());}
            if(first!=null) try { cleanedFirst="success".equals(bridge.call("FINISH_INVESTIGATION",true,first).get("status")); }
                catch(Exception e) {evidence.put("firstCleanupError",e.toString());}
            evidence.put("cleanupFirst",cleanedFirst);
            evidence.put("cleanupSecond",cleanedSecond);
            evidence.put("cleanup",cleanedFirst && cleanedSecond);
        }
    }

    private String processOutput(String launch) throws Exception {
        Map<String,Object> response=bridge.call("GET_PROCESS_OUTPUT",true,launch);
        if(!"success".equals(response.get("status"))) return "";
        StringBuilder sb=new StringBuilder();
        for(Map<String,Object> item:rows(response.get("processes"))) {
            sb.append(s(item.get("stdout"))).append('\n').append(s(item.get("stderr")))
                .append('\n').append(s(item.get("console")));
        }
        return sb.toString();
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
    private static String usage() {return "--root REPO --workspace DISPOSABLE_WORKSPACE --port BRIDGE_PORT --scenario all|two-mains|junit-invalid-method|two-services|startup-failure|readiness-timeout|port-conflict|owned-isolation --output REPORT";}
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
