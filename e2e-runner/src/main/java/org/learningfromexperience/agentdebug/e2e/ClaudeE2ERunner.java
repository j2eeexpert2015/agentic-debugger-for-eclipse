package org.learningfromexperience.agentdebug.e2e;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Single-command Claude->MCP->Eclipse acceptance test. Never edits Claude's user config.
 *
 * @author Ayan Dutta
 */
public final class ClaudeE2ERunner {
    private static final String TEST_LAUNCH = "AverageCalculatorApplication";
    private static final boolean INSTALLED_MODE = Boolean.getBoolean("agentdebug.test.installed");
    private static final String PREFIX = "mcp__eclipse-debugger__";
    private static final List<String> REQUIRED_TOOLS = List.of(
        "eclipse_ping", "list_run_configurations", "list_breakpoints", "set_breakpoint",
        "start_debug_session", "wait_for_debug_pause", "get_debug_threads",
        "get_stack_frames", "get_frame_variables", "control_debug_session", "show_debug_location");
    private static final Pattern TOOL_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"mcp__eclipse-debugger__([a-z_]+)\\\"");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path reports = root.resolve("e2e-runner/target/e2e-report");
        Files.createDirectories(reports);
        Path report = reports.resolve("result.txt");
        try {
            run(root, reports);
            Files.writeString(report, "PASS: Claude debugger tools executed without errors; one step-over, source-navigation request, session suspended, calculator stack, sum=10 and defect explanation verified. See trace.jsonl and runtime-evidence.txt.\n");
            System.out.println("E2E PASS. Report: " + report);
        } catch (Exception e) {
            Files.writeString(report, "FAIL: " + e.getMessage() + "\nTrace (if Claude started): trace.jsonl\n");
            System.err.println("E2E FAIL: " + e.getMessage());
            throw e;
        }
    }

    static void run(Path root, Path reports) throws Exception {
        Path jar = root.resolve("mcp-server/target/agentic-debugger-mcp-1.0.0-SNAPSHOT.jar");
        if (!Files.isRegularFile(jar)) throw new IOException("Build did not produce MCP JAR: " + jar);
        String check = bridge("PING", false);
        if (!"PONG".equals(check)) throw new IOException("Eclipse bridge response was: " + check);
        System.out.println("Eclipse bridge: PONG");
        String info = bridge("GET_E2E_INFO", true);
        if (!info.contains("\"fixtureProtocol\":\"calculator-v2\"")) {
            throw new IOException("Connected Eclipse bridge is not the current calculator-v2 plugin. "
                    + "Actual response: " + info + ". Restart the PDE Runtime Eclipse with the latest plugin source.");
        }
        if (INSTALLED_MODE) validateInstalledBinary(root, reports, info);
                String prepared = bridge("PREPARE_SMOKE_TEST", true);
        if (!prepared.contains("\"status\":\"success\""))
            throw new IOException("Cannot prepare fixture in running Eclipse. Restart the PDE Runtime Eclipse with the latest plugin; response: " + prepared);
        System.out.println("Fixture preparation: " + prepared);
        // A successful PREPARE reply does NOT prove that the newly created launch
        // is visible to the debugger. Validate through the same bridge operations
        // that Claude will use, before spending time/quota on an agent run.
        preflightFixture(reports);

        Path tempConfig = Files.createTempFile(reports, "mcp-config-", ".json");
        try {
            String config = "{\"mcpServers\":{\"eclipse-debugger\":{\"type\":\"stdio\",\"command\":\""
                    + json(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString())
                    + "\",\"args\":[\"-jar\",\"" + json(jar.toString()) + "\"]}}}";
            Files.writeString(tempConfig, config, StandardCharsets.UTF_8);
            Path promptPath = root.resolve("e2e-runner/claude-prompt.txt");
            String prompt = Files.readString(promptPath, StandardCharsets.UTF_8);
            Path serviceSource = root.resolve("examples/average-calculator-debug-sample/src/com/learningfromexperience/debugsample/service/AverageCalculator.java");
            Path appSource = root.resolve("examples/average-calculator-debug-sample/src/com/learningfromexperience/debugsample/AverageCalculatorApplication.java");
            prompt += "\n\nSource evidence from the checked-in fixture (supplied by the test harness; DO NOT use file or evaluation tools):\n"
                    + "AverageCalculator.java:\n```java\n" + Files.readString(serviceSource, StandardCharsets.UTF_8)
                    + "\n```\nAverageCalculatorApplication.java:\n```java\n" + Files.readString(appSource, StandardCharsets.UTF_8)
                    + "\n```\n";
            List<String> cmd = new ArrayList<>(List.of(
                executable("claude"), "-p", prompt, "--mcp-config", tempConfig.toString(),
                "--strict-mcp-config", "--output-format", "stream-json", "--verbose",
                "--max-turns", "22", "--allowedTools", PREFIX + "*"));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
            // Do not inherit a nested Claude Code invocation marker.
            pb.environment().remove("CLAUDECODE");
            Process proc = pb.start();
            Path trace = reports.resolve("trace.jsonl");
            Thread output = new Thread(() -> {
                try (InputStream in = proc.getInputStream(); OutputStream out = Files.newOutputStream(trace)) {
                    in.transferTo(out);
                } catch (IOException ignored) { }
            }, "claude-e2e-output");
            output.setDaemon(true); output.start();
            if (!proc.waitFor(240, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("Claude timed out after 240 seconds; see trace.jsonl");
            }
            output.join(5000);
            if (proc.exitValue() != 0) throw new IOException("Claude exited " + proc.exitValue()
                    + "; " + traceFailure(trace) + "; see trace.jsonl");
            checkTrace(trace);
        } finally {
            Files.deleteIfExists(tempConfig);
        }
    }

    private static void preflightFixture(Path reports) throws IOException {
        String configurations = null;
        // Allow Eclipse's resource/build/launch listeners a short time to settle.
        for (int attempt = 0; attempt < 8; attempt++) {
            configurations = bridge("LIST_RUN_CONFIGURATIONS", true);
            if (hasLaunch(configurations, TEST_LAUNCH)) break;
            if (attempt < 7) {
                try { Thread.sleep(500); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted during launch discovery", e); }
            }
        }
        Files.writeString(reports.resolve("preflight.txt"),
                "Launch configurations: " + configurations + System.lineSeparator(), StandardCharsets.UTF_8);
        if (!hasLaunch(configurations, TEST_LAUNCH)) {
            throw new IOException("Eclipse did not expose the prepared launch '" + TEST_LAUNCH
                    + "'. Actual LIST_RUN_CONFIGURATIONS: " + configurations
                    + ". Confirm the PDE Runtime Eclipse is running the newly built plugin,"
                    + " not an older installed copy, and restart the runtime if necessary.");
        }
        String breakpointList = bridge("LIST_BREAKPOINTS", true);
        Files.writeString(reports.resolve("preflight.txt"),
                "Breakpoints: " + breakpointList + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        if (!breakpointList.contains("\"status\":\"success\""))
            throw new IOException("Eclipse cannot list breakpoints before Claude starts: "
                    + breakpointList + ". See Runtime Eclipse Error Log / console.");
        if (INSTALLED_MODE) {
            // Exercise the installed Eclipse breakpoint implementation *before* using Claude quota.
            String encodedType = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "com.learningfromexperience.debugsample.service.AverageCalculator".getBytes(StandardCharsets.UTF_8));
            String created = bridge("SET_BREAKPOINT " + encodedType + " 10", true);
            Files.writeString(reports.resolve("preflight.txt"),
                    "Installed breakpoint create: " + created + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            if (!created.contains("\"status\":\"success\""))
                throw new IOException("INSTALLED BREAKPOINT FAILED: " + created
                    + ". Check the installed bundle and Eclipse Error Log, not the PDE test.");
            // Only remove a breakpoint that this preflight actually created and owns.
            if (created.contains("\"created\":true") && created.contains("\"owner\":\"agent\"")) {
                String removal = bridge("REMOVE_BREAKPOINT " + encodedType + " 10", true);
                if (!removal.contains("\"status\":\"success\""))
                    throw new IOException("Cannot clean up preflight agent breakpoint: " + removal);
            }
        }
        System.out.println("Preflight passed: launch discovered, breakpoints readable"
                + (INSTALLED_MODE ? ", installed breakpoint creation works" : ""));
    }

    /** Verify the installed Eclipse's ACTUAL loaded breakpoint bytecode against the
     * newest plugin JAR produced by this Maven reactor; JAR-level binary comparison
     * is unsuitable because archive timestamps and metadata vary.
     */
    private static void validateInstalledBinary(Path root, Path reports, String info) throws IOException {
        // Tycho's eclipse-plugin packaging does not promise a JAR named
        // <bundle-id>_<version>.jar directly inside eclipse-plugin/target.
        // Compare compiled class bytes with the distributed p2 ZIP and with
        // the classes that are actually loaded in the running Eclipse instance.
        String operationsClass =
                "org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.class";
        String breakpointClass =
                "org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.class";

        String compiledOperations = compiledClassHash(root, operationsClass);
        String compiledBreakpoint = compiledClassHash(root, breakpointClass);
        String packagedOperations = updateSiteClassHash(root, operationsClass);
        String packagedBreakpoint = updateSiteClassHash(root, breakpointClass);

        String loadedOperations = jsonField(info, "operationsSha256");
        String loadedBreakpoint = jsonField(info, "breakpointSha256");
        String loadedFrom = jsonField(info, "loadedFrom");
        String installedVersion = jsonField(info, "bundleVersion");
        if (loadedOperations == null || loadedBreakpoint == null || loadedFrom == null) {
            throw new IOException("Installed Eclipse plugin lacks build identity: " + info
                    + ". Reinstall the current update-site ZIP and restart Eclipse.");
        }

        String evidence = "Compiled classes: " + root.resolve("eclipse-plugin/target/classes")
                + "\nRunning Eclipse plugin: " + loadedFrom
                + "\nRunning bundle version: " + installedVersion
                + "\nOperations SHA-256 (compiled/site/loaded): "
                + compiledOperations + " / " + packagedOperations + " / " + loadedOperations
                + "\nBreakpoint SHA-256 (compiled/site/loaded): "
                + compiledBreakpoint + " / " + packagedBreakpoint + " / " + loadedBreakpoint + "\n";
        Files.writeString(reports.resolve("installed-binary.txt"), evidence, StandardCharsets.UTF_8);
        if (!loadedFrom.toLowerCase(Locale.ROOT).contains(".jar")) {
            throw new IOException("Eclipse is running PDE classes rather than the installed plugin JAR: "
                    + loadedFrom + ". Use the Eclipse instance with the update-site feature installed.");
        }
        if (!compiledOperations.equalsIgnoreCase(packagedOperations)
                || !compiledBreakpoint.equalsIgnoreCase(packagedBreakpoint)) {
            throw new IOException("UPDATE-SITE DOES NOT MATCH COMPILED SOURCE. " + evidence);
        }
        if (!compiledOperations.equalsIgnoreCase(loadedOperations)
                || !compiledBreakpoint.equalsIgnoreCase(loadedBreakpoint)) {
            throw new IOException("INSTALLED PLUGIN DOES NOT MATCH CURRENT BUILD. " + evidence
                    + "Install the newly built ZIP as a higher p2 version, restart Eclipse, and retry.");
        }
        System.out.println("Installed Eclipse binary matches compiled and packaged debugger classes: "
                + loadedFrom);
    }

    private static String compiledClassHash(Path root, String classEntry) throws IOException {
        Path classFile = root.resolve("eclipse-plugin/target/classes").resolve(classEntry);
        if (!Files.isRegularFile(classFile)) {
            throw new IOException("Compiled Eclipse class not found: " + classFile
                    + ". Build the full Maven reactor from the repository root first.");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(classFile)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Independently prove the release ZIP contains the same debugger bytecode.
     * The update-site archive contains a nested p2 plugins/<bundle>.jar entry.
     */
    private static String updateSiteClassHash(Path root, String classEntry) throws IOException {
        Path target = root.resolve("eclipse-update-site/target");
        if (!Files.isDirectory(target)) throw new IOException("Missing update-site target: " + target);
        List<Path> archives;
        try (var files = Files.list(target)) {
            archives = files.filter(p -> p.getFileName().toString().endsWith(".zip")
                    && p.getFileName().toString().contains("update-site"))
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                .toList();
        }
        if (archives.isEmpty()) throw new IOException("No update-site ZIP built in " + target);
        Path site = archives.get(0);
        try (ZipFile zip = new ZipFile(site.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (!name.contains("plugins/org.learningfromexperience.eclipse.agentdebug_")
                        || !name.endsWith(".jar")) continue;
                try (ZipInputStream nested = new ZipInputStream(zip.getInputStream(entry))) {
                    java.util.zip.ZipEntry item;
                    while ((item = nested.getNextEntry()) != null) {
                        if (item.getName().equals(classEntry)) {
                            try { return HexFormat.of().formatHex(
                                    MessageDigest.getInstance("SHA-256").digest(nested.readAllBytes())); }
                            catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
                        }
                    }
                }
            }
        }
        throw new IOException("Installable update-site ZIP " + site
                + " does not contain plugin class " + classEntry);
    }

    private static String jsonField(String object, String field) {
        String key = "\"" + field + "\"";
        int start = object.indexOf(key);
        if (start < 0) return null;
        int colon = object.indexOf(':', start + key.length());
        if (colon < 0) return null;
        int openQuote = object.indexOf('"', colon + 1);
        if (openQuote < 0) return null;
        int closeQuote = object.indexOf('"', openQuote + 1);
        return closeQuote < 0 ? null : object.substring(openQuote + 1, closeQuote);
    }

    private static String zipClassHash(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var ze = zip.getEntry(entry);
            if (ze == null) throw new IOException("Missing " + entry + " in " + jar);
            try (InputStream input = zip.getInputStream(ze)) {
                try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(input.readAllBytes())); }
                catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
            }
        }
    }

    private static boolean hasLaunch(String json, String name) {
        // Match an actual configuration name, not a substring in an error message.
        return json != null && json.contains("\"status\":\"success\"")
                && json.contains("\"name\":\"" + name + "\"");
    }

    private static String traceFailure(Path trace) {
        try {
            if (!Files.isRegularFile(trace)) return "no Claude transcript";
            String first = "no useful diagnostic in transcript";
            for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
                if (line.contains("\"type\":\"tool_result\"")
                        && (line.contains("Failed") || line.contains("\\\"status\\\":\\\"error")
                            || line.contains("closed the connection"))) {
                    return line.length() > 900 ? line.substring(0, 900) : line;
                }
                if (line.contains("\"type\":\"result\"") && line.contains("\"is_error\":true"))
                    first = line.length() > 900 ? line.substring(0, 900) : line;
            }
            return first;
        } catch (IOException e) { return "unable to read transcript: " + e.getMessage(); }
    }

    static void checkTrace(Path trace) throws IOException {
        List<String> lines = Files.readAllLines(trace, StandardCharsets.UTF_8);
        // Claude's stream-json is one JSON object per line. Count tool-use events,
        // not mentions of tool names in narrative or the initialization record.
        Map<String, String> toolById = new LinkedHashMap<>();
        Map<String, Integer> callCounts = new HashMap<>();
        List<String> errors = new ArrayList<>();
        StringBuilder evidenceText = new StringBuilder();
        for (String line : lines) {
            if (line.contains("\"type\":\"tool_use\"")) {
                Matcher names = TOOL_NAME.matcher(line);
                Matcher ids = Pattern.compile("\"id\":\"(toolu_[^\"]+)\"").matcher(line);
                if (names.find() && ids.find()) {
                    String tool = names.group(1);
                    toolById.put(ids.group(1), tool);
                    callCounts.merge(tool, 1, Integer::sum);
                }
            }
            if (line.contains("\"type\":\"tool_result\"")) {
                Matcher ids = Pattern.compile("\"tool_use_id\":\"(toolu_[^\"]+)\"").matcher(line);
                String tool = ids.find() ? toolById.getOrDefault(ids.group(1), "unknown") : "unknown";
                if (line.contains("\"is_error\":true") || line.contains("\"isError\":true")
                        || line.contains("\\\"status\\\":\\\"error\\\"")) {
                    // Evaluation is intentionally excluded from this acceptance test:
                    // it can require explicit human consent even for read-only expressions.
                    if (!"evaluate_expression".equals(tool)) {
                        errors.add(tool + ": " + extractToolText(line));
                    }
                }
                evidenceText.append(line).append('\n');
            }
        }
        if (!errors.isEmpty()) throw new IOException("MCP tool failure: " + errors.get(0));
        List<String> missing = REQUIRED_TOOLS.stream()
                .filter(t -> callCounts.getOrDefault(t, 0) == 0).toList();
        if (!missing.isEmpty()) throw new IOException("Missing MCP tool calls: " + missing
                + ". First failure: " + traceFailure(trace) + "; see trace.jsonl");
        if (callCounts.getOrDefault("control_debug_session", 0) != 1)
            throw new IOException("Expected exactly one STEP_OVER; observed "
                + callCounts.getOrDefault("control_debug_session", 0) + " control calls");
        // Optional expression evaluation is not part of the acceptance criteria.
        // It may be blocked by consent, which must not invalidate successful
        // breakpoint, launch, inspection and stepping operations.

        String observed = evidenceText.toString();
        if (!observed.contains("\\\"scheduled\\\":true"))
            throw new IOException("Source navigation was not scheduled for the suspended session; see trace.jsonl");
        if (!observed.contains("\\\"name\\\":\\\"sum\\\"")
                || !observed.contains("\\\"name\\\":\\\"number\\\""))
            throw new IOException("Claude did not observe sum and number in Eclipse variables; see trace.jsonl");

        // A tool-use transcript alone cannot prove runtime values. Validate the
        // final session directly through the Eclipse bridge as an independent oracle.
        String encodedLaunch = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(TEST_LAUNCH.getBytes(StandardCharsets.UTF_8));
        String state = bridge("GET_DEBUG_STATE " + encodedLaunch, true);
        if (!state.contains("\"suspended\":true"))
            throw new IOException("Test session not suspended at completion: " + state);
        String threads = bridge("GET_THREADS " + encodedLaunch, true);
        Matcher suspendedThread = Pattern.compile("\\{\"index\":(\\d+),\"name\":\"[^\"]+\",\"suspended\":true")
                .matcher(threads);
        if (!suspendedThread.find()) throw new IOException("No suspended thread: " + threads);
        String index = suspendedThread.group(1);
        String stack = bridge("GET_STACK " + encodedLaunch + " " + index, true);
        Matcher frame = Pattern.compile("\\{\"index\":(\\d+),\"className\":\"com\\.learningfromexperience\\.debugsample\\.service\\.AverageCalculator\"")
                .matcher(stack);
        if (!frame.find()) throw new IOException("AverageCalculator service frame missing: " + stack);
        if (!stack.contains("com.learningfromexperience.debugsample.AverageCalculatorApplication"))
            throw new IOException("Application entry-point frame missing from stack: " + stack);
        String variables = bridge("GET_VARIABLES " + encodedLaunch + " " + index + " " + frame.group(1), true);
        if (!Pattern.compile("\"name\":\"sum\"[^}]*\"value\":\"10\"").matcher(variables).find())
            throw new IOException("Expected sum=10 after STEP_OVER; observed: " + variables);

        // The prompt includes the exact checked-in source. Require the final
        // narrative to identify the defect; this is separate from debugger proof.
        StringBuilder narrative = new StringBuilder();
        for (String line : lines) {
            if (line.contains("\"type\":\"assistant\"")) narrative.append(line).append('\n');
        }
        String discussion = narrative.toString().toLowerCase(Locale.ROOT);
        if (!discussion.contains("integer division") || !discussion.contains("20.333"))
            throw new IOException("Defect identification missing: expected integer division and 20.333..."
                + " in Claude's explanation; see trace.jsonl");

        Path evidence = trace.getParent().resolve("runtime-evidence.txt");
        Files.writeString(evidence, "Final Eclipse state: " + state + "\nThreads: " + threads
                + "\nStack: " + stack + "\nVariables: " + variables + "\n"
                + "Defect: integer division from supplied checked-in fixture source.\n");
    }

    private static String extractToolText(String line) {
        // Retain the actual tool response, but avoid megabytes of stream metadata.
        Matcher content = Pattern.compile("\"text\":\"((?:\\\\.|[^\"\\\\])*)\"").matcher(line);
        if (content.find()) return content.group(1).substring(0, Math.min(600, content.group(1).length()));
        return line.substring(0, Math.min(600, line.length()));
    }

    static String bridge(String cmd, boolean authenticated) throws IOException {
        String request = cmd;
        if (authenticated) {
            Path token = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "bridge.token");
            if (!Files.isRegularFile(token)) throw new IOException("Missing bridge token. Start Eclipse with the plugin.");
            request = "AUTH " + Files.readString(token).trim() + " " + cmd;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8765), 2500);
            socket.setSoTimeout(30000);
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.println(request);
                String response = reader.readLine();
                if (response == null) throw new IOException("Eclipse bridge closed without response");
                return response;
            }
        }
    }
    static String executable(String name) { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name; }
    static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
