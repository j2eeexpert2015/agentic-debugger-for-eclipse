package org.learningfromexperience.agentdebug.e2e;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs Phase 1 debugger acceptance scenarios against an already running Eclipse bridge.
 * Does not invoke Claude, mutate user-owned breakpoints, or require Python.
 *
 * @author Ayan Dutta
 */
public final class PhaseOneBridgeVerifier {
    private static final String PKG = "org.learningfromexperience.debugfixtures";
    private static final List<String> ALL = List.of("conditional", "exception", "junit", "variables", "trace", "pause-restart", "filters");
    private final Path root;
    private final String project;
    private final Bridge bridge;
    private final Map<String, Object> results = new LinkedHashMap<>();

    private PhaseOneBridgeVerifier(Path root, String project) throws IOException {
        this.root = root;
        this.project = project;
        this.bridge = new Bridge();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Usage: [--scenario all|conditional|exception|junit|variables|trace|pause-restart|filters] [--project phase-one-debugging] [--root repository-root] [--output report.json]");
            opts.put(args[i++].substring(2), args[i]);
        }
        String requested = opts.getOrDefault("scenario", "all");
        if (!"all".equals(requested) && !ALL.contains(requested)) throw new IllegalArgumentException("Unknown scenario: " + requested);
        Path root = Path.of(opts.getOrDefault("root", ".")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) throw new IllegalArgumentException("--root must be repository root: " + root);
        PhaseOneBridgeVerifier verifier = new PhaseOneBridgeVerifier(root, opts.getOrDefault("project", "phase-one-debugging"));
        Path output = Path.of(opts.getOrDefault("output", root.resolve("evaluation/reports/phase1-live.json").toString()));
        boolean pass = verifier.run(requested, output);
        if (!pass) throw new IllegalStateException("Phase 1 bridge verification failed; review " + output);
    }

    private boolean run(String requested, Path output) throws IOException {
        List<String> selected = "all".equals(requested) ? ALL : List.of(requested);
        for (String scenario : selected) {
            String investigation = null;
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                investigation = str(bridge.call("BEGIN_INVESTIGATION", false, "NORMAL").get("investigationId"));
                require(!investigation.isBlank(), "investigation ID missing");
                switch (scenario) {
                    case "conditional" -> conditional(investigation);
                    case "exception" -> exception(investigation);
                    case "junit" -> junit(investigation);
                    case "variables" -> variables(investigation);
                    case "trace" -> trace(investigation);
                    case "pause-restart" -> pauseRestart(investigation);
                    case "filters" -> filters(investigation);
                    default -> throw new IllegalArgumentException(scenario);
                }
                result.put("status", "PASS");
            } catch (Exception ex) {
                result.put("status", "FAIL");
                result.put("error", ex.toString());
            } finally {
                if (investigation != null) {
                    try {
                        result.put("cleanup", "success".equals(str(bridge.call("FINISH_INVESTIGATION", false, investigation).get("status"))));
                    } catch (Exception ex) {
                        result.put("cleanup", false);
                        result.put("cleanupError", ex.toString());
                    }
                } else result.put("cleanup", false);
                results.put(scenario, result);
                System.out.println(scenario + ": " + result);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("project", project);
        report.put("scenarios", results);
        report.put("executions", bridge.traces);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, Json.stringify(report), StandardCharsets.UTF_8);
        System.out.println("Report: " + output.toAbsolutePath());
        return results.values().stream().map(PhaseOneBridgeVerifier::obj)
                .allMatch(x -> "PASS".equals(x.get("status")) && Boolean.TRUE.equals(x.get("cleanup")));
    }

    private int line(String name, String fragment) throws IOException {
        Path file = root.resolve("examples/phase-one-debugging/src/main/java/")
                .resolve(PKG.replace('.', '/')).resolve(name + ".java");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int found = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(fragment)) {
            require(found == -1, "Ambiguous source fragment in " + file + ": " + fragment);
            found = i + 1;
        }
        require(found > 0, "Source fragment missing in " + file + ": " + fragment);
        return found;
    }

    private int waitSuspended(String launch, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            Map<String, Object> response = bridge.call("GET_THREADS", true, launch);
            if ("success".equals(response.get("status")))
                for (Object value : arr(response.get("threads"))) {
                    Map<String, Object> thread = obj(value);
                    if (Boolean.TRUE.equals(thread.get("suspended"))) return number(thread.get("index"));
                }
            Thread.sleep(200);
        }
        throw new AssertionError("No suspended thread for " + launch + " after " + timeout);
    }

    private String start(String main, String investigation) throws IOException {
        return str(bridge.call("START_JAVA_APPLICATION", false, project, PKG + "." + main, investigation).get("launchName"));
    }

    private void assertLocal(String launch, int thread, String name, String value) throws IOException {
        Map<String, Object> response = bridge.call("GET_VARIABLES", false, launch, thread, 0);
        for (Object entry : arr(response.get("variables"))) {
            Map<String, Object> variable = obj(entry);
            if (name.equals(variable.get("name")) && str(variable.get("value")).contains(value)) return;
        }
        throw new AssertionError("Missing local " + name + " containing " + value + ": " + response);
    }

    private void conditional(String investigation) throws Exception {
        int line = line("OrderCalculator", "double discountRate =");
        String type = PKG + ".OrderCalculator";
        Map<String, Object> set = bridge.call("SET_CONDITIONAL_BREAKPOINT", false, type, line, "orderId == 1057", investigation);
        require(Boolean.TRUE.equals(set.get("created")) && Boolean.FALSE.equals(set.get("tracepoint")), "Conditional breakpoint not created: " + set);
        require("error".equals(bridge.call("SET_CONDITIONAL_BREAKPOINT", true, type, line, "orderId == 1000", investigation).get("status")), "Existing condition unexpectedly overwritten");
        String launch = start("OrderBatchApplication", investigation);
        int index = waitSuspended(launch, Duration.ofSeconds(20));
        assertLocal(launch, index, "orderId", "1057");
        assertLocal(launch, index, "discountPercent", "10");
        boolean listed = arr(bridge.call("LIST_BREAKPOINTS", false).get("breakpoints")).stream().map(PhaseOneBridgeVerifier::obj)
                .anyMatch(x -> type.equals(x.get("typeName")) && number(x.get("lineNumber")) == line && "orderId == 1057".equals(x.get("condition")));
        require(listed, "Conditional breakpoint absent from listing");
        bridge.call("CONTROL_DEBUG", false, launch, index, "RESUME", investigation);
        Thread.sleep(500);
        Map<String, Object> threads = bridge.call("GET_THREADS", true, launch);
        for (Object t : arr(threads.get("threads"))) require(!Boolean.TRUE.equals(obj(t).get("suspended")), "Unexpected suspension after matching order: " + threads);
    }

    private void exception(String investigation) throws Exception {
        String type = "java.lang.IllegalStateException";
        require("error".equals(bridge.call("SET_EXCEPTION_BREAKPOINT", true, type, false, false, investigation).get("status")), "Invalid exception configuration accepted");
        require(Boolean.TRUE.equals(bridge.call("SET_EXCEPTION_BREAKPOINT", false, type, true, true, investigation).get("created")), "Exception breakpoint not created");
        require("error".equals(bridge.call("SET_EXCEPTION_BREAKPOINT", true, type, true, false, investigation).get("status")), "Existing exception breakpoint modified");
        require(arr(bridge.call("LIST_EXCEPTION_BREAKPOINTS", false).get("breakpoints")).stream().map(PhaseOneBridgeVerifier::obj)
                .anyMatch(x -> type.equals(x.get("exceptionName")) && "agent".equals(x.get("owner"))), "Agent-owned exception breakpoint missing");
        String launch = start("ExceptionApplication", investigation);
        int index = waitSuspended(launch, Duration.ofSeconds(20));
        require(arr(bridge.call("GET_STACK", false, launch, index).get("frames")).stream().map(PhaseOneBridgeVerifier::obj)
                .anyMatch(x -> str(x.get("className")).contains(PKG + ".ExceptionApplication")), "Wrong exception suspension stack");
    }

    private void junit(String investigation) throws Exception {
        String type = PKG + ".OrderCalculatorTest";
        require(arr(bridge.call("LIST_JUNIT_TESTS", false).get("tests")).stream().map(PhaseOneBridgeVerifier::obj)
                .anyMatch(x -> type.equals(x.get("typeName")) && "appliesTenPercentDiscount".equals(x.get("methodName"))), "JUnit test not discovered");
        bridge.call("SET_BREAKPOINT", false, PKG + ".OrderCalculator", line("OrderCalculator", "double discountRate ="), investigation);
        String launch = str(bridge.call("START_JUNIT_TEST", false, project, type, "appliesTenPercentDiscount", investigation).get("launchName"));
        int index = waitSuspended(launch, Duration.ofSeconds(30));
        List<Object> frames = arr(bridge.call("GET_STACK", false, launch, index).get("frames"));
        require(!frames.isEmpty() && (PKG + ".OrderCalculator").equals(obj(frames.get(0)).get("className")), "JUnit stopped in unexpected frame: " + frames);
    }

    private void variables(String investigation) throws Exception {
        bridge.call("SET_BREAKPOINT", false, PKG + ".NestedObjectApplication", line("NestedObjectApplication", "double total = sum(order);"), investigation);
        String launch = start("NestedObjectApplication", investigation);
        int index = waitSuspended(launch, Duration.ofSeconds(20));
        require("error".equals(bridge.call("EXPAND_VARIABLE", true, launch, index, 0, "order.customer", 0, 0).get("status")), "Invalid page size accepted");
        require(arr(bridge.call("EXPAND_VARIABLE", false, launch, index, 0, "order.customer", 0, 20).get("children")).stream().map(PhaseOneBridgeVerifier::obj)
                .anyMatch(x -> str(x.get("name")).contains("address")), "Customer address not expandable");
        require(arr(bridge.call("EXPAND_VARIABLE", false, launch, index, 0, "order.customer.address", 0, 20).get("children")).stream().map(PhaseOneBridgeVerifier::obj)
                .anyMatch(x -> str(x.get("value")).contains("Kolkata")), "Nested address value missing");
    }

    private void trace(String investigation) throws Exception {
        Map<String, Object> response = bridge.call("SET_TRACEPOINT", false, PKG + ".OrderCalculator",
                line("OrderCalculator", "double discountRate ="), "\"TRACE-ORDER \" + orderId", investigation);
        require(Boolean.TRUE.equals(response.get("created")) && Boolean.TRUE.equals(response.get("tracepoint")), "Tracepoint not installed: " + response);
        String launch = start("OrderBatchApplication", investigation);
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < end) {
            Map<String, Object> output = bridge.call("GET_PROCESS_OUTPUT", true, launch);
            if (Json.stringify(output).contains("TRACE-ORDER 1057")) return;
            Thread.sleep(300);
        }
        throw new AssertionError("Trace output not observable in process stdout (may be Eclipse evaluation console)");
    }

    private void pauseRestart(String investigation) throws Exception {
        String launch = start("LongRunningApplication", investigation);
        int index = 0;
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < end) {
            List<Object> threads = arr(bridge.call("GET_THREADS", true, launch).get("threads"));
            if (!threads.isEmpty()) {
                index = threads.stream().map(PhaseOneBridgeVerifier::obj)
                        .filter(t -> "main".equals(t.get("name"))).map(t -> number(t.get("index"))).findFirst().orElse(0);
                break;
            }
            Thread.sleep(200);
        }
        bridge.call("CONTROL_DEBUG", false, launch, index, "PAUSE", investigation);
        require(Boolean.TRUE.equals(bridge.call("GET_DEBUG_STATE", false, launch).get("suspended")), "Pause did not suspend the application");
        require("error".equals(bridge.call("RESTART_DEBUG", true, launch, "some-other-investigation-id").get("status")), "Unauthorized restart allowed");
        require(Boolean.TRUE.equals(bridge.call("RESTART_DEBUG", false, launch, investigation).get("restarted")), "Restart failed");
        require(!Boolean.TRUE.equals(bridge.call("GET_DEBUG_STATE", false, launch).get("terminated")), "Restarted app terminated");
    }

    private void filters(String investigation) throws Exception {
        bridge.call("SET_BREAKPOINT", false, PKG + ".StepFilterApplication", line("StepFilterApplication", "values.add(10);"), investigation);
        String launch = start("StepFilterApplication", investigation);
        int index = waitSuspended(launch, Duration.ofSeconds(20));
        Map<String, Object> response = bridge.call("SET_STEP_FILTERS", false, launch, true, "java.*,javax.*,sun.*");
        require(Boolean.TRUE.equals(response.get("enabled")) && str(response.get("filters")).contains("java.*"), "Step filters not applied: " + response);
        require("error".equals(bridge.call("SET_STEP_FILTERS", true, launch, true, "bad-prefix!").get("status")), "Invalid filter accepted");
        bridge.call("CONTROL_DEBUG", false, launch, index, "STEP_INTO", investigation);
        index = waitSuspended(launch, Duration.ofSeconds(20));
        List<Object> frames = arr(bridge.call("GET_STACK", false, launch, index).get("frames"));
        require(!frames.isEmpty() && str(obj(frames.get(0)).get("className")).startsWith(PKG), "Stepped into framework code: " + frames);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int number(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") private static List<Object> arr(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }

    private static final class Bridge {
        private final String token;
        private final List<Map<String, Object>> traces = new ArrayList<>();
        private Bridge() throws IOException {
            token = Files.readString(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "bridge.token"), StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) throw new IOException("Empty Eclipse bridge authentication token");
        }
        private Map<String, Object> call(String command, boolean tolerateError, Object... args) throws IOException {
            StringBuilder request = new StringBuilder("AUTH ").append(token).append(' ').append(command);
            for (Object value : args) request.append(' ').append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(str(value).getBytes(StandardCharsets.UTF_8)));
            String json;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 8765), 5000);
                socket.setSoTimeout(30000);
                try (OutputStream out = socket.getOutputStream(); BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    out.write((request + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    json = in.readLine();
                }
            }
            if (json == null) throw new IOException("Bridge returned no response for " + command);
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map<?, ?>)) throw new IOException("Unexpected bridge response: " + json);
            Map<String, Object> result = obj(parsed);
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("command", command);
            trace.put("arguments", Arrays.stream(args).map(PhaseOneBridgeVerifier::str).toList());
            trace.put("response", result);
            traces.add(trace);
            if (!tolerateError && "error".equals(result.get("status"))) throw new IOException(command + " failed: " + json);
            return result;
        }
    }

    /** Small JSON codec for the bridge protocol, avoiding third-party runtime dependencies. */
    static final class Json {
        private final String s;
        private int p;
        private Json(String s) { this.s = s; }
        static Object parse(String s) { Json j = new Json(s); Object v = j.value(); j.ws(); if (j.p != s.length()) throw new IllegalArgumentException("Unexpected JSON suffix"); return v; }
        private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
        private char ch() { if (p >= s.length()) throw new IllegalArgumentException("Unexpected JSON end"); return s.charAt(p); }
        private boolean take(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
        private void expect(char c) { if (!take(c)) throw new IllegalArgumentException("Expected '" + c + "' at " + p); }
        private Object value() {
            ws(); char c = ch();
            if (c == '"') return string();
            if (c == '{') { p++; Map<String,Object> result = new LinkedHashMap<>(); if (take('}')) return result;
                do { String key = string(); expect(':'); result.put(key, value()); } while (take(',')); expect('}'); return result; }
            if (c == '[') { p++; List<Object> result = new ArrayList<>(); if (take(']')) return result;
                do { result.add(value()); } while (take(',')); expect(']'); return result; }
            if (s.startsWith("true", p)) { p += 4; return true; }
            if (s.startsWith("false", p)) { p += 5; return false; }
            if (s.startsWith("null", p)) { p += 4; return null; }
            int start = p;
            while (p < s.length() && "-+0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            if (start == p) throw new IllegalArgumentException("Invalid JSON value at " + p);
            String n = s.substring(start,p);
            return n.contains(".") || n.contains("e") || n.contains("E") ? Double.parseDouble(n) : Long.parseLong(n);
        }
        private String string() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                char c = ch(); p++;
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char escape = ch(); p++;
                switch (escape) {
                    case '"' -> b.append('"'); case '\\' -> b.append('\\'); case '/' -> b.append('/');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f'); case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                    case 'u' -> { if (p + 4 > s.length()) throw new IllegalArgumentException("Invalid unicode escape");
                        b.append((char)Integer.parseInt(s.substring(p,p+4),16)); p += 4; }
                    default -> throw new IllegalArgumentException("Invalid JSON escape: " + escape);
                }
            }
        }
        static String stringify(Object v) {
            if (v == null) return "null";
            if (v instanceof String x) return "\"" + x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t") + "\"";
            if (v instanceof Number || v instanceof Boolean) return v.toString();
            if (v instanceof Map<?,?> m) { StringJoiner j = new StringJoiner(",","{","}"); m.forEach((k,x) -> j.add(stringify(str(k)) + ":" + stringify(x))); return j.toString(); }
            if (v instanceof Iterable<?> it) { StringJoiner j = new StringJoiner(",","[","]"); for (Object x:it) j.add(stringify(x)); return j.toString(); }
            throw new IllegalArgumentException("Unsupported JSON value " + v.getClass());
        }
    }
}
