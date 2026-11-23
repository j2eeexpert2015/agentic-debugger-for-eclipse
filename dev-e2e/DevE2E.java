import java.io.*;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Java 21 source launcher. One repeatable entry point; never terminates an Eclipse process. */
public class DevE2E {
    private final Path bundle, repo, eclipse, workspace;
    private final int port;
    private final String mode;
    private final boolean approval;
    private boolean claudeApproval;
    /** Claude trials only: runs per scenario (--repeat N) and skill mode (--no-skill). */
    private int claudeRepeat = 1;
    private boolean claudeNoSkill;
    private static final String IU = "org.learningfromexperience.eclipse.agentdebug.feature.feature.group";

    private DevE2E(Path source, String mode, boolean approval) throws Exception {
        this.bundle = source.toRealPath();
        this.mode = mode;
        this.approval = approval;
        Properties p = new Properties();
        try (var in = Files.newInputStream(bundle.resolve("dev-e2e/e2e.properties"))) { p.load(in); }
        this.repo = bundle;
        String configuredRepository = expand(p.getProperty("repository", bundle.toString()));
        if (!absolute(configuredRepository).equals(bundle)) throw new IllegalArgumentException("Run from configured repository root: " + configuredRepository);
        this.eclipse = absolute(expand(required(p, "eclipse")));
        this.workspace = absolute(expand(required(p, "workspace")));
        this.port = Integer.parseInt(required(p, "bridgePort"));
        if (port < 1024 || port > 65535 || port == 8765) throw new IllegalArgumentException("Configure a dedicated nondefault bridge port");
        if (workspace.equals(repo) || workspace.equals(eclipse) || workspace.equals(Path.of(System.getProperty("user.home")).toAbsolutePath()))
            throw new IllegalArgumentException("Unsafe test workspace: " + workspace);
        if (!Files.isRegularFile(repo.resolve("pom.xml")) || !Files.isRegularFile(repo.resolve("e2e-runner/pom.xml")))
            throw new IllegalArgumentException("Not a valid project checkout: " + repo);
    }
    private static String required(Properties p, String key) {
        String v = p.getProperty(key, "").strip();
        if (v.isBlank() || v.contains("CHANGE_ME")) throw new IllegalArgumentException("Set " + key + " in dev-e2e.properties");
        return v;
    }
    private static String expand(String s) {
        String local = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home"));
        return s.replace("${user.home}", System.getProperty("user.home"))
            .replace("${LOCALAPPDATA}", local);
    }
    private static Path absolute(String s) { return Path.of(s).toAbsolutePath().normalize(); }
    private static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    private static Path executable(Path dir, String name) {
        return dir.resolve(windows() ? name + ".exe" : name);
    }
    private static String javaBin(String name) {
        return executable(Path.of(System.getProperty("java.home"), "bin"), name).toString();
    }
    private void runProcess(List<String> command, Path cwd, Map<String, String> env, int timeoutSeconds) throws Exception {
        System.out.println("RUN: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO();
        pb.environment().putAll(env);
        Process proc = pb.start();
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroy();
            throw new IllegalStateException("Timed out; inspect child process: " + command.get(0));
        }
        if (proc.exitValue() != 0) throw new IllegalStateException("Process failed (exit " + proc.exitValue() + "): " + command);
    }
    private String capture(List<String> command) throws Exception {
        // Drain output while the p2 Director runs. Waiting before draining a pipe
        // can deadlock once the child's output buffer fills.
        Path log = repo.resolve("e2e-runner/target/java-e2e-p2-director.log");
        Files.createDirectories(log.getParent());
        System.out.println("RUN (captured to " + log + "): " + String.join(" ", command));
        Process p = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(600, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            throw new IllegalStateException("p2 command timed out after 600 seconds. Inspect "
                    + log + "; last output: " + tailLog(log));
        }
        String result = Files.readString(log, StandardCharsets.UTF_8);
        if (p.exitValue() != 0)
            throw new IllegalStateException("p2 command failed (exit " + p.exitValue()
                    + "). Inspect " + log + "; last output: " + tailLog(log));
        return result;
    }
    private static String tailLog(Path file) {
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            return s.substring(Math.max(0, s.length() - 2500));
        } catch (IOException e) { return "Cannot read diagnostic log: " + e.getMessage(); }
    }
    private static void check(boolean v, String error) { if (!v) throw new IllegalStateException(error); }
    private Path eclipseExe() {return executable(eclipse, windows() ? "eclipse" : "eclipse");}
    private Path directorExe() {return windows() ? eclipse.resolve("eclipsec.exe") : eclipse.resolve("eclipse");}

    private void verifySources() throws Exception {
        for (String filename : List.of("PhaseOneBridgeVerifier.java", "PhaseTwoBridgeVerifier.java",
                "JavaFixtureQuoteClient.java", "SpringBootDirectVerifier.java", "SpringBootQuoteVerifier.java")) {
            check(Files.isRegularFile(repo.resolve("e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/" + filename)),
                    "Missing committed Java verifier " + filename + ". Run the repository integration command first.");
        }
        check(Files.isRegularFile(repo.resolve("examples/spring-boot-debugging/src/main/java/org/learningfromexperience/bootfixture/orders/FixtureIdentityController.java")),
                "Missing committed fixture identity controller");
    }
    /** One-time, idempotent migration: retain all Phase 2 assertions, remove the obsolete
     * PowerShell HTTP child process. Git keeps the previous implementation recoverable. */
    private void integratePhase2JavaHttp() throws Exception {
        Path path=repo.resolve("e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseTwoBridgeVerifier.java");
        String source=Files.readString(path, StandardCharsets.UTF_8);
        if (source.contains("// JAVA_HTTP_ONLY_CLIENT_V1")) {
            check(!source.contains("powershell.exe"), "Phase 2 has Java marker but still calls PowerShell; refusing partial migration");
            return;
        }
        String head="    private Process startHttpFixtureClient(String launch, Path receipt, boolean approved, boolean invalid,";
        int a=source.indexOf(head);
        int b=source.indexOf("    private String launch(String owner)",a);
        check(a >= 0 && b > a,"Unexpected PhaseTwoBridgeVerifier structure; refusing automatic modification: " + path);
        String replacement="""
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

                """;
        source=source.substring(0,a)+replacement+source.substring(b);
        source=source.replace("options.getOrDefault(\"http-client\", \"powershell\")", "options.getOrDefault(\"http-client\", \"java\")");
        source=source.replace("List.of(\"powershell\", \"java\").contains(httpClient)", "\"java\".equals(httpClient)");
        source=source.replace("--http-client java|powershell", "--http-client java");
        Path temporary=Files.createTempFile(path.getParent(),"phase2-java-http-",".tmp");
        try {
            Files.writeString(temporary,source,StandardCharsets.UTF_8);
            Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("Integrated Java HTTP client into PhaseTwoBridgeVerifier.java (once).");
    }
    private void ensureIgnoreLocalConfig() throws Exception {
        Path gitignore=repo.resolve(".gitignore");
        String old=Files.exists(gitignore) ? Files.readString(gitignore) : "";
        if (!old.lines().anyMatch(line -> line.strip().equals("/dev-e2e/e2e.properties"))) {
            Files.writeString(gitignore, (old.endsWith("\n") || old.isEmpty() ? old : old+"\n")
                    + "\n# Local Java E2E installation paths\n/dev-e2e/e2e.properties\n",StandardCharsets.UTF_8);
            System.out.println("Ignored local dev-e2e/e2e.properties in .gitignore");
        }
    }
    private void cleanupObsoletePowerShellHttpClient() throws Exception {
        Path verifier=repo.resolve("e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseTwoBridgeVerifier.java");
        String code=Files.readString(verifier);
        check(code.contains("JAVA_HTTP_ONLY_CLIENT_V1") && !code.contains("powershell.exe"),
                "Refusing cleanup: Java HTTP conversion is not complete");
        Path legacyBatch=repo.resolve("scripts/windows/verify-phase2-eclipse.bat");
        check(Files.isRegularFile(legacyBatch) && Files.readString(legacyBatch).contains("--http-client java"),
                "Refusing cleanup: legacy batch compatibility path still uses old client");
        boolean verified = false;
        for (String reportName : List.of("phase2-java-live.json", "phase2-all-java-live.json")) {
            Path report = repo.resolve("evaluation/reports/" + reportName);
            if (!Files.isRegularFile(report)) continue;
            String evidence = Files.readString(report);
            long passes = evidence.split(java.util.regex.Pattern.quote("\"status\":\"PASS\""), -1).length - 1;
            long cleanups = evidence.split(java.util.regex.Pattern.quote("\"cleanup\":true"), -1).length - 1;
            if (evidence.contains("\"validation\":") && evidence.contains("\"ownership\":")
                    && passes >= 2 && cleanups >= 2) {
                verified = true;
                System.out.println("Confirmed full Java validation + ownership PASS evidence: " + report);
                break;
            }
        }
        check(verified, "Refusing cleanup: first run BOTH Phase 2 Java scenarios and preserve their PASS report");
        // Historical test reports remain. Replace only obsolete documentation links.
        Path toolAudit = repo.resolve("docs/spring-boot-tool-audit.md");
        if (Files.isRegularFile(toolAudit)) {
            String oldAudit = Files.readString(toolAudit);
            String oldLink = "[fixture client](../e2e-runner/boot-tests/Invoke-FixtureQuote.ps1)";
            String newLink = "historical PowerShell fixture client (now replaced by [JavaFixtureQuoteClient.java](../e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/JavaFixtureQuoteClient.java))";
            if (oldAudit.contains(oldLink)) {
                Files.writeString(toolAudit, oldAudit.replace(oldLink, newLink), StandardCharsets.UTF_8);
                System.out.println("Updated historical fixture-client reference in " + toolAudit);
            }
        }
        // Do not delete related historical evidence, skill scripts or the Windows wrappers.
        Path obsolete=repo.resolve("e2e-runner/boot-tests/Invoke-FixtureQuote.ps1");
        if (Files.deleteIfExists(obsolete))
            System.out.println("Removed obsolete PowerShell HTTP fixture client: " + obsolete);
        else System.out.println("Obsolete PowerShell HTTP fixture client already absent.");
        System.out.println("Preserved PowerShell developer and skill harness scripts: not yet replaced by Java.");
    }
    private void ensureNoRunningEclipseForDeploy() {
        String expected = eclipseExe().toAbsolutePath().normalize().toString();
        boolean running = ProcessHandle.allProcesses().anyMatch(p -> {
            String c = p.info().command().orElse("");
            try { return !c.isBlank() && Path.of(c).toAbsolutePath().normalize().toString().equalsIgnoreCase(expected); }
            catch (Exception e) { return false; }
        });
        check(!running, "Eclipse at " + eclipse + " is already running. Close it manually before 'all'; this tool never kills Eclipse. For an already running configured test instance use 'test'.");
    }
    private void buildAndDeploy(boolean build) throws Exception {
        check(Files.isRegularFile(eclipseExe()), "Eclipse executable missing: " + eclipseExe());
        check(Files.isRegularFile(directorExe()), "Eclipse p2 director executable missing: " + directorExe());
        ensureNoRunningEclipseForDeploy();
        if (build) {
            System.out.println("== Build Maven/Tycho artifacts ==");
            String mvn = windows() ? "mvn.cmd" : "mvn";
            runProcess(List.of(mvn,"clean","package"), repo, Map.of(), 900);
        } else {
            System.out.println("== Reuse existing Maven build; no rebuild ==");
        }
        Path site = repo.resolve("eclipse-update-site/target/repository");
        check(Files.isDirectory(site), "Missing built update site " + site);
        Path featureDir = site.resolve("features");
        List<Path> matches;
        try (var files = Files.list(featureDir)) {
            matches=files.filter(x->x.getFileName().toString().startsWith("org.learningfromexperience.eclipse.agentdebug.feature_") && x.toString().endsWith(".jar")).toList();
        }
        check(matches.size()==1, "Expected exactly one built feature JAR in " + featureDir);
        String name=matches.get(0).getFileName().toString();
        String version=name.substring(name.indexOf('_')+1,name.length()-4);
        List<String> directorBase = List.of(directorExe().toString(),"-nosplash","-application","org.eclipse.equinox.p2.director","-destination",eclipse.toString());
        String roots=capture(concat(directorBase,List.of("-listInstalledRoots")));
        ArrayList<String> args=new ArrayList<>(directorBase);
        if (Arrays.stream(roots.split("\\R")).anyMatch(x->x.strip().startsWith(IU+"/"))) {args.add("-uninstallIU");args.add(IU);}
        args.add("-repository");args.add(site.toUri().toASCIIString());
        args.add("-installIU");args.add(IU+"/"+version);
        System.out.println("== Install Eclipse feature " + version + " ==");
        runProcess(args, repo, Map.of(), 300);
    }
    private static List<String> concat(List<String> a,List<String>b) {ArrayList<String> c=new ArrayList<>(a);c.addAll(b);return c;}
    private static String tokenFilename(int port) {return "bridge-"+port+".token";}
    private String bridgeResponse(String command) throws Exception {
        Path tokenPath=Path.of(System.getProperty("user.home"),".eclipse-agentdebug",tokenFilename(port));
        check(Files.isRegularFile(tokenPath),"Missing bridge token; Eclipse startup may have failed: "+tokenPath);
        String token=Files.readString(tokenPath).strip();
        try(Socket s=new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1",port),2000);s.setSoTimeout(4000);
            var out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8));
            out.write("AUTH "+token+" "+command+"\n");out.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8)).readLine();
        }
    }
    private static String jsonString(String json, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing bridge identity field: " + field);
        return matcher.group(1).replace("\\\\", "\\").replace("\\\"", "\"");
    }
    private static Path canonical(Path p) throws IOException {
        return p.toRealPath();
    }
    private String validatedBridge() throws Exception {
        String info = bridgeResponse("GET_E2E_INFO");
        check(info != null && info.contains("\"status\":\"success\""),
                "Bridge did not respond with success: " + info);
        Path actualWorkspace = canonical(Path.of(jsonString(info, "workspace")));
        Path expectedWorkspace = canonical(workspace);
        check(actualWorkspace.equals(expectedWorkspace),
                "Bridge belongs to DIFFERENT workspace: " + actualWorkspace + "; expected: " + expectedWorkspace);
        String installUri = jsonString(info, "installDirectory");
        Path actualEclipse = canonical(Path.of(URI.create(installUri)));
        Path expectedEclipse = canonical(eclipse);
        check(actualEclipse.equals(expectedEclipse),
                "Bridge belongs to DIFFERENT Eclipse installation: " + actualEclipse + "; expected: " + expectedEclipse);
        check(jsonString(info, "loadedFrom").contains("org.learningfromexperience.eclipse.agentdebug_"),
                "Bridge is not running the installed debugger bundle");
        return info;
    }
    private boolean portListening(){try (Socket s=new Socket()) {s.connect(new InetSocketAddress("127.0.0.1",port),250);return true;}catch(Exception e){return false;}}
    private void startAndWait() throws Exception {
        check(Files.isRegularFile(eclipseExe()),"Eclipse executable missing: "+eclipseExe());
        if (portListening()) {
            // Never start a second instance against a live bridge; verify exact workspace with Java test preflight.
            System.out.println("Existing bridge at " + port + "; validating before reuse.");
            String info=validatedBridge();
            check(info.contains("agentdebug"),"Unexpected bridge identity. Refusing reuse.");
            return;
        }
        // Do not create a competing Eclipse for an existing running installation.
        ensureNoRunningEclipseForDeploy();
        Files.createDirectories(workspace);
        // AGENTDEBUG_E2E=1 enables the test-only bridge fault-injection hook (BRIDGE_TEST_SLEEP)
        // in this dedicated Eclipse only; a normal Eclipse never sets it.
        Map<String,String> env=new LinkedHashMap<>(Map.of("AGENTDEBUG_PORT",Integer.toString(port),
            "AGENTDEBUG_FIXTURE_SOURCE",repo.resolve("examples/phase-one-debugging")+File.pathSeparator+repo.resolve("examples/spring-boot-debugging"),
            "AGENTDEBUG_E2E","1"));
        // Comparison runs: AGENTDEBUG_BRIDGE_MODE=legacy in the runner's environment reaches the dedicated Eclipse.
        String bridgeMode=System.getenv("AGENTDEBUG_BRIDGE_MODE");
        if (bridgeMode != null && !bridgeMode.isBlank()) env.put("AGENTDEBUG_BRIDGE_MODE", bridgeMode);
        ProcessBuilder pb=new ProcessBuilder(eclipseExe().toString(),"-clean","-data",workspace.toString());
        pb.directory(eclipse.toFile());pb.environment().putAll(env);
        // Eclipse's console output must go to a file. Left as an unread pipe, it fills up and any
        // System.out.println inside Eclipse (including the debugger plugin) blocks indefinitely.
        Path consoleLog=repo.resolve("e2e-runner/target/eclipse-console.log");
        Files.createDirectories(consoleLog.getParent());
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(consoleLog.toFile()));
        pb.start();
        System.out.println("Started Eclipse automatically in dedicated workspace: "+workspace);
        System.out.println("Eclipse console output: "+consoleLog);
        long until=System.nanoTime()+Duration.ofMinutes(5).toNanos();
        while(System.nanoTime()<until) {
            try {
                if(portListening()) {validatedBridge();System.out.println("Eclipse bridge ready.");return;}
            } catch (Exception e) { /* still starting */ }
            Thread.sleep(1500);
        }
        throw new IllegalStateException("Eclipse did not start bridge within 5 minutes. Inspect "+workspace.resolve(".metadata/.log"));
    }
    private void test(Map<String, List<String>> selection) throws Exception {
        String verifier = Files.readString(repo.resolve("e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseTwoBridgeVerifier.java"));
        if (selection.containsKey("phase2") || selection.containsKey("boot-quote")) {
            check(verifier.contains("JAVA_HTTP_ONLY_CLIENT_V1"),
                    "Phase 2 Java client not integrated. Run: java dev-e2e/DevE2E.java integrate");
        }
        if (selection.containsKey("phase2") || selection.containsKey("boot-quote")) {
            if (!approval) throw new IllegalArgumentException("HTTP tests require --approve-fixture-post");
        }
        verifySources();
        Path classes = repo.resolve("e2e-runner/target/phase2-classes");
        Files.createDirectories(classes);
        Path sources = repo.resolve("e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e");
        System.out.println("== Compile Java E2E verifiers ==");
        List<String> compile = new ArrayList<>(List.of(javaBin("javac"), "--release", "21", "-encoding", "UTF-8",
                "-d", classes.toString()));
        for (String file : List.of("PhaseOneBridgeVerifier.java", "PhaseTwoBridgeVerifier.java",
                "JavaFixtureQuoteClient.java", "SpringBootDirectVerifier.java", "SpringBootQuoteVerifier.java"))
            compile.add(sources.resolve(file).toString());
        for (String optional : List.of("PendingAcceptanceVerifier.java", "BootGapVerifier.java", "BridgeFaultVerifier.java",
                "StopEventVerifier.java")) {
            Path sourceFile = sources.resolve(optional);
            boolean required = (optional.startsWith("Pending") && selection.containsKey("acceptance"))
                    || (optional.startsWith("BootGap") && selection.containsKey("boot-gaps"))
                    || (optional.startsWith("BridgeFault") && selection.containsKey("bridge"))
                    || (optional.startsWith("StopEvent") && selection.containsKey("stops"));
            if (required) check(Files.isRegularFile(sourceFile),
                    "Missing " + optional + ". Update the repository to the current source before running this group.");
            if (Files.isRegularFile(sourceFile)) compile.add(sourceFile.toString());
        }
        runProcess(compile, repo, Map.of(), 120);
        if(selection.containsKey("claude")) {
            if(!claudeApproval) throw new IllegalArgumentException("Claude token usage requires --approve-claude");
            Path claudeSource=repo.resolve("dev-e2e/ClaudeSymptomTrial.java");
            Path evaluatorSource=repo.resolve("dev-e2e/ClaudeTranscriptEvaluator.java");
            check(Files.isRegularFile(claudeSource),"Missing Claude trial capture Java source");
            check(Files.isRegularFile(evaluatorSource),"Missing Claude transcript evaluator Java source");
            runProcess(List.of(javaBin("javac"),"--release","21","-encoding","UTF-8",
                    "-d",classes.toString(),claudeSource.toString(),evaluatorSource.toString()),repo,Map.of(),60);
        }
        startAndWait();
        Map<String,String> env = Map.of("AGENTDEBUG_PORT", Integer.toString(port));
        List<String> failures = new ArrayList<>();
        // One batch ID for all Claude runs of this command, so the summary covers exactly this batch.
        String batch = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
                + (claudeNoSkill ? "-noskill" : "-skill");
        Path classesDir = classes;
        for (var entry : selection.entrySet()) for (String scenario : entry.getValue())
        for (int run = 1; run <= (entry.getKey().equals("claude") ? claudeRepeat : 1); run++) {
            if(entry.getKey().equals("claude") && scenario.equals("all"))
                throw new IllegalArgumentException("Use --groups claude for three trials, or --tests claude:boot-quote,claude:junit,claude:validation");
            String group=entry.getKey();
            String reportKey=(group.equals("boot") ? "boot-direct" : group)
                    + ("all".equals(scenario) ? "" : "-" + scenario);
            Path report=repo.resolve("evaluation/reports/" + reportKey + "-java-live.json");
            List<String> args=new ArrayList<>(List.of(javaBin("java"), "-cp", classes.toString()));
            switch (group) {
                case "phase1" -> {
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.PhaseOneBridgeVerifier",
                        "--root",repo.toString(),"--project","phase-one-debugging","--scenario",scenario,
                        "--output",report.toString()));
                }
                case "phase2" -> {
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.PhaseTwoBridgeVerifier",
                        "--root",repo.toString(),"--workspace",workspace.toString(),"--port",Integer.toString(port),
                        "--scenario",scenario,"--approve-fixture-post","--http-client","java","--output",report.toString()));
                }
                case "boot" -> {
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.SpringBootDirectVerifier",
                        "--workspace",workspace.toString(),"--port",Integer.toString(port),
                        "--scenario",scenario,"--output",report.toString()));
                }
                case "boot-quote" -> {
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.SpringBootQuoteVerifier",
                        "--root",repo.toString(),"--workspace",workspace.toString(),"--port",Integer.toString(port),
                        "--approve-fixture-post","--output",report.toString()));
                }
                case "acceptance" -> {
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.PendingAcceptanceVerifier",
                        "--root",repo.toString(),"--workspace",workspace.toString(),
                        "--scenario",scenario,"--output",report.toString()));
                }
                case "claude" -> {
                    if(!approval && !scenario.equals("junit"))
                        throw new IllegalArgumentException("Claude HTTP trials require --approve-fixture-post");
                    args.addAll(List.of("ClaudeSymptomTrial",repo.toString(),scenario,
                            repo.resolve("evaluation/reports/claude-trials").toString(),"--approve-claude",
                            "--batch",batch));
                    if(approval) args.add("--approve-fixture-post");
                    if(claudeNoSkill) args.add("--no-skill");
                }
                case "bridge" -> {
                    // CORE-13 bridge stability fault injection; needs the E2E-started Eclipse (AGENTDEBUG_E2E=1).
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.BridgeFaultVerifier",
                        "--port",Integer.toString(port),"--scenario",scenario,"--output",report.toString()));
                }
                case "stops" -> {
                    // CORE-14/15: event-based wait_for_stop and process output, on phase-one fixtures.
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.StopEventVerifier",
                        "--root",repo.toString(),"--scenario",scenario,"--output",report.toString()));
                }
                case "boot-gaps" -> {
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.BootGapVerifier",
                        "--root",repo.toString(),"--workspace",workspace.toString(),
                        "--port",Integer.toString(port),"--scenario",scenario,"--output",report.toString()));
                }
                default -> throw new IllegalArgumentException("Unknown suite: " + group);
            }
            System.out.println("== Java E2E: " + group + "/" + scenario
                    + (group.equals("claude") ? " (run " + run + " of " + claudeRepeat + ", " + (claudeNoSkill ? "skill disabled" : "skill allowed") + ")" : "") + " ==");
            try {
                runProcess(args,repo,env,group.equals("claude")?530:360);
                System.out.println(group.equals("claude")?"CAPTURED and graded: see the VERDICT line above (*.verdict.json)":"PASS: "+report);
            } catch (Exception error) { failures.add(group+"/"+scenario+": "+error.getMessage()); }
        }
        if (selection.containsKey("claude")) {
            // Pass rate, tool calls, turns, time and cost across the runs of this batch.
            try {
                runProcess(List.of(javaBin("java"),"-cp",classesDir.toString(),"ClaudeTranscriptEvaluator","--summary",
                        "--batch",batch,repo.resolve("evaluation/reports/claude-trials").toString()),repo,Map.of(),60);
            } catch (Exception error) { failures.add("claude summary: "+error.getMessage()); }
        }
        if (!failures.isEmpty()) throw new IllegalStateException("E2E failures: " + String.join(" | ",failures));
        System.out.println(selection.containsKey("claude")?"Completed requested runs; Claude verdicts are in evaluation/reports/claude-trials/*.verdict.json":"PASS: all requested Java E2E suites");
    }
    private static final Map<String,Set<String>> SCENARIOS = Map.of(
        "phase1", Set.of("all","conditional","exception","junit","variables","trace","pause-restart","filters"),
        "phase2", Set.of("all","validation","ownership"),
        "boot", Set.of("all","discovery","readiness"),
        "boot-quote", Set.of("all","quote"),
        "acceptance", Set.of("all","core-step-into-out","sb01-target-selection","sb09-borrowed-session","sb09-expired-owner"),
        "boot-gaps", Set.of("all","two-mains","junit-invalid-method","two-services","startup-failure","readiness-timeout","port-conflict","owned-isolation"),
        "claude", Set.of("all","boot-quote","junit","validation"),
        "bridge", Set.of("all","slow-command","busy","parallel-load","auth"),
        "stops", Set.of("all","breakpoint","step","exception","pause","terminated","output"));
    private static LinkedHashMap<String,List<String>> select(String[] args) {
        LinkedHashMap<String,List<String>> selected=new LinkedHashMap<>();
        String groups=null, tests=null;
        for (int i=1;i<args.length;i++) {
            if (args[i].equals("--groups") && i+1<args.length) groups=args[++i];
            else if (args[i].equals("--tests") && i+1<args.length) tests=args[++i];
            else if (args[i].equals("--repeat") && i+1<args.length) i++;
            else if (!args[i].equals("--approve-fixture-post") && !args[i].equals("--approve-claude")
                    && !args[i].equals("--no-skill")) throw new IllegalArgumentException("Unexpected option: "+args[i]);
        }
        if (groups != null && tests != null) throw new IllegalArgumentException("Use --groups OR --tests");
        if (tests != null) {
            for (String entry:tests.split(",")) {
                String[] parts=entry.strip().split(":",2);
                if (parts.length!=2 || !SCENARIOS.containsKey(parts[0]) || !SCENARIOS.get(parts[0]).contains(parts[1]))
                    throw new IllegalArgumentException("Unknown test: "+entry);
                List<String> cases=selected.computeIfAbsent(parts[0], ignored->new ArrayList<>());
                if (!cases.contains(parts[1])) cases.add(parts[1]);
            }
        } else {
            String[] list=groups == null ? new String[]{"phase1","phase2","boot","boot-quote","acceptance","boot-gaps"} : groups.split(",");
            for(String raw:list) {
                String g=raw.strip();
                if (!SCENARIOS.containsKey(g)) throw new IllegalArgumentException("Unknown group: "+g);
                selected.put(g,g.equals("claude")?List.of("boot-quote","junit","validation"):List.of("all"));
            }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("No scenarios selected");
        return selected;
    }
    private void describe() {
        System.out.printf("Mode: %s%nRepository: %s%nEclipse: %s%nWorkspace: %s%nBridge port: %d%n",
                mode,repo,eclipse,workspace,port);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Set.of("help","--help","-h").contains(args[0])) {
            System.out.println("From repository root: java dev-e2e/DevE2E.java [config|list|integrate|cleanup|build|all|deploy|test] [--groups phase1,phase2,boot,boot-quote,acceptance,boot-gaps|bridge|stops] [--tests acceptance:core-step-into-out,boot-gaps:two-mains,bridge:busy,stops:step] [--approve-fixture-post]");
            System.out.println("Claude trials (use Claude usage): test --groups claude|--tests claude:boot-quote --approve-claude [--approve-fixture-post] [--repeat N] [--no-skill]");
            System.out.println("'all' builds, deploys (requires Eclipse closed), starts Eclipse, runs the selected suites.");
            System.out.println("'deploy' reuses Maven output, deploys, starts Eclipse, runs selected suites.");
            System.out.println("'test' reuses dedicated Eclipse or starts it without redeployment; test selectors are optional.");
            return;
        }
        if (!Set.of("config","integrate","cleanup","build","all","deploy","test","list").contains(args[0]))
            throw new IllegalArgumentException("Unknown mode: "+args[0]);
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        boolean approved=Arrays.asList(args).contains("--approve-fixture-post");
        DevE2E x=new DevE2E(root,args[0],approved);
        x.claudeApproval=Arrays.asList(args).contains("--approve-claude");
        x.claudeNoSkill=Arrays.asList(args).contains("--no-skill");
        int repeatAt=Arrays.asList(args).indexOf("--repeat");
        if (repeatAt >= 0) {
            x.claudeRepeat=Integer.parseInt(args[repeatAt+1]);
            if (x.claudeRepeat < 1 || x.claudeRepeat > 20) throw new IllegalArgumentException("--repeat must be 1-20");
        }
        x.describe();
        if (args[0].equals("config")) return;
        if (args[0].equals("list")) {
            for (String group : List.of("phase1","phase2","boot","boot-quote","acceptance","boot-gaps","bridge","stops","claude")) {
                System.out.println(group + ": " + String.join(", ", new TreeSet<>(SCENARIOS.get(group))));
            }
            return;
        }
        x.verifySources();
        if (args[0].equals("integrate") || args[0].equals("cleanup") || args[0].equals("all") || args[0].equals("deploy")) {
            x.integratePhase2JavaHttp();
            x.ensureIgnoreLocalConfig();
        }
        if (args[0].equals("integrate")) return;
        if (args[0].equals("cleanup")) { x.cleanupObsoletePowerShellHttpClient(); return; }
        if (args[0].equals("build")) {
            x.verifySources();
            x.runProcess(List.of(windows()?"mvn.cmd":"mvn","clean","package"),x.repo,Map.of(),900);
            return;
        }
        LinkedHashMap<String,List<String>> selected=select(args);
        if (args[0].equals("all")) x.buildAndDeploy(true);
        if (args[0].equals("deploy")) x.buildAndDeploy(false);
        x.test(selected);
    }
}
