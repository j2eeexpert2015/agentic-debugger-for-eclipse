package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in, installed-Eclipse Phase 2 bridge checks for the repository's disposable
 * Spring Boot fixture. Reuses the Phase 1 bridge protocol and the strict Windows
 * HTTP client; no Claude invocation and no new debugger operations.
 *
 * The local HTTP POST is disabled unless --approve-fixture-post is passed.
 * Never point this verifier at a production Eclipse workspace or live service.
 */
public final class PhaseTwoBridgeVerifier {
    private static final String PKG = "org.learningfromexperience.bootfixture.orders.";
    private static final String PROJECT = "spring-boot-debugging";
    private static final Pattern READY = Pattern.compile("Tomcat started on port (\\d+)");
    private final Path root;
    private final Path workspace;
    private final Path output;
    private final int port;
    private final boolean javaHttpClient;
    private final PhaseOneBridgeVerifier.Bridge bridge;
    private final Map<String,Object> scenarios = new LinkedHashMap<>();

    private PhaseTwoBridgeVerifier(Path root, Path workspace, Path output, int port, boolean javaHttpClient) throws IOException {
        this.root = root;
        this.workspace = workspace;
        this.output = output;
        this.port = port;
        this.javaHttpClient = javaHttpClient;
        this.bridge = new PhaseOneBridgeVerifier.Bridge();
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options = new LinkedHashMap<>();
        boolean approved = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--approve-fixture-post")) { approved = true; continue; }
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException(usage());
            options.put(args[i++].substring(2), args[i]);
        }
        if (!approved) throw new IllegalArgumentException("HTTP fixture requires --approve-fixture-post; " + usage());
        String selected = options.getOrDefault("scenario", "all");
        if (!List.of("all", "validation", "ownership").contains(selected)) throw new IllegalArgumentException(usage());
        Path root = Path.of(options.getOrDefault("root", ".")).toAbsolutePath().normalize();
        Path ws = Path.of(options.getOrDefault("workspace", "")).toAbsolutePath().normalize();
        Path output = Path.of(options.getOrDefault("output", root.resolve("evaluation/reports/phase2-live.json").toString())).toAbsolutePath();
        int port = Integer.parseInt(options.getOrDefault("port", System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765")));
        if (port == 8765 || port < 1024 || port > 65535) throw new IllegalArgumentException("Use a disposable, nondefault Eclipse bridge port");
        if (!Files.isRegularFile(root.resolve("pom.xml")) ||
                !Files.isRegularFile(root.resolve("examples/spring-boot-debugging/pom.xml")))
            throw new IllegalArgumentException("Repository/Boot fixture not found: " + root);
        if (!Files.isDirectory(ws)) throw new IllegalArgumentException("Workspace missing: " + ws);
        // PhaseOneBridgeVerifier.Bridge reads AGENTDEBUG_PORT at class initialization.
        if (Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765")) != port)
            throw new IllegalArgumentException("Set AGENTDEBUG_PORT to exactly --port before invocation");
        String httpClient = options.getOrDefault("http-client", "java");
        if (!"java".equals(httpClient)) throw new IllegalArgumentException("--http-client must be java or powershell");
        PhaseTwoBridgeVerifier v = new PhaseTwoBridgeVerifier(root, ws.toRealPath(), output, port, "java".equals(httpClient));
        v.preflight();
        v.run(selected);
    }

    private void preflight() throws IOException {
        Map<String,Object> info = bridge.call("GET_E2E_INFO", false);
        Path actual = Path.of(text(info.get("workspace"))).toRealPath();
        require(actual.equals(workspace), "Bridge belongs to another workspace: " + actual);
        require(text(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"),
                "Bridge does not report the installed product bundle");
        boolean found = list(bridge.call("LIST_JAVA_APPLICATIONS", false).get("applications")).stream()
                .map(PhaseTwoBridgeVerifier::map)
                .anyMatch(a -> PROJECT.equals(a.get("project")) && (PKG + "OrdersApplication").equals(a.get("mainType")));
        require(found, "Import the standalone spring-boot-debugging Maven fixture before running Phase 2 checks");
    }

    private void run(String selected) throws Exception {
        for (String scenario : selected.equals("all") ? List.of("validation", "ownership") : List.of(selected)) {
            Map<String,Object> evidence = new LinkedHashMap<>();
            String owner = null;
            try {
                owner = text(bridge.call("BEGIN_INVESTIGATION", false, "NORMAL").get("investigationId"));
                require(!owner.isBlank(), "Investigation ID missing");
                if (scenario.equals("validation")) validation(owner, evidence);
                else ownership(owner, evidence);
                evidence.put("status", "PASS");
            } catch (Exception | AssertionError error) {
                evidence.put("status", "FAIL");
                evidence.put("reason", error.toString());
            } finally {
                if (owner != null) {
                    try {
                        Map<String,Object> finish = bridge.call("FINISH_INVESTIGATION", true, owner);
                        evidence.put("cleanup", "success".equals(finish.get("status")));
                        if (!"success".equals(finish.get("status"))) evidence.put("cleanupResponse", finish);
                    } catch (Exception error) {
                        evidence.put("cleanup", false);
                        evidence.put("cleanupError", error.toString());
                    }
                } else evidence.put("cleanup", false);
                scenarios.put(scenario, evidence);
                System.out.println(scenario + " => " + evidence);
            }
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("project", PROJECT);
        report.put("workspace", workspace.toString());
        report.put("bridgePort", port);
        report.put("httpClient", javaHttpClient ? "java" : "powershell");
        report.put("scenarios", scenarios);
        report.put("executions", bridge.traces);
        Files.createDirectories(output.getParent());
        Files.writeString(output, PhaseOneBridgeVerifier.Json.stringify(report), StandardCharsets.UTF_8);
        System.out.println("Report: " + output);
        require(scenarios.values().stream().map(PhaseTwoBridgeVerifier::map)
                .allMatch(x -> "PASS".equals(x.get("status")) && Boolean.TRUE.equals(x.get("cleanup"))),
                "Phase 2 verification failed: " + output);
    }

    private void validation(String owner, Map<String,Object> evidence) throws Exception {
        int throwLine = sourceLine("OrderService", "throw new IllegalArgumentException(\"Quantity must be positive\")");
        int handlerLine = sourceLine("OrderController", "return new ErrorResponse(error.getMessage());");
        bridge.call("SET_BREAKPOINT", false, PKG + "OrderService", throwLine, owner);
        bridge.call("SET_BREAKPOINT", false, PKG + "OrderController", handlerLine, owner);
        String launch = launch(owner);
        int readyPort = waitReady(launch, Duration.ofSeconds(75));
        evidence.put("serverPort", readyPort);
        // The existing PowerShell client independently checks the server PID,
        // loopback listener, exact URL, workspace, consent and launch identity.
        Path receipt = Files.createTempDirectory("phase2-validation-").resolve("invalid-quantity.json");
        Path clientLog = receipt.resolveSibling("http-client.log");
        Process client = startHttpFixtureClient(launch, receipt, true, true, clientLog);
        try {
            int serviceThread = awaitFrame(launch, "OrderService", throwLine, Duration.ofSeconds(45), client, clientLog);
            assertLocal(launch, serviceThread, "quantity", "0");
            evidence.put("serviceThrowLine", throwLine);
            bridge.call("CONTROL_DEBUG", false, launch, serviceThread, "RESUME", owner);
            int handlerThread = awaitFrame(launch, "OrderController", handlerLine, Duration.ofSeconds(45), client, clientLog);
            evidence.put("handlerLine", handlerLine);
            bridge.call("CONTROL_DEBUG", false, launch, handlerThread, "RESUME", owner);
            require(client.waitFor(35, TimeUnit.SECONDS), "HTTP fixture client timed out; output: " + clientLog(clientLog));
            require(client.exitValue() == 0, "HTTP fixture client failed with exit " + client.exitValue()
                    + "; output: " + clientLog(clientLog));
            require(Files.isRegularFile(receipt), "HTTP receipt missing");
            Map<String,Object> result = map(PhaseOneBridgeVerifier.Json.parse(Files.readString(receipt)));
            require(number(result.get("status")) == 400, "Expected HTTP 400: " + result);
            require(text(result.get("body")).contains("Quantity must be positive"), "Incorrect error response: " + result);
            require(("http://127.0.0.1:" + readyPort + "/orders/quote").equals(result.get("url")),
                    "HTTP target does not match selected launch");
            evidence.put("httpStatus", result.get("status"));
            evidence.put("httpBody", result.get("body"));
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            // Preserve the client log on failure. This matters when the POST
            // exits before either Java breakpoint is reached.
            if (Files.isRegularFile(clientLog)) {
                evidence.put("httpClientLog", clientLog.toString());
                evidence.put("httpClientOutput", clientLog(clientLog));
            }
            Files.deleteIfExists(receipt);
            // Do not delete the diagnostic directory: its log can explain a failed run.
        }
    }

    private void ownership(String owner, Map<String,Object> evidence) throws Exception {
        String launch = launch(owner);
        waitReady(launch, Duration.ofSeconds(75));
        String borrowed = text(bridge.call("BEGIN_INVESTIGATION", false, "NORMAL").get("investigationId"));
        boolean borrowerFinished = false;
        try {
            Map<String,Object> reused = bridge.call("START_JAVA_APPLICATION", false, PROJECT, PKG + "OrdersApplication", borrowed);
            require(Boolean.TRUE.equals(reused.get("reusedSession")), "Expected a borrowed, already-running JVM");
            require(launch.equals(reused.get("launchName")), "Borrowed session changed target");
            require("success".equals(bridge.call("FINISH_INVESTIGATION", false, borrowed).get("status")),
                    "Borrower's finish failed");
            borrowerFinished = true;
            Map<String,Object> state = bridge.call("GET_DEBUG_STATE", false, launch);
            require(!Boolean.TRUE.equals(state.get("terminated")), "Borrower terminated the owner's application");
            Map<String,Object> invalid = bridge.call("RESTART_DEBUG", true, launch, borrowed);
            require("error".equals(invalid.get("status")), "Expired investigation restarted someone else's application");
            evidence.put("borrowedLaunchPreserved", true);
            evidence.put("expiredInvestigationRejected", true);
            // An HTTP request without approval must not be sent. The client
            // checks consent before probing any bridge or network endpoint.
            Path noReceipt = Files.createTempDirectory("phase2-consent-").resolve("must-not-exist.json");
            try {
                Process denied = startHttpFixtureClient(launch, noReceipt, false, false,
                        noReceipt.resolveSibling("denied-client.log"));
                require(denied.waitFor(10, TimeUnit.SECONDS), "Consent denial client timed out");
                require(denied.exitValue() != 0 && !Files.exists(noReceipt), "Unapproved HTTP fixture call was accepted");
                evidence.put("unapprovedPostRejected", true);
            } finally {
                Files.deleteIfExists(noReceipt);
                Files.deleteIfExists(noReceipt.resolveSibling("denied-client.log"));
                Files.deleteIfExists(noReceipt.getParent());
            }
        } finally {
            if (!borrowerFinished) bridge.call("FINISH_INVESTIGATION", true, borrowed);
        }
    }

// JAVA_HTTP_ONLY_CLIENT_V1: Java transport; no shell/PowerShell child process.
private Process startHttpFixtureClient(String launch, Path receipt, boolean approved, boolean invalid,
        Path clientLog) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    Path classes = root.resolve("e2e-runner/target/phase2-classes");
    if (!Files.isRegularFile(classes.resolve("org/learningfromexperience/agentdebug/e2e/JavaFixtureQuoteClient.class")))
        throw new IOException("Compile JavaFixtureQuoteClient first");
    List<String> args = new ArrayList<>(List.of(java, "-cp", classes.toString(),
            JavaFixtureQuoteClient.class.getName(), "--workspace", workspace.toString(),
            "--bridge-port", Integer.toString(port), "--launch-name", launch,
            "--receipt", receipt.toString()));
    if (approved) args.add("--approve-fixture-post");
    if (invalid) args.add("--invalid-quantity");
    return new ProcessBuilder(args).redirectErrorStream(true)
            .redirectOutput(clientLog.toFile()).start();
}

    private String launch(String owner) throws IOException {
        Map<String,Object> reply = bridge.call("START_JAVA_APPLICATION", false, PROJECT, PKG + "OrdersApplication", owner);
        require(!Boolean.TRUE.equals(reply.get("reusedSession")), "Refusing to test on an existing user-owned launch");
        String launch = text(reply.get("launchName"));
        require(!launch.isBlank(), "Launch name missing");
        return launch;
    }

    private int waitReady(String launch, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String,Object> state = bridge.call("GET_PROCESS_OUTPUT", true, launch);
            if ("success".equals(state.get("status"))) for (Object obj : list(state.get("processes"))) {
                Map<String,Object> process = map(obj);
                String lines = text(process.get("stdout")) + "\n" + text(process.get("console"));
                Matcher ready = READY.matcher(lines);
                if (ready.find()) return Integer.parseInt(ready.group(1));
                if (lines.contains("APPLICATION FAILED TO START") || lines.contains("APPLICATION FAILED"))
                    throw new AssertionError("Boot fixture failed to start: " + lines.substring(Math.max(0, lines.length()-800)));
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Fixture readiness timeout for " + launch);
    }

    private int awaitFrame(String launch, String classSimple, int expectedLine, Duration timeout,
            Process client, Path clientOutput) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!client.isAlive()) {
                throw new AssertionError("HTTP client exited before " + classSimple + ":" + expectedLine
                        + " (exit=" + client.exitValue() + "); output: " + clientLog(clientOutput));
            }
            Map<String,Object> threads = bridge.call("GET_THREADS", true, launch);
            if ("success".equals(threads.get("status"))) for (Object obj : list(threads.get("threads"))) {
                Map<String,Object> thread = map(obj);
                if (!Boolean.TRUE.equals(thread.get("suspended"))) continue;
                int index = number(thread.get("index"));
                Map<String,Object> stack = bridge.call("GET_STACK", true, launch, index);
                if (!"success".equals(stack.get("status"))) continue;
                List<Object> frames = list(stack.get("frames"));
                if (!frames.isEmpty()) {
                    Map<String,Object> top = map(frames.get(0));
                    if ((PKG + classSimple).equals(top.get("className")) && number(top.get("lineNumber")) == expectedLine)
                        return index;
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("No suspension at " + classSimple + ":" + expectedLine
                + "; HTTP client " + (client.isAlive() ? "still running" : "exited=" + client.exitValue())
                + "; output: " + clientLog(clientOutput));
    }

    private static String clientLog(Path path) {
        try {
            if (!Files.isRegularFile(path)) return "(no client output file)";
            String output = Files.readString(path, StandardCharsets.UTF_8);
            return output.length() > 4000 ? output.substring(output.length() - 4000) : output;
        } catch (IOException e) { return "(cannot read HTTP client output: " + e.getMessage() + ")"; }
    }

    private void assertLocal(String launch, int thread, String key, String expected) throws IOException {
        Map<String,Object> locals = bridge.call("GET_VARIABLES", false, launch, thread, 0);
        boolean matched = list(locals.get("variables")).stream().map(PhaseTwoBridgeVerifier::map)
                .anyMatch(v -> key.equals(v.get("name")) && expected.equals(text(v.get("value"))));
        require(matched, "Expected " + key + "=" + expected + " in suspended frame: " + locals);
    }

    private int sourceLine(String type, String fragment) throws IOException {
        Path source = root.resolve("examples/spring-boot-debugging/src/main/java/")
                .resolve(PKG.replace('.', '/')).resolve(type + ".java");
        List<String> lines = Files.readAllLines(source);
        int match = -1;
        for (int i=0;i<lines.size();i++) if (lines.get(i).contains(fragment)) {
            require(match == -1, "Ambiguous source marker in " + source + ": " + fragment);
            match=i+1;
        }
        require(match > 0, "Source marker not found: " + fragment);
        return match;
    }

    private static String usage() { return "--root PATH --workspace DISPOSABLE_ECLIPSE_WORKSPACE --port NONDEFAULT_PORT [--scenario all|validation|ownership] --approve-fixture-post [--http-client java] [--output REPORT]"; }
    private static int number(Object x) { return x instanceof Number n ? n.intValue() : Integer.parseInt(text(x)); }
    private static String text(Object x) { return x == null ? "" : x.toString(); }
    private static void require(boolean ok, String msg) { if(!ok) throw new AssertionError(msg); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object x) { return (Map<String,Object>)x; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object x) { return x instanceof List<?> l ? (List<Object>)l : List.of(); }
}
