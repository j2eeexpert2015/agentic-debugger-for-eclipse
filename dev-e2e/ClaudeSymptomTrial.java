import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in Claude CLI transcript capture, NOT an automatic correctness grader.
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
                + ". Close Eclipse, then run java dev-e2e/DevE2E.java all --approve-fixture-post");
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

    private static String testMcpConfig(Path jar, int port, Path workspace, Path root, boolean approvedPost) {
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
                + "}\n"
                + "    }\n  }\n}\n";
    }

    public static void main(String[] args) throws Exception {
        require(args.length>=3,"Usage: ClaudeSymptomTrial REPO SCENARIO OUTPUT_FOLDER --approve-claude [--approve-fixture-post]");
        Path root=Path.of(args[0]).toRealPath();
        String scenario=args[1];
        Path reports=Path.of(args[2]).toAbsolutePath();
        require(PROMPTS.containsKey(scenario),"Unknown Claude scenario: " + scenario);
        boolean consent=Arrays.asList(args).contains("--approve-claude");
        boolean post=Arrays.asList(args).contains("--approve-fixture-post");
        require(consent,"Claude CLI execution requires --approve-claude (may consume tokens)");
        require(post || scenario.equals("junit"),"Fixture HTTP trial requires --approve-fixture-post");
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
                    + ". Start the E2E Eclipse instance via DevE2E.java. " + e.getMessage(), e);
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
        require(!Files.exists(transcript) && !Files.exists(metadata),"Existing evidence collision");
        Files.writeString(prompt,PROMPTS.get(scenario)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        // Test-scoped MCP config binds this process to the configured E2E port.
        // The user-level Claude MCP registration and normal IDE bridge remain untouched.
        Files.writeString(mcpConfig, testMcpConfig(jar, port, workspace, root, post), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        // Approve only this installed debugger's tools, not Bash/Write/Edit/other MCP servers.
        // No dangerously-skip-permissions and no broad user configuration changes.
        List<String> command=List.of("claude","-p",PROMPTS.get(scenario),
                "--mcp-config",mcpConfig.toString(),"--strict-mcp-config",
                "--allowedTools","mcp__eclipse-debugger__*",
                "--output-format","stream-json","--verbose","--max-turns","25");
        Process p=null;
        int exit=-1; boolean timedOut=false; String failure="";
        try {
            p=new ProcessBuilder(command).directory(root.toFile()).redirectError(stderr.toFile())
                    .redirectOutput(transcript.toFile()).start();
            if(!p.waitFor(480,TimeUnit.SECONDS)) {
                timedOut=true;p.destroy();
                if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}
            }
            exit=p.exitValue();
        } catch(Exception e) {failure=e.toString();}
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
                "  \"bridgePort\": "+port+",\n"+
                "  \"mcpConfig\": "+json(mcpConfig.toString())+",\n"+
                "  \"permissionDenialsObserved\": "+permissionDenied+",\n"+
                "  \"deniedTools\": "+jsonArray(deniedTools)+",\n"+
                "  \"eclipseMcpPermissionDenied\": "+eclipseMcpDenied+",\n"+
                "  \"humanEvidenceReviewNeeded\": true,\n"+
                "  \"toolInvocationVerified\": false,\n"+
                "  \"runtimeDiagnosisVerified\": false,\n"+
                "  \"cleanupVerified\": false\n}";
        Files.writeString(metadata,manifest,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println(scenario+" => "+outcome+"; transcript="+transcript+"; manifest="+metadata);
        if(!outcome.equals("REVIEW_REQUIRED")) throw new IllegalStateException("Claude trial not captured successfully: "+outcome+" "+failure);
    }
}
