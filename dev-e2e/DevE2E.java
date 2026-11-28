import java.io.*;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Java 21 source launcher for the test environment: checks this machine's settings, builds, installs the plugin into
 * the dedicated test Eclipse and starts it. The tests themselves are JUnit 5 classes in the testing module, run with
 * Maven (docs/integration-tests.md). Never terminates an Eclipse process.
 */
public class DevE2E {
    private final Path bundle, repo, eclipse, workspace;
    private final int port;
    private final String mode;
    private static final String IU = "org.learningfromexperience.eclipse.agentdebug.feature.feature.group";

    /**
     * A problem with this machine's setup, found before anything runs. Its message says what is wrong and what to do;
     * main prints it without a stack trace.
     */
    static final class SetupProblem extends RuntimeException {
        SetupProblem(String message) { super(message); }
    }

    private static final String PROPERTIES = "dev-e2e/e2e.properties";

    private DevE2E(Path source, String mode) throws Exception {
        this.mode = mode;
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
                if (!Files.isRegularFile(launcherOf(eclipseDir)))
                    problems.add("No " + launcherOf(eclipseDir) + " for the 'eclipse' folder " + eclipseDir
                        + "\n   Point 'eclipse' at the installation folder itself (the one with eclipse" + (windows() ? ".exe" : "")
                        + " and eclipse.ini" + (mac() ? "; on macOS .../Eclipse.app/Contents/Eclipse" : "") + ").");
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
        if (!Files.isRegularFile(bundle.resolve("testing/pom.xml")))
            problems.add("This checkout has no testing/pom.xml: " + bundle + " (incomplete clone? run git status).");

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
     * deploying (and a warning when sources are newer than it).
     */
    private void preflight(String mode) {
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
                        newest(repo.resolve("report/src"))));
                if (changedAt > builtAt)
                    System.out.println("WARNING: source files changed after the last build (" + Instant.ofEpochMilli(changedAt)
                        + " > " + Instant.ofEpochMilli(builtAt) + "). 'deploy' installs the OLD build. Run 'mvn clean package' first if that is not intended.");
            }
        }
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
    private static boolean mac() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"); }
    /**
     * The program that starts this Eclipse. Windows and Linux: eclipse(.exe) in the installation folder. macOS: the
     * installation folder is Eclipse.app/Contents/Eclipse (eclipse.ini, plugins, p2) and the program is
     * Eclipse.app/Contents/MacOS/eclipse (28 Nov, cross-platform CI).
     */
    private static Path launcherOf(Path installation) {
        if (mac() && !Files.isRegularFile(installation.resolve("eclipse"))
                && installation.getFileName() != null && installation.getFileName().toString().equals("Eclipse"))
            return installation.getParent().resolve("MacOS").resolve("eclipse");
        return executable(installation, "eclipse");
    }
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
        // dev-e2e/target, not a module's target: "mvn clean" must not need to delete it (OPEN-38 step 6).
        Path log = repo.resolve("dev-e2e/target/java-e2e-p2-director.log");
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
    private Path eclipseExe() {return launcherOf(eclipse);}
    private Path directorExe() {return windows() ? eclipse.resolve("eclipsec.exe") : launcherOf(eclipse);}

    private void verifySources() throws Exception {
        check(Files.isRegularFile(repo.resolve("examples/spring-boot-debugging/src/main/java/org/learningfromexperience/bootfixture/orders/FixtureIdentityController.java")),
                "Missing committed fixture identity controller");
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
        check(info == null || !info.contains("is a test command"),
                "The Eclipse on port " + port + " was not started by DevE2E, so its test commands are off (OPEN-38 step 5)."
                + "\n   Close that Eclipse and run this command again: DevE2E starts it with AGENTDEBUG_E2E=1.");
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
        ProcessBuilder pb=new ProcessBuilder(eclipseExe().toString(),"-clean","-data",workspace.toString());
        pb.directory(eclipse.toFile());pb.environment().putAll(env);
        // Eclipse's console output must go to a file. Left as an unread pipe, it fills up and any
        // System.out.println inside Eclipse (including the debugger plugin) blocks indefinitely.
        // Not under a Maven module's target: the running test Eclipse keeps this file open, and "mvn clean" of the
        // testing module then failed to delete it (25 Nov 10:09). dev-e2e is no Maven module, so nothing cleans it.
        Path consoleLog=repo.resolve("dev-e2e/target/eclipse-console.log");
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
    /**
     * Rebuilds a Claude trial's report from the events it recorded (EVENTS.jsonl next to the report, written since the
     * trials set AGENTDEBUG_REPORT_EVENTS=1) with the report module just built, and runs the REPORT CHECK. No Claude
     * usage, no Eclipse: for changes that only touch the report page. Without a path: the newest recorded trial.
     */
    private static void reportReplay(Path root, Path events) throws Exception {
        Path jar = root.resolve("report/target/agentic-debugger-report-1.0.0-SNAPSHOT.jar");
        if (!Files.isRegularFile(jar)) throw new SetupProblem("Run 'mvn clean package' first: " + jar + " is missing.");
        if (events == null) {
            Path trials = root.resolve("evaluation/reports/claude-trials");
            if (Files.isDirectory(trials)) try (var walk = Files.walk(trials, 2)) {
                events = walk.filter(p -> p.getFileName().toString().endsWith(".events.jsonl"))
                        .max(Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
            }
            if (events == null) throw new SetupProblem("No recorded trial events yet. Run one Claude trial with --report"
                    + " (since this update it keeps its events), then report-replay works without Claude.");
        }
        String name = events.getFileName().toString().replace(".events.jsonl", "");
        Path out = events.toAbsolutePath().resolveSibling(name + ".replayed.html");
        System.out.println("Events: " + events.toAbsolutePath());
        String java = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
        Process p = new ProcessBuilder(java, "-cp", jar.toString(), "org.learningfromexperience.agentdebug.report.ReportReplay",
                events.toAbsolutePath().toString(), out.toString()).inheritIO().start();
        if (p.waitFor() != 0) throw new SetupProblem("Report replay failed (see above).");
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0 || Set.of("help","--help","-h").contains(args[0])) {
            System.out.println("From the repository root: java dev-e2e/DevE2E.java config|build|all|deploy|start|report-replay");
            System.out.println("  config         check this machine's settings (dev-e2e/e2e.properties) and print them");
            System.out.println("  build          mvn clean package");
            System.out.println("  all            build, install the plugin into the test Eclipse (close it first) and start it");
            System.out.println("  deploy         install the last build into the test Eclipse (close it first) and start it");
            System.out.println("  start          start the test Eclipse with the plugin already installed (or check the running one)");
            System.out.println("  report-replay [EVENTS.jsonl]  rebuild the newest Claude trial report from its recorded events");
            System.out.println("                 and run the REPORT CHECK (no Claude, no Eclipse; needs 'mvn clean package' first)");
            System.out.println("The tests are JUnit classes, run with Maven once the test Eclipse runs, for example:");
            System.out.println("  mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true");
            System.out.println("  (all suites, classes and options: docs/integration-tests.md)");
            System.out.println("Add --trace to any command to see the Java details of a failure.");
            return;
        }
        if (args[0].equals("report-replay")) {
            reportReplay(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                    args.length > 1 && !args[1].startsWith("--") ? Path.of(args[1]) : null);
            return;
        }
        if (Set.of("test","list").contains(args[0]))
            throw new SetupProblem("'" + args[0] + "' was replaced on 25 Nov 2026: the integration tests are JUnit classes now."
                + "\n  Start the test Eclipse (java dev-e2e/DevE2E.java start), then for example:"
                + "\n    mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true"
                + "\n  Every suite, test class and option: docs/integration-tests.md");
        if (!Set.of("config","build","all","deploy","start").contains(args[0]))
            throw new SetupProblem("Unknown mode: " + args[0] + ". Modes: config, build, all, deploy, start, report-replay"
                + " ('java dev-e2e/DevE2E.java help' explains them).");
        if (args.length > 1)
            throw new IllegalArgumentException("Unexpected option: " + args[1] + " (test options belong to the Maven command;"
                + " see docs/integration-tests.md)");
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        DevE2E x=new DevE2E(root,args[0]);
        x.describe();
        if (args[0].equals("config")) return;
        x.verifySources();
        x.preflight(args[0]);
        if (args[0].equals("build")) {
            x.runProcess(List.of(windows()?"mvn.cmd":"mvn","clean","package"),x.repo,Map.of(),900);
            return;
        }
        if (args[0].equals("all")) x.buildAndDeploy(true);
        if (args[0].equals("deploy")) x.buildAndDeploy(false);
        x.startAndWait();
        System.out.println("The test Eclipse is ready. Run the tests, for example:");
        System.out.println("  mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true          (regression suite)");
        System.out.println("  mvn -pl testing verify -Pintegration-tests -Dit.test=FullSuite -Dfixture.post.approved=true");
    }
}
