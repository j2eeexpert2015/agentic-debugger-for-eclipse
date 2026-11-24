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
    /** Claude trials only: the MCP server also writes a debugging session report (--report, OPEN-30). */
    private boolean claudeReport;
    private static final String IU = "org.learningfromexperience.eclipse.agentdebug.feature.feature.group";

    /**
     * A problem with this machine's setup, found before anything runs. Its message says what is wrong and what to do;
     * main prints it without a stack trace.
     */
    static final class SetupProblem extends RuntimeException {
        SetupProblem(String message) { super(message); }
    }

    private static final String PROPERTIES = "dev-e2e/e2e.properties";

    private DevE2E(Path source, String mode, boolean approval) throws Exception {
        this.mode = mode;
        this.approval = approval;
        List<String> problems = new ArrayList<>();
        if (Runtime.version().feature() < 21)
            problems.add("Java " + Runtime.version().feature() + " runs this tool; Java 21 or newer is needed (java -version shows which java is on PATH).");
        Path root = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isRegularFile(root.resolve("dev-e2e/DevE2E.java")))
            throw new SetupProblem("Run this from the repository root, the folder that contains pom.xml and dev-e2e."
                + "\n  Current folder: " + root
                + "\n  Example: cd C:\\path\\to\\agentic-debugger-for-eclipse  then  java dev-e2e/DevE2E.java " + mode);
        this.bundle = root.toRealPath();
        this.repo = bundle;
        Path settings = bundle.resolve(PROPERTIES);
        if (!Files.isRegularFile(settings)) {
            // A file that is almost the right one: Windows hides ".txt" ("e2e.properties.txt"), a typo, a space.
            List<String> nearly = new ArrayList<>();
            try (var files = Files.list(bundle.resolve("dev-e2e"))) {
                files.map(f -> f.getFileName().toString())
                    .filter(n -> !n.equals("e2e.properties.example"))
                    .filter(n -> n.toLowerCase(Locale.ROOT).replace(" ", "").contains("e2e") && n.toLowerCase(Locale.ROOT).contains("propert"))
                    .forEach(nearly::add);
            } catch (IOException ignored) { }
            if (!nearly.isEmpty())
                throw new SetupProblem("Looked for " + settings + " but it is not there. In that folder there is: "
                    + String.join(", ", nearly) + "\n  The file must be named exactly e2e.properties. Windows Explorer hides"
                    + " extensions, so a file shown as \"e2e.properties\" can really be \"e2e.properties.txt\".\n  Fix: "
                    + (windows() ? "ren \"dev-e2e\\" + nearly.get(0) + "\" e2e.properties" : "mv \"dev-e2e/" + nearly.get(0) + "\" dev-e2e/e2e.properties")
                    + "\n  Check with: " + (windows() ? "dir dev-e2e" : "ls dev-e2e"));
            throw new SetupProblem("Looked for " + settings + " but it is not there (the file is not in git; every"
                + " machine needs its own copy, in the checkout you run from).\n"
                + "  If you made it before, it may be in another checkout folder, or named differently ("
                + (windows() ? "dir dev-e2e" : "ls dev-e2e") + " shows what is there).\n"
                + "  To create it:\n"
                + "  1. " + (windows() ? "copy dev-e2e\\e2e.properties.example dev-e2e\\e2e.properties"
                                     : "cp dev-e2e/e2e.properties.example dev-e2e/e2e.properties") + "\n"
                + "  2. edit it (forward slashes in paths):\n"
                + "       repository=" + bundle.toString().replace('\\', '/') + "\n"
                + "       eclipse=<the folder that contains eclipse" + (windows() ? ".exe" : "") + "; a separate Eclipse used only for tests>\n"
                + "       workspace=${LOCALAPPDATA}/AgenticDebugger/java-e2e-workspace\n"
                + "       bridgePort=21766\n"
                + "  3. run 'java dev-e2e/DevE2E.java config' to check the settings.");
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(settings)) { p.load(in); }
        List<String> missing = new ArrayList<>();
        for (String key : List.of("eclipse", "workspace", "bridgePort")) {
            String v = p.getProperty(key, "").strip();
            if (v.isBlank() || v.contains("CHANGE_ME")) missing.add(key);
        }
        if (!missing.isEmpty())
            problems.add("Set " + String.join(", ", missing) + " in " + settings + " (see dev-e2e/e2e.properties.example).");

        Path configuredRepository = absolute(expand(p.getProperty("repository", bundle.toString()).strip()));
        if (!sameFolder(configuredRepository, bundle))
            problems.add("'repository' in " + PROPERTIES + " is a different folder from where you run this:"
                + "\n     repository = " + configuredRepository + "\n     you are in = " + bundle
                + "\n   Set repository=" + bundle.toString().replace('\\', '/') + " (or run from the configured folder).");

        Path eclipseDir = missing.contains("eclipse") ? null : absolute(expand(p.getProperty("eclipse").strip()));
        if (eclipseDir != null) {
            if (!Files.isDirectory(eclipseDir))
                problems.add("'eclipse' folder does not exist: " + eclipseDir + "\n   Set it to the folder that contains eclipse" + (windows() ? ".exe" : "") + ".");
            else {
                if (!Files.isRegularFile(executable(eclipseDir, "eclipse")))
                    problems.add("No " + executable(eclipseDir, "eclipse").getFileName() + " in the 'eclipse' folder " + eclipseDir
                        + "\n   Point 'eclipse' at the installation folder itself (the one with eclipse" + (windows() ? ".exe" : "") + " and eclipse.ini).");
                if (windows() && !Files.isRegularFile(eclipseDir.resolve("eclipsec.exe")))
                    problems.add("No eclipsec.exe in " + eclipseDir + " (needed to install the plugin). Use a full Eclipse installation (EPP package).");
            }
        }
        Path workspaceDir = missing.contains("workspace") ? null : absolute(expand(p.getProperty("workspace").strip()));
        if (workspaceDir != null && (workspaceDir.equals(bundle) || (eclipseDir != null && workspaceDir.equals(eclipseDir))
                || workspaceDir.equals(Path.of(System.getProperty("user.home")).toAbsolutePath())))
            problems.add("'workspace' must be a separate, throwaway folder (not the repository, the Eclipse folder or your home): " + workspaceDir);

        int bridge = -1;
        if (!missing.contains("bridgePort")) {
            try {
                bridge = Integer.parseInt(p.getProperty("bridgePort").strip());
                if (bridge < 1024 || bridge > 65535 || bridge == 8765)
                    problems.add("'bridgePort' must be 1024-65535 and not 8765 (8765 is the normal Eclipse's port); for example bridgePort=21766.");
            } catch (NumberFormatException notNumber) {
                problems.add("'bridgePort' is not a number: " + p.getProperty("bridgePort") + " (for example bridgePort=21766).");
            }
        }
        if (!Files.isRegularFile(bundle.resolve("e2e-runner/pom.xml")))
            problems.add("This checkout has no e2e-runner/pom.xml: " + bundle + " (incomplete clone? run git status).");

        if (!problems.isEmpty()) {
            StringBuilder out = new StringBuilder("Fix " + (problems.size() == 1 ? "this" : "these " + problems.size() + " things")
                + " in " + settings + " or on this machine, then run again:");
            for (int i = 0; i < problems.size(); i++) out.append("\n ").append(i + 1).append(". ").append(problems.get(i));
            throw new SetupProblem(out.toString());
        }
        this.eclipse = eclipseDir;
        this.workspace = workspaceDir;
        this.port = bridge;
    }
    private static boolean sameFolder(Path a, Path b) {
        try {
            if (Files.exists(a)) return a.toRealPath().equals(b.toRealPath());
        } catch (IOException ignored) { }
        return windows() ? a.toString().equalsIgnoreCase(b.toString()) : a.equals(b);
    }
    /**
     * Checks before a mode starts its work, so it does not fail halfway: Maven for building, a finished build for
     * deploying (and a warning when sources are newer than it), the claude command for Claude trials.
     */
    private void preflight(String mode, Map<String, List<String>> selected) {
        List<String> problems = new ArrayList<>();
        if ((mode.equals("build") || mode.equals("all")) && !commandWorks(windows() ? "mvn.cmd" : "mvn", "-v"))
            problems.add("Maven (mvn) is not on PATH. Install Maven 3.9+ and open a new terminal; 'mvn -v' must work.");
        if (mode.equals("deploy")) {
            Path features = repo.resolve("eclipse-update-site/target/repository/features");
            boolean built = false;
            try (var files = Files.list(features)) {
                built = files.anyMatch(f -> f.getFileName().toString().startsWith("org.learningfromexperience.eclipse.agentdebug.feature_"));
            } catch (IOException none) { }
            if (!built)
                problems.add("There is no build to deploy (" + features + " is missing or empty).\n   Run 'mvn clean package' first, or use 'all' to build and deploy in one go.");
            else {
                long builtAt = newest(repo.resolve("eclipse-update-site/target/repository"));
                long changedAt = Math.max(newest(repo.resolve("eclipse-plugin/src")), Math.max(newest(repo.resolve("mcp-server/src")),
                        Math.max(newest(repo.resolve("report-format/src")), newest(repo.resolve("report-chaintest/src")))));
                if (changedAt > builtAt)
                    System.out.println("WARNING: source files changed after the last build (" + Instant.ofEpochMilli(changedAt)
                        + " > " + Instant.ofEpochMilli(builtAt) + "). 'deploy' installs the OLD build. Run 'mvn clean package' first if that is not intended.");
            }
        }
        if (selected != null && selected.containsKey("claude") && !commandWorks("claude", "--version"))
            problems.add("The 'claude' command (Claude Code) could not be started from this tool, so Claude trials cannot run."
                + " 'claude --version' must work in this terminal.");
        if (!problems.isEmpty()) {
            StringBuilder out = new StringBuilder("Cannot start '" + mode + "':");
            for (int i = 0; i < problems.size(); i++) out.append("\n ").append(i + 1).append(". ").append(problems.get(i));
            throw new SetupProblem(out.toString());
        }
    }
    /**
     * True when the command starts and answers, started exactly as the tool starts it later (so the check cannot
     * disagree with the real run, whatever the PATH spelling, the file extension or the install location).
     */
    private static boolean commandWorks(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroy();
                return true;                      // it started; slow to answer is not a setup problem
            }
            return process.exitValue() == 0;
        } catch (IOException notFound) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
    /** Newest modification time of a file under the folder (0 when it does not exist). */
    private static long newest(Path folder) {
        if (!Files.isDirectory(folder)) return 0;
        try (var files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return 0; }
            }).max().orElse(0);
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }
    private static String required(Properties p, String key) {
        String v = p.getProperty(key, "").strip();
        if (v.isBlank() || v.contains("CHANGE_ME")) throw new SetupProblem("Set " + key + " in " + PROPERTIES);
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
                "StopEventVerifier.java", "InvestigationLeaseVerifier.java")) {
            Path sourceFile = sources.resolve(optional);
            boolean required = (optional.startsWith("Pending") && selection.containsKey("acceptance"))
                    || (optional.startsWith("BootGap") && selection.containsKey("boot-gaps"))
                    || (optional.startsWith("BridgeFault") && selection.containsKey("bridge"))
                    || (optional.startsWith("StopEvent") && selection.containsKey("stops"))
                    || (optional.startsWith("InvestigationLease") && selection.containsKey("lease"));
            if (required) check(Files.isRegularFile(sourceFile),
                    "Missing " + optional + ". Update the repository to the current source before running this group.");
            if (Files.isRegularFile(sourceFile)) compile.add(sourceFile.toString());
        }
        runProcess(compile, repo, Map.of(), 120);
        if(selection.containsKey("claude")) {
            if(!claudeApproval) throw new IllegalArgumentException("Claude token usage requires --approve-claude");
            Path claudeSource=repo.resolve("dev-e2e/ClaudeSymptomTrial.java");
            // The grader lives in the testing module (its unit tests run in "mvn clean package").
            Path evaluatorSource=repo.resolve("testing/src/main/java/org/learningfromexperience/agentdebug/testing/grading/ClaudeTranscriptEvaluator.java");
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
                    if(claudeReport) args.add("--report");
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
                case "lease" -> {
                    // CORE-17: abandoned investigations cleaned up; MCP auto-attach and disconnect cleanup.
                    args.addAll(List.of("org.learningfromexperience.agentdebug.e2e.InvestigationLeaseVerifier",
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
                runProcess(List.of(javaBin("java"),"-cp",classesDir.toString(),"org.learningfromexperience.agentdebug.testing.grading.ClaudeTranscriptEvaluator","--summary",
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
        "stops", Set.of("all","breakpoint","step","exception","pause","terminated","output"),
        "lease", Set.of("all","idle-expiry","wait-keeps-alive","controlled-kept","mcp-disconnect","mcp-several"));
    /** Execution order of the verifier groups; also the order of a run selected by feature or suite. */
    private static final List<String> GROUP_ORDER = List.of("phase1","phase2","boot","boot-quote","acceptance","boot-gaps",
            "stops","lease","bridge","claude");
    /** The six groups of the 23-scenario regression (the default when nothing is selected). */
    private static final List<String> REGRESSION_GROUPS = List.of("phase1","phase2","boot","boot-quote","acceptance","boot-gaps");
    /**
     * Integration tests by product feature (OPEN-18). Each entry is "group:scenario", or "group:all" for a whole group.
     * The verifier groups stay as they are; a feature only selects scenarios from them. Every regression scenario
     * belongs to exactly one feature (checked by {@link #checkFeatureCatalog()}).
     */
    private static final LinkedHashMap<String,List<String>> FEATURES = new LinkedHashMap<>();
    static {
        FEATURES.put("breakpoints", List.of("phase1:conditional","phase1:exception","phase1:trace"));
        FEATURES.put("execution-control", List.of("phase1:pause-restart","phase1:filters","acceptance:core-step-into-out"));
        FEATURES.put("inspection", List.of("phase1:variables"));
        FEATURES.put("junit", List.of("phase1:junit","boot-gaps:junit-invalid-method"));
        FEATURES.put("spring-boot", List.of("boot:discovery","boot:readiness","boot-quote:quote","phase2:validation",
                "acceptance:sb01-target-selection","boot-gaps:two-mains","boot-gaps:two-services",
                "boot-gaps:startup-failure","boot-gaps:readiness-timeout","boot-gaps:port-conflict"));
        FEATURES.put("ownership", List.of("phase2:ownership","acceptance:sb09-expired-owner",
                "acceptance:sb09-borrowed-session","boot-gaps:owned-isolation"));
        FEATURES.put("stop-events", List.of("stops:all"));
        FEATURES.put("abandoned-cleanup", List.of("lease:all"));
        FEATURES.put("bridge", List.of("bridge:all"));
        FEATURES.put("claude", List.of("claude:all"));
    }
    /** Named sets of features. "regression" runs the six regression groups whole, exactly as before OPEN-18. */
    private static final LinkedHashMap<String,List<String>> SUITES = new LinkedHashMap<>();
    static {
        SUITES.put("regression", List.of("breakpoints","execution-control","inspection","junit","spring-boot","ownership"));
        SUITES.put("extended", List.of("stop-events","abandoned-cleanup","bridge"));
        SUITES.put("full", List.of("breakpoints","execution-control","inspection","junit","spring-boot","ownership",
                "stop-events","abandoned-cleanup","bridge"));
    }
    /** Every regression scenario is in exactly one feature, and every feature entry names a real scenario. */
    private static void checkFeatureCatalog() {
        Map<String,String> owner=new HashMap<>();
        for (var feature : FEATURES.entrySet()) for (String entry : feature.getValue()) {
            String[] parts=entry.split(":",2);
            check(SCENARIOS.containsKey(parts[0]) && SCENARIOS.get(parts[0]).contains(parts[1]),
                    "Feature " + feature.getKey() + " names an unknown scenario: " + entry);
            String previous=owner.put(entry, feature.getKey());
            check(previous == null, entry + " is in two features: " + previous + ", " + feature.getKey());
        }
        for (String group : REGRESSION_GROUPS) for (String scenario : SCENARIOS.get(group)) {
            if (!scenario.equals("all")) check(owner.containsKey(group + ":" + scenario),
                    "Regression scenario " + group + ":" + scenario + " belongs to no feature");
        }
    }
    /**
     * Turns features into the group/scenario selection the runner executes. A group whose scenarios are all selected
     * runs as one "all" invocation, so a suite that covers whole groups behaves exactly like selecting those groups.
     */
    private static LinkedHashMap<String,List<String>> byFeatures(List<String> features) {
        Map<String,Set<String>> wanted=new HashMap<>();
        for (String raw : features) {
            String feature=raw.strip();
            if (!FEATURES.containsKey(feature))
                throw new IllegalArgumentException("Unknown feature: " + feature + ". Features: " + String.join(", ", FEATURES.keySet()));
            for (String entry : FEATURES.get(feature)) {
                String[] parts=entry.split(":",2);
                wanted.computeIfAbsent(parts[0], ignored->new LinkedHashSet<>()).add(parts[1]);
            }
        }
        LinkedHashMap<String,List<String>> selected=new LinkedHashMap<>();
        for (String group : GROUP_ORDER) {
            Set<String> cases=wanted.get(group);
            if (cases == null) continue;
            Set<String> every=new TreeSet<>(SCENARIOS.get(group));
            every.remove("all");
            boolean whole=cases.contains("all") || cases.containsAll(every);
            if (group.equals("claude")) selected.put(group, whole ? List.of("boot-quote","junit","validation") : new ArrayList<>(cases));
            else selected.put(group, whole ? List.of("all") : scenariosInOrder(group, cases));
        }
        return selected;
    }
    /** Selected scenarios of one group in a stable order. */
    private static List<String> scenariosInOrder(String group, Set<String> cases) {
        return new ArrayList<>(new TreeSet<>(cases));
    }
    private static LinkedHashMap<String,List<String>> select(String[] args) {
        checkFeatureCatalog();
        LinkedHashMap<String,List<String>> selected=new LinkedHashMap<>();
        String groups=null, tests=null, features=null, suite=null;
        for (int i=1;i<args.length;i++) {
            if (args[i].equals("--groups") && i+1<args.length) groups=args[++i];
            else if (args[i].equals("--tests") && i+1<args.length) tests=args[++i];
            else if (args[i].equals("--feature") && i+1<args.length) features=args[++i];
            else if (args[i].equals("--suite") && i+1<args.length) suite=args[++i];
            else if (args[i].equals("--repeat") && i+1<args.length) i++;
            else if (!args[i].equals("--approve-fixture-post") && !args[i].equals("--approve-claude")
                    && !args[i].equals("--no-skill") && !args[i].equals("--report")) throw new IllegalArgumentException("Unexpected option: "+args[i]);
        }
        long selectors=java.util.stream.Stream.of(groups,tests,features,suite).filter(Objects::nonNull).count();
        if (selectors > 1) throw new IllegalArgumentException("Use only one of --suite, --feature, --groups, --tests");
        if (suite != null) {
            if (!SUITES.containsKey(suite.strip()))
                throw new IllegalArgumentException("Unknown suite: " + suite + ". Suites: " + String.join(", ", SUITES.keySet()));
            return byFeatures(SUITES.get(suite.strip()));
        }
        if (features != null) return byFeatures(Arrays.asList(features.split(",")));
        if (tests != null) {
            for (String entry:tests.split(",")) {
                String[] parts=entry.strip().split(":",2);
                if (parts.length!=2 || !SCENARIOS.containsKey(parts[0]) || !SCENARIOS.get(parts[0]).contains(parts[1]))
                    throw new IllegalArgumentException("Unknown test: "+entry);
                List<String> cases=selected.computeIfAbsent(parts[0], ignored->new ArrayList<>());
                if (!cases.contains(parts[1])) cases.add(parts[1]);
            }
        } else {
            // No selector: the 23-scenario regression (same as --suite regression).
            String[] list=groups == null ? REGRESSION_GROUPS.toArray(String[]::new) : groups.split(",");
            for(String raw:list) {
                String g=raw.strip();
                if (!SCENARIOS.containsKey(g)) throw new IllegalArgumentException("Unknown group: "+g);
                selected.put(g,g.equals("claude")?List.of("boot-quote","junit","validation"):List.of("all"));
            }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("No scenarios selected");
        return selected;
    }
    /** Scenarios behind a feature; a whole group counts all of its scenarios. */
    private static int scenarioCount(String feature) {
        int count=0;
        for (String entry : FEATURES.get(feature)) {
            String[] parts=entry.split(":",2);
            count += parts[1].equals("all") ? (parts[0].equals("claude") ? 3 : SCENARIOS.get(parts[0]).size() - 1) : 1;
        }
        return count;
    }
    private void describe() {
        System.out.printf("Mode: %s%nRepository: %s%nEclipse: %s%nWorkspace: %s%nBridge port: %d%n",
                mode,repo,eclipse,workspace,port);
    }
    /**
     * Runs the tool and turns failures into clear messages: a setup problem is explained with what to do (exit 2),
     * any other failure is one line saying what failed (exit 1); --trace adds the Java stack trace.
     */
    public static void main(String[] args) {
        boolean trace = Arrays.asList(args).contains("--trace");
        String[] rest = Arrays.stream(args).filter(a -> !a.equals("--trace")).toArray(String[]::new);
        try {
            run(rest);
        } catch (SetupProblem problem) {
            System.err.println();
            System.err.println("SETUP PROBLEM - nothing was changed.");
            System.err.println(problem.getMessage());
            if (trace) problem.printStackTrace();
            System.exit(2);
        } catch (Exception | Error failure) {
            System.err.println();
            System.err.println("FAILED: " + describeFailure(failure));
            if (trace) failure.printStackTrace();
            else System.err.println("(Add --trace to the command for the Java details.)");
            System.exit(1);
        }
    }
    static String describeFailure(Throwable failure) {
        if (failure instanceof NoSuchFileException missing)
            return "file or folder not found: " + missing.getFile() + (missing.getReason() == null ? "" : " (" + missing.getReason() + ")");
        if (failure instanceof AccessDeniedException denied)
            return "access denied: " + denied.getFile() + " (is it open in another program, or read-only?)";
        if (failure instanceof java.net.ConnectException)
            return "could not connect to the test Eclipse (" + failure.getMessage() + "). Is it running, and is bridgePort right?";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
    private static void run(String[] args) throws Exception {
        if (args.length == 0 || Set.of("help","--help","-h").contains(args[0])) {
            System.out.println("From repository root: java dev-e2e/DevE2E.java [config|list|integrate|cleanup|build|all|deploy|test] [selector] [--approve-fixture-post]");
            System.out.println("Selectors (one at most; none = the 23-scenario regression):");
            System.out.println("  --suite regression|extended|full");
            System.out.println("  --feature breakpoints,execution-control,inspection,junit,spring-boot,ownership,stop-events,abandoned-cleanup,bridge");
            System.out.println("  --groups phase1,phase2,boot,boot-quote,acceptance,boot-gaps,stops,lease,bridge   (verifier groups)");
            System.out.println("  --tests acceptance:core-step-into-out,boot-gaps:two-mains,stops:step             (single scenarios)");
            System.out.println("'list' shows every suite, feature, group and scenario.");
            System.out.println("Claude trials (use Claude usage): test --groups claude|--tests claude:boot-quote --approve-claude [--approve-fixture-post] [--repeat N] [--no-skill] [--report]");
            System.out.println("'all' builds, deploys (requires Eclipse closed), starts Eclipse, runs the selected suites.");
            System.out.println("'deploy' reuses Maven output, deploys, starts Eclipse, runs selected suites.");
            System.out.println("'test' reuses dedicated Eclipse or starts it without redeployment; test selectors are optional.");
            System.out.println("'config' only checks this machine's settings (dev-e2e/e2e.properties) and prints them.");
            System.out.println("Add --trace to any command to see the Java details of a failure.");
            return;
        }
        if (!Set.of("config","integrate","cleanup","build","all","deploy","test","list").contains(args[0]))
            throw new SetupProblem("Unknown mode: " + args[0] + ". Modes: config, list, integrate, cleanup, build, all, deploy, test"
                + " ('java dev-e2e/DevE2E.java help' explains them).");
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        boolean approved=Arrays.asList(args).contains("--approve-fixture-post");
        DevE2E x=new DevE2E(root,args[0],approved);
        x.claudeApproval=Arrays.asList(args).contains("--approve-claude");
        x.claudeNoSkill=Arrays.asList(args).contains("--no-skill");
        x.claudeReport=Arrays.asList(args).contains("--report");
        int repeatAt=Arrays.asList(args).indexOf("--repeat");
        if (repeatAt >= 0) {
            x.claudeRepeat=Integer.parseInt(args[repeatAt+1]);
            if (x.claudeRepeat < 1 || x.claudeRepeat > 20) throw new IllegalArgumentException("--repeat must be 1-20");
        }
        x.describe();
        if (args[0].equals("config")) return;
        if (args[0].equals("list")) {
            checkFeatureCatalog();
            System.out.println("== Suites (--suite) ==");
            for (var suite : SUITES.entrySet()) {
                int count=0;
                for (String feature : suite.getValue()) count += scenarioCount(feature);
                System.out.println(suite.getKey() + " (" + count + "): " + String.join(", ", suite.getValue()));
            }
            System.out.println("== Features (--feature) ==");
            for (var feature : FEATURES.entrySet())
                System.out.println(feature.getKey() + " (" + scenarioCount(feature.getKey()) + "): " + String.join(", ", feature.getValue()));
            System.out.println("== Verifier groups (--groups) and scenarios (--tests group:scenario) ==");
            for (String group : GROUP_ORDER) {
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
            x.preflight("build", null);
            x.verifySources();
            x.runProcess(List.of(windows()?"mvn.cmd":"mvn","clean","package"),x.repo,Map.of(),900);
            return;
        }
        LinkedHashMap<String,List<String>> selected=select(args);
        x.preflight(args[0], selected);
        if (args[0].equals("all")) x.buildAndDeploy(true);
        if (args[0].equals("deploy")) x.buildAndDeploy(false);
        x.test(selected);
    }
}
