package org.learningfromexperience.agentdebug.testing.trial;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.learningfromexperience.agentdebug.testing.grading.ClaudeTranscriptEvaluator;

/** Opt-in Claude CLI trial: captures the transcript, an independent before/after Eclipse
 * inventory, and grades the run with ClaudeTranscriptEvaluator (PASS/PARTIAL/FAIL/BLOCKED/UNSAFE).
 * Run only in the disposable Eclipse workspace with a trusted local MCP registration.
 * Never bypasses Claude permission prompts and never runs production requests. */
public final class ClaudeSymptomTrial {
    private static final Map<String,String> PROMPTS = new LinkedHashMap<>();
    static {
        PROMPTS.put("boot-quote", "The local disposable Orders Spring Boot quote endpoint returns 120.00 when I expect 108.00 for quantity=2, unitPrice=60, discountPercent=10. Use the Eclipse debugger MCP to reproduce this localhost fixture issue and identify the first incorrect runtime value. After starting your own NORMAL investigation and launching OrdersApplication, call start_fixture_http_request with the owned launchName, investigationId and scenario=discount. This starts a non-blocking POST; inspect the resulting breakpoint, resume the request thread, then call get_fixture_http_request with the returned requestId to read the actual HTTP response. Never use Bash, curl or PowerShell for HTTP reproduction. You are explicitly authorized to POST ONLY to the identified disposable localhost Orders fixture. Do not modify code, files, launch configurations, or unrelated sessions. Finish your own investigation and include exact observed breakpoint frames, inputs, HTTP response and cleanup evidence. If an Eclipse MCP tool is unavailable, say so instead of guessing.");
        PROMPTS.put("junit", "A discount calculation test is failing in my imported disposable Spring Boot fixture. Investigate the relevant single JUnit method using Eclipse debugger MCP, without running the entire suite or modifying source. Show observed application frames and values, explain the mismatch, and finish only your own investigation. Do not guess or claim test evidence you did not observe.");
        PROMPTS.put("validation", "The disposable local Orders API returns HTTP 400 when quantity is invalid. Find the originating application-code path using Eclipse debugger MCP and show the actual status, body, and exception/handler evidence. After starting your own NORMAL investigation and launching OrdersApplication, use start_fixture_http_request with investigationId, owned launchName, scenario=invalid-quantity to POST asynchronously; inspect the exception/request thread, resume it, then get_fixture_http_request to read the response. Never use Bash, curl or PowerShell for HTTP reproduction. You are explicitly authorized to POST ONLY to the disposable localhost Orders fixture. Do not change source or other sessions. Finish your own investigation. Report exactly what is observed.");
    }
    private static void require(boolean ok,String message) {if(!ok) throw new IllegalArgumentException(message);}
    private static String json(String s) {return "\"" + s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r") + "\"";}
    private static String jsonArray(List<String> values) {
        return "[" + String.join(",", values.stream().map(ClaudeSymptomTrial::json).toList()) + "]";
    }
    /** Read the same untracked test configuration used by DevE2E. No global Claude or Eclipse edits. */
    private static int configuredPort(Path root) throws Exception {
        Properties properties = new Properties();
        Path settings = root.resolve("dev-e2e/e2e.properties");
        require(Files.isRegularFile(settings), "Missing E2E configuration: " + settings);
        try (var input = Files.newInputStream(settings)) { properties.load(input); }
        int port = Integer.parseInt(Objects.requireNonNull(properties.getProperty("bridgePort"),
                "Missing bridgePort in " + settings).trim());
        require(port > 1024 && port <= 65535 && port != 8765,
                "Claude acceptance must use a dedicated non-default E2E bridge port");
        String inherited = System.getenv("AGENTDEBUG_PORT");
        require(inherited == null || inherited.equals(Integer.toString(port)),
                "E2E bridge port mismatch: e2e.properties=" + port + ", runner environment=" + inherited);
        return port;
    }

    /**
     * The acceptance test must launch the freshly built repository MCP runtime,
     * NOT the user-scoped staged runtime that Eclipse/Claude may not have refreshed.
     * This never changes ~/.claude.json or ~/.eclipse-agentdebug.
     * Fail before consuming tokens if Maven has not built the new tool registrations.
     */
    private static Path mcpJar(Path root, boolean httpScenario) throws Exception {
        Path jar = root.resolve("mcp-server/target/agentic-debugger-mcp-1.0.0-SNAPSHOT.jar")
                .toAbsolutePath().normalize();
        require(Files.isRegularFile(jar), "Missing built MCP JAR: " + jar
                + ". Close Eclipse, then run java dev-e2e/DevE2E.java all");
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
            String base = "org/learningfromexperience/eclipse/agentdebug/mcp/";
            require(archive.getEntry(base + "DebugMcpServer.class") != null,
                    "The selected JAR does not contain DebugMcpServer: " + jar);
            require(archive.getEntry("io/modelcontextprotocol/server/McpServer.class") != null,
                    "The selected JAR is not the runnable shaded MCP distribution: " + jar);
            if (httpScenario) {
                var fixture = archive.getJarEntry(base + "FixtureHttpTools.class");
                var main = archive.getJarEntry(base + "DebugMcpServer.class");
                require(fixture != null, "Built MCP JAR lacks FixtureHttpTools. Rebuild before Claude acceptance: " + jar);
                byte[] fixtureCode;
                byte[] mainCode;
                try (var input = archive.getInputStream(fixture)) { fixtureCode = input.readAllBytes(); }
                try (var input = archive.getInputStream(main)) { mainCode = input.readAllBytes(); }
                String registrations = new String(fixtureCode, StandardCharsets.ISO_8859_1);
                String mainRegistration = new String(mainCode, StandardCharsets.ISO_8859_1);
                for (String tool : List.of("start_fixture_http_request", "get_fixture_http_request", "cancel_fixture_http_request")) {
                    require(registrations.contains(tool),
                            "Built MCP JAR is missing tool registration " + tool + ". Rebuild before Claude acceptance: " + jar);
                }
                require(mainRegistration.contains("FixtureHttpTools"),
                        "Built MCP server does not register FixtureHttpTools. Rebuild before Claude acceptance: " + jar);
            }
        }
        return jar;
    }

    /**
     * Prints the full path of every debugging session report of this run (OPEN-30). The MCP server writes a report
     * when its investigation ends, possibly just after Claude exits, so this waits up to 10 seconds for the first one.
     */
    private static void printReports(Path folder, Path transcript, boolean skillAllowed) throws Exception {
        List<Path> found = List.of();
        for (int i = 0; i < 20 && found.isEmpty(); i++) {
            if (Files.isDirectory(folder)) {
                try (var files = Files.list(folder)) {
                    found = files.filter(f -> f.getFileName().toString().endsWith(".html")).sorted().toList();
                }
            }
            if (found.isEmpty()) Thread.sleep(500);
        }
        if (found.isEmpty()) System.out.println("Debugging session report: none written in " + folder.toAbsolutePath());
        for (Path report : found) {
            System.out.println("Debugging session report: " + report.toAbsolutePath());
            System.out.println(checkReport(report, transcript, skillAllowed));
        }
    }

    /**
     * Checks a debugging session report written during a trial (OPEN-30): it is one self-contained file with the
     * request, the agent's conclusion and the cleanup, and (with the skill) the agent's final answer gives its path.
     * Since OPEN-38 step 4 the report is the Agent Activity page: the request is its "Request:" block, the conclusion
     * its "From the agent" section, the cleanup a step; nothing may be loaded from outside the file.
     * Printed as one REPORT CHECK line next to the VERDICT; it does not change the verdict, which grades the debugging.
     */
    static String checkReport(Path report, Path transcript, boolean skillAllowed) throws Exception {
        String html = Files.readString(report, StandardCharsets.ISO_8859_1);
        List<String> missing = new java.util.ArrayList<>();
        if (!html.contains("<span class=\"lbl\">Request: </span>")) missing.add("request");
        if (!html.contains("<h2>From the agent</h2>")) missing.add("conclusion (no summary at finish)");
        if (!html.contains("Finished investigation and cleaned up") && !html.contains("Cleaned up automatically")) missing.add("cleanup step");
        if (html.contains("<link") || html.contains(" src=\"")) missing.add("self-contained file (something is loaded from outside)");
        if (skillAllowed) {
            String answer = "";
            for (String line : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
                if (line.contains("\"type\":\"result\"")) answer = line;
            }
            if (!answer.contains(report.getFileName().toString())) missing.add("report path in the agent's final answer");
        }
        return missing.isEmpty() ? "REPORT CHECK PASS" : "REPORT CHECK FAIL  missing: " + String.join(", ", missing);
    }

    /**
     * @param reportFolder when not null, the MCP server writes a debugging session report (OPEN-30) into this
     *                     folder, next to the transcript ({@code --report})
     * @param prompt       the exact prompt sent to Claude; shown first in the report as "Exact prompt"
     */
    private static String testMcpConfig(Path jar, int port, Path workspace, Path root, boolean approvedPost,
                                        Path reportFolder, String prompt) {
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
                .toAbsolutePath().toString();
        return "{\n  \"mcpServers\": {\n    \"eclipse-debugger\": {\n"
                + "      \"type\": \"stdio\",\n"
                + "      \"command\": " + json(javaExe) + ",\n"
                + "      \"args\": [\"-jar\", " + json(jar.toString()) + "],\n"
                + "      \"env\": {\"AGENTDEBUG_PORT\": " + json(Integer.toString(port))
                + ", \"AGENTDEBUG_E2E_HTTP\": \"1\""
                + ", \"AGENTDEBUG_E2E_WORKSPACE\": " + json(workspace.toString())
                + ", \"AGENTDEBUG_E2E_ROOT\": " + json(root.toString())
                + ", \"AGENTDEBUG_E2E_POST_APPROVED\": " + json(approvedPost ? "1" : "0")
                // Reports are on by default in the MCP server; trials without --report switch them off, so nothing is
                // written into the fixture projects and earlier trial results stay comparable.
                + (reportFolder == null ? ", \"AGENTDEBUG_REPORT\": \"off\"" : ", \"AGENTDEBUG_REPORT\": \"html\", \"AGENTDEBUG_REPORT_EVENTS\": \"1\", \"AGENTDEBUG_REPORT_DIR\": "
                        + json(reportFolder.toString()) + ", \"AGENTDEBUG_REPORT_PROMPT\": " + json(prompt))
                + "}\n"
                + "    }\n  }\n}\n";
    }

    /**
     * Independent Eclipse state read directly from the bridge (not through Claude):
     * debug sessions, line breakpoints and exception breakpoints with their owner.
     * Returns a JSON object; on failure an object with "error".
     */
    /** One authenticated bridge command; string arguments are URL-safe Base64, numbers plain. */
    private static String bridge(int port, String command, Object... args) throws Exception {
        Path tokenFile = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug",
                port == 8765 ? "bridge.token" : "bridge-" + port + ".token");
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        StringBuilder line = new StringBuilder("AUTH ").append(token).append(' ').append(command);
        for (Object arg : args) line.append(' ').append(arg instanceof Number ? arg.toString()
                : Base64.getUrlEncoder().withoutPadding().encodeToString(arg.toString().getBytes(StandardCharsets.UTF_8)));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(30_000);
            var writer = new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(line.append('\n').toString());
            writer.flush();
            String reply = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            require(reply != null && reply.contains("\"status\":\"success\""), command + " failed: " + reply);
            return reply;
        }
    }

    private static String inventory(int port) {
        try {
            Path tokenFile = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug",
                    port == 8765 ? "bridge.token" : "bridge-" + port + ".token");
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            StringBuilder out = new StringBuilder("{");
            String[][] parts = {{"sessions","LIST_DEBUG_SESSIONS"},{"breakpoints","LIST_BREAKPOINTS"},
                    {"exceptionBreakpoints","LIST_EXCEPTION_BREAKPOINTS"}};
            for (int i = 0; i < parts.length; i++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                    socket.setSoTimeout(30_000);
                    var writer = new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    writer.write("AUTH " + token + " " + parts[i][1] + "\n");
                    writer.flush();
                    String reply = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    require(reply != null && reply.contains("\"status\":\"success\""),
                            parts[i][1] + " failed: " + reply);
                    out.append(i == 0 ? "" : ",").append(json(parts[i][0])).append(":").append(reply);
                }
            }
            return out.append("}").toString();
        } catch (Exception e) {
            return "{\"error\":" + json(e.toString()) + "}";
        }
    }

    /** Main classes of the disposable fixture JVMs; only these are ever stopped by the harness. */
    private static final List<String> FIXTURE_PREFIXES = List.of(
            "org.learningfromexperience.bootfixture.", "org.learningfromexperience.debugfixtures.");

    /**
     * Test-only isolation between trials, NOT a product cleanup path (those are described in the
     * plugin's DebugInvestigationPolicy and ADR-008). It guarantees each trial starts clean even if a
     * product path failed; the grader still reports the failing run.
     * Test-only isolation between trials: a fixture JVM left running by an earlier run would be
     * reused by the next run and spoil it. Finds fixture JVMs with jps, stops them, and waits until
     * Eclipse no longer shows a live "Agentic Debugger - " session. Returns what was stopped.
     * Throws (before any Claude usage) if Eclipse still shows one, e.g. because a dialog is open.
     */
    private static List<String> clearLeftoverFixtures(int port) throws Exception {
        // Returns everything removed: fixture JVMs ("pid mainClass") and agent-owned breakpoints.
        List<String> stopped = new ArrayList<>();
        Path jps = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "jps.exe" : "jps");
        if (Files.isRegularFile(jps)) {
            Process p = new ProcessBuilder(jps.toString(), "-l").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(20, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length < 2 || FIXTURE_PREFIXES.stream().noneMatch(parts[1]::startsWith)) continue;
                try {
                    long pid = Long.parseLong(parts[0]);
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                    stopped.add(pid + " " + parts[1]);
                } catch (NumberFormatException ignored) {
                    // not a jps process line
                }
            }
        }
        // Agent-owned breakpoints left by an interrupted run would make the next run stop unexpectedly.
        // Only markers owned by the agent are removed; user breakpoints are never touched.
        try {
            var snapshot = ClaudeTranscriptEvaluator.Json.parse(inventory(port));
            var root = ClaudeTranscriptEvaluator.Json.obj(snapshot);
            for (var bp : ClaudeTranscriptEvaluator.Json.objectsWith(root.get("breakpoints"), "typeName", "lineNumber", "owner")) {
                if (!"agent".equals(bp.get("owner"))) continue;
                String type = ClaudeTranscriptEvaluator.Json.str(bp.get("typeName"));
                int line = ClaudeTranscriptEvaluator.Json.num(bp.get("lineNumber"));
                bridge(port, "REMOVE_BREAKPOINT", type, line);
                stopped.add("agent breakpoint " + type + ":" + line);
            }
            for (var bp : ClaudeTranscriptEvaluator.Json.objectsWith(root.get("exceptionBreakpoints"), "exceptionName", "owner")) {
                if (!"agent".equals(bp.get("owner"))) continue;
                String name = ClaudeTranscriptEvaluator.Json.str(bp.get("exceptionName"));
                bridge(port, "REMOVE_EXCEPTION_BREAKPOINT", name);
                stopped.add("agent exception breakpoint " + name);
            }
        } catch (Exception e) {
            System.out.println("Could not remove leftover agent breakpoints: " + e.getMessage());
        }
        // Give Eclipse time to notice the exits, then require a clean session list.
        String live = "";
        for (int i = 0; i < 40; i++) {
            String sessions = inventory(port);
            live = liveAgentSessions(sessions);
            if (live.isEmpty()) return stopped;
            Thread.sleep(250);
        }
        throw new IllegalStateException("DIRTY_START: Eclipse still shows a running test session (" + live
                + ") although no fixture JVM is running. A dialog may be open in the test Eclipse; "
                + "click it, or close the test Eclipse, then rerun. No Claude usage was spent on this run.");
    }

    /** Names of live (not terminated, not disconnected) "Agentic Debugger - " sessions in an inventory. */
    private static String liveAgentSessions(String inventoryJson) {
        var sessions = ClaudeTranscriptEvaluator.Json.objectsWith(
                ClaudeTranscriptEvaluator.Json.parse(inventoryJson), "launchName", "terminated");
        List<String> live = new ArrayList<>();
        for (var session : sessions) {
            String name = ClaudeTranscriptEvaluator.Json.str(session.get("launchName"));
            if (name.startsWith("Agentic Debugger - ") && !Boolean.TRUE.equals(session.get("terminated"))
                    && !Boolean.TRUE.equals(session.get("disconnected"))) live.add(name);
        }
        return String.join(", ", live);
    }

    /**
     * Command line: {@code REPO SCENARIO OUTPUT_FOLDER --approve-claude [--approve-fixture-post] [--batch ID] [--no-skill]
     * [--report] [--model NAME]}. The JUnit class {@code ClaudeAgentEndToEndTests} calls {@link #run} directly.
     */
    public static void main(String[] args) throws Exception {
        require(args.length>=3,"Usage: ClaudeSymptomTrial REPO SCENARIO OUTPUT_FOLDER --approve-claude [--approve-fixture-post] [--batch ID] [--no-skill] [--report] [--model NAME]");
        List<String> list=Arrays.asList(args);
        int modelAt=list.indexOf("--model");
        int batchAt=list.indexOf("--batch");
        require(list.contains("--approve-claude"),"Claude CLI execution requires --approve-claude (may consume tokens)");
        run(Path.of(args[0]), args[1], Path.of(args[2]), list.contains("--approve-fixture-post"), list.contains("--no-skill"),
                list.contains("--report"), modelAt >= 0 && modelAt + 1 < args.length ? args[modelAt + 1] : null,
                batchAt >= 0 && batchAt + 1 < args.length ? args[batchAt + 1] : "single");
    }

    /**
     * Runs one Claude trial and grades it. Returns the verdict (its "verdict" is PASS, PARTIAL, FAIL, BLOCKED or
     * UNSAFE), or null when Claude produced no transcript; throws when the Claude CLI run itself did not complete.
     *
     * @param post          HTTP requests to the test application approved (needed by every scenario but junit)
     * @param noSkill       Claude may not use any skill (comparison run)
     * @param sessionReport the MCP server also writes the debugging session report next to the transcript
     * @param model         the Claude model (for example haiku); null for the Claude CLI's default
     * @param batch         the batch ID that groups runs in the summary
     */
    public static Map<String,Object> run(Path repository, String scenario, Path reportFolderRoot, boolean post, boolean noSkill,
                                         boolean sessionReport, String model, String batch) throws Exception {
        Path root=repository.toRealPath();
        Path reports=reportFolderRoot.toAbsolutePath();
        require(PROMPTS.containsKey(scenario),"Unknown Claude scenario: " + scenario);
        require(model == null || model.matches("[a-z0-9][a-z0-9.-]{0,60}"), "Invalid model name: " + model);
        require(post || scenario.equals("junit"),"This trial sends an HTTP request to the test application: approval needed (-Dfixture.post.approved=true)");
        require(Files.isRegularFile(root.resolve("pom.xml")),"Not a repository checkout");
        int port = configuredPort(root);
        Properties e2eProperties = new Properties();
        try (var input = Files.newInputStream(root.resolve("dev-e2e/e2e.properties"))) {
            e2eProperties.load(input);
        }
        String rawWorkspace = Objects.requireNonNull(e2eProperties.getProperty("workspace"),
                "Missing workspace in E2E configuration");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (rawWorkspace.contains("${LOCALAPPDATA}")) {
            require(localAppData != null && !localAppData.isBlank(), "Missing LOCALAPPDATA");
            rawWorkspace = rawWorkspace.replace("${LOCALAPPDATA}", localAppData);
        }
        Path workspace = Path.of(rawWorkspace).toRealPath();
        require(Files.isRegularFile(workspace.resolve("spring-boot-debugging/.agentdebug-fixture")),
                "Configured workspace does not contain the disposable Spring Boot fixture");
        Path jar = mcpJar(root, !scenario.equals("junit"));
        // Do not spend Claude tokens when the dedicated test bridge is unreachable.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Dedicated Eclipse bridge not listening on 127.0.0.1:" + port
                    + ". Start the test Eclipse: java dev-e2e/DevE2E.java start " + e.getMessage(), e);
        }
        // Fail closed rather than replace a previous trial or silently overwrite evidence.
        Files.createDirectories(reports);
        String stem=scenario + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path transcript=reports.resolve(stem+".claude.jsonl");
        Path metadata=reports.resolve(stem+".manifest.json");
        Path prompt=reports.resolve(stem+".prompt.txt");
        Path stderr=reports.resolve(stem+".stderr.log");
        Path mcpConfig=reports.resolve(stem+".mcp.json");
        Path inventoryFile=reports.resolve(stem+".inventory.json");
        Path reportFolder=sessionReport ? reports.resolve(stem+".report") : null;
        require(!Files.exists(transcript) && !Files.exists(metadata),"Existing evidence collision");
        Files.writeString(prompt,PROMPTS.get(scenario)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        // Test-scoped MCP config binds this process to the configured E2E port.
        // The user-level Claude MCP registration and normal IDE bridge remain untouched.
        Files.writeString(mcpConfig, testMcpConfig(jar, port, workspace, root, post, reportFolder, PROMPTS.get(scenario)), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        // Approve only this installed debugger's tools, not Bash/Write/Edit/other MCP servers.
        // No dangerously-skip-permissions and no broad user configuration changes.
        List<String> command=new ArrayList<>(List.of("claude","-p",PROMPTS.get(scenario),
                "--mcp-config",mcpConfig.toString(),"--strict-mcp-config",
                "--allowedTools","mcp__eclipse-debugger__*",
                "--output-format","stream-json","--verbose","--max-turns","25"));
        if (noSkill) command.addAll(List.of("--disallowedTools","Skill"));
        if (model != null) command.addAll(List.of("--model", model));
        // Start from a clean Eclipse: no fixture JVM left over from an earlier trial.
        List<String> leftoversStopped = clearLeftoverFixtures(port);
        if (!leftoversStopped.isEmpty())
            System.out.println("Removed leftovers of an earlier run: " + leftoversStopped);
        // Snapshot Eclipse before Claude starts, so cleanup and user resources can be checked afterwards.
        String inventoryBefore = inventory(port);
        Process p=null;
        int exit=-1; boolean timedOut=false; String failure="";
        try {
            // Empty stdin: otherwise the CLI waits 3 s for piped input on every run.
            java.io.File noInput = new java.io.File(
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
            p=new ProcessBuilder(command).directory(root.toFile()).redirectError(stderr.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(noInput))
                    .redirectOutput(transcript.toFile()).start();
            if(!p.waitFor(480,TimeUnit.SECONDS)) {
                timedOut=true;p.destroy();
                if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}
            }
            exit=p.exitValue();
        } catch(Exception e) {failure=e.toString();}
        String inventoryAfter = inventory(port);
        Files.writeString(inventoryFile, "{\"before\":" + inventoryBefore + ",\"after\":" + inventoryAfter + "}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        String cliResult = Files.isRegularFile(transcript)
                ? Files.readString(transcript, StandardCharsets.UTF_8) : "";
        // Tool denial scope matters: a denied Grep/Bash operation must not be mistaken
        // for a denied Eclipse debugger MCP tool. Preserve every denial for review.
        String denialSection = "";
        var array = java.util.regex.Pattern.compile("\"permission_denials\"\\s*:\\s*\\[(.*?)\\]",
                java.util.regex.Pattern.DOTALL).matcher(cliResult);
        if (array.find()) denialSection = array.group(1);
        List<String> deniedTools = new ArrayList<>();
        var names = java.util.regex.Pattern.compile("\"tool_name\"\\s*:\\s*\"([^\"]+)\"").matcher(denialSection);
        while (names.find()) deniedTools.add(names.group(1));
        boolean permissionDenied = !deniedTools.isEmpty();
        boolean eclipseMcpDenied = deniedTools.stream().anyMatch(t -> t.startsWith("mcp__eclipse-debugger__"));
        String outcome=timedOut?"TIMEOUT":exit!=0?"CLI_FAILED":eclipseMcpDenied?"BLOCKED_PERMISSIONS":"REVIEW_REQUIRED";
        // EXIT 0 is NOT a debugger PASS. Human/code-based grading must review tool evidence.
        String manifest="{\n"+
                "  \"scenario\": "+json(scenario)+",\n"+
                "  \"generatedAt\": "+json(Instant.now().toString())+",\n"+
                "  \"status\": "+json(outcome)+",\n"+
                "  \"exitCode\": "+exit+",\n"+
                "  \"failure\": "+json(failure)+",\n"+
                "  \"transcript\": "+json(transcript.toString())+",\n"+
                "  \"transcriptFormat\": \"stream-json\",\n"+
                "  \"stderr\": "+json(stderr.toString())+",\n"+
                "  \"permissionMode\": \"allowedTools: mcp__eclipse-debugger__* (no bypass)\",\n"+
                "  \"batch\": "+json(batch)+",\n"+
                "  \"leftoversRemovedBeforeRun\": "+jsonArray(leftoversStopped)+",\n"+
                "  \"skillMode\": "+json(noSkill ? "disabled" : "allowed")+",\n"+
                "  \"bridgePort\": "+port+",\n"+
                "  \"mcpConfig\": "+json(mcpConfig.toString())+",\n"+
                "  \"permissionDenialsObserved\": "+permissionDenied+",\n"+
                "  \"deniedTools\": "+jsonArray(deniedTools)+",\n"+
                "  \"eclipseMcpPermissionDenied\": "+eclipseMcpDenied+",\n"+
                "  \"inventory\": "+json(inventoryFile.toString())+",\n"+
                "  \"verdictFile\": "+json(reports.resolve(stem+".verdict.json").toString())+",\n"+
                "  \"humanEvidenceReviewNeeded\": true,\n"+
                "  \"toolInvocationVerified\": false,\n"+
                "  \"runtimeDiagnosisVerified\": false,\n"+
                "  \"cleanupVerified\": false\n}";
        Files.writeString(metadata,manifest,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println(scenario+" => "+outcome+"; transcript="+transcript+"; manifest="+metadata);
        // Grade every run that produced a transcript, including CLI failures and timeouts: a failed
        // run must count as FAIL in the batch summary, never silently disappear from it.
        Map<String,Object> verdict=null;
        if (Files.isRegularFile(transcript) && Files.size(transcript) > 0) {
            // Automatic grade from the debugger's actual results plus the independent inventory.
            var expectations = ClaudeTranscriptEvaluator.Json.obj(ClaudeTranscriptEvaluator.Json.parse(
                    Files.readString(root.resolve("evaluation/claude-grading/expectations.json"), StandardCharsets.UTF_8)));
            verdict = ClaudeTranscriptEvaluator.gradeFile(transcript, scenario, expectations);
            verdict.put("batch", batch);
            verdict.put("skillMode", noSkill ? "disabled" : "allowed");
            Path verdictFile = reports.resolve(stem + ".verdict.json");
            Files.writeString(verdictFile, ClaudeTranscriptEvaluator.Json.stringify(verdict) + "\n", StandardCharsets.UTF_8);
            System.out.println("VERDICT " + ClaudeTranscriptEvaluator.summary(verdict) + "; verdict=" + verdictFile);
            if (reportFolder != null) printReports(reportFolder, transcript, !noSkill);
            if (!outcome.equals("REVIEW_REQUIRED")) System.out.println("Claude CLI outcome " + outcome + ": " + verdict.get("reason")
                    + " (" + ClaudeTranscriptEvaluator.Json.str(((java.util.List<?>) verdict.get("checks")).get(0)) + ")");
        }
        if(!outcome.equals("REVIEW_REQUIRED")) throw new IllegalStateException("Claude trial not captured successfully: "+outcome+" "+failure);
        return verdict;
    }
}
