import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.zip.*;

/**
 * The test steps that are not Maven's own work: Maven runs them through the exec plugin in the test profiles
 * (mvn verify -Drun=..., docs/local-commands.md), and the GitHub workflow "Tests on Windows, Linux and macOS" uses the
 * same steps on all three systems. One Java file, so there are no shell differences. Run from the repository root:
 *
 *   java dev-e2e/TestSteps.java close-test-eclipse           close the test Eclipse gracefully (only that one)
 *   java dev-e2e/TestSteps.java claude-code-registration     check Claude Code's eclipse-debugger entry (changes nothing)
 *   java dev-e2e/TestSteps.java zip-lifecycle                install the built update-site zip into the test Eclipse,
 *                                                            uninstall it, reinstall it
 *   java dev-e2e/TestSteps.java eclipse <release> <folder>   GitHub: download and unpack Eclipse (skipped when there);
 *                                                            writes ECLIPSE_HOME (the installation folder) to $GITHUB_ENV
 *   java dev-e2e/TestSteps.java settings                     GitHub: write dev-e2e/e2e.properties for that Eclipse
 *   java dev-e2e/TestSteps.java report <title> <min> <paths> sum JUnit results; fail on failures, errors, skips, too few
 *   java dev-e2e/TestSteps.java collect                      copy Eclipse and test logs into ci-out/
 *   java -Djava.awt.headless=false dev-e2e/TestSteps.java screenshot <file>
 *
 * The test Eclipse is the "eclipse" folder of dev-e2e/e2e.properties (or ECLIPSE_HOME). Installation folder per
 * system: Windows and Linux the folder with eclipse(.exe); macOS Eclipse.app/Contents/Eclipse, started by
 * Eclipse.app/Contents/MacOS/eclipse. dev-e2e/DevE2E.java uses the same rule.
 */
public class TestSteps {
    static final String FEATURE = "org.learningfromexperience.eclipse.agentdebug.feature";
    static final String IU = FEATURE + ".feature.group";
    static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    static final boolean WINDOWS = OS.contains("win"), MAC = OS.contains("mac");

    public static void main(String[] args) throws Exception {
        try {
            dispatch(args);
        } catch (IllegalStateException failed) {
            fail(failed.getMessage());
        }
    }

    static void dispatch(String[] args) throws Exception {
        if (args.length == 0) fail("mode missing: close-test-eclipse, claude-code-registration, zip-lifecycle, eclipse,"
                + " settings, report, collect, screenshot");
        switch (args[0]) {
            case "close-test-eclipse" -> closeTestEclipse();
            case "claude-code-registration" -> claudeCodeRegistration();
            case "eclipse" -> eclipse(args[1], Path.of(args[2]).toAbsolutePath());
            case "settings" -> settings();
            case "zip-lifecycle" -> zipLifecycle();
            case "report" -> report(args[1], Integer.parseInt(args[2]), Arrays.copyOfRange(args, 3, args.length));
            case "collect" -> collect();
            case "screenshot" -> screenshot(Path.of(args[1]));
            default -> fail("unknown mode " + args[0]);
        }
    }

    // ---- Eclipse ---------------------------------------------------------------------------------------------------

    static Path installation(Path folder) {
        return MAC ? folder.resolve("Eclipse.app/Contents/Eclipse") : folder.resolve("eclipse");
    }

    /** The test Eclipse's installation folder: ECLIPSE_HOME (GitHub), else "eclipse" in dev-e2e/e2e.properties. */
    static Path testEclipse() throws IOException {
        String env = System.getenv("ECLIPSE_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        Path props = Path.of("dev-e2e/e2e.properties");
        if (!Files.isRegularFile(props)) fail(props + " is missing (docs/local-commands.md: java dev-e2e/DevE2E.java config)");
        Properties p = new Properties();
        try (var in = Files.newInputStream(props)) { p.load(in); }
        String eclipse = p.getProperty("eclipse", "").strip();
        if (eclipse.isBlank()) fail("set eclipse= in " + props);
        return Path.of(eclipse).toAbsolutePath().normalize();
    }

    /** The program that starts Eclipse (macOS: Contents/MacOS/eclipse; elsewhere eclipse(.exe) in the folder). */
    static Path startProgram(Path installation) {
        if (MAC) return installation.getParent().resolve("MacOS/eclipse");
        return installation.resolve(WINDOWS ? "eclipse.exe" : "eclipse");
    }

    static Path launcher(Path installation) {
        if (MAC) return installation.getParent().resolve("MacOS/eclipse");
        return installation.resolve(WINDOWS ? "eclipsec.exe" : "eclipse");   // eclipsec: console version, waits
    }

    static void eclipse(String release, Path folder) throws Exception {
        Path home = installation(folder);
        if (Files.isRegularFile(launcher(home))) {
            System.out.println("Eclipse " + release + " already here (cache): " + home);
        } else {
            String arch = System.getProperty("os.arch").contains("aarch64") ? "aarch64" : "x86_64";
            String name = "eclipse-java-" + release + "-R-" + (WINDOWS ? "win32-" + arch + ".zip"
                    : MAC ? "macosx-cocoa-" + arch + ".dmg" : "linux-gtk-" + arch + ".tar.gz");
            String url = "https://download.eclipse.org/technology/epp/downloads/release/" + release + "/R/" + name;
            Files.createDirectories(folder);
            Path download = folder.resolve(name);
            download(url, download);
            if (WINDOWS) unzip(download, folder);
            else if (MAC) {
                Path mount = Files.createTempDirectory("eclipse-dmg");
                run(List.of("hdiutil", "attach", "-nobrowse", "-quiet", "-mountpoint", mount.toString(), download.toString()), null);
                try {
                    run(List.of("ditto", mount.resolve("Eclipse.app").toString(), folder.resolve("Eclipse.app").toString()), null);
                } finally {
                    run(List.of("hdiutil", "detach", "-quiet", mount.toString()), null);
                }
            } else run(List.of("tar", "-xzf", download.toString(), "-C", folder.toString()), null);
            Files.delete(download);
            if (!Files.isRegularFile(launcher(home))) fail("Eclipse unpacked, but " + launcher(home) + " is missing");
            System.out.println("Eclipse " + release + " unpacked: " + home);
        }
        env("ECLIPSE_HOME", home.toString());
    }

    static void download(String url, Path to) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        for (int attempt = 1; ; attempt++) {
            System.out.println("Download " + url + " (attempt " + attempt + ")");
            HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofFile(to));
            if (response.statusCode() == 200) return;
            if (attempt == 3) fail("download failed, HTTP " + response.statusCode() + ": " + url);
            Thread.sleep(10_000);
        }
    }

    static void unzip(Path zip, Path into) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path target = into.resolve(e.getName()).normalize();
                if (!target.startsWith(into)) throw new IOException("bad zip entry " + e.getName());
                if (e.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ---- test settings ---------------------------------------------------------------------------------------------

    static void settings() throws IOException {
        String home = need("ECLIPSE_HOME").replace('\\', '/');
        String repo = Path.of("").toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(Path.of("dev-e2e/e2e.properties"), String.join("\n",
                "# Written by the CI workflow. Never commit this file.",
                "repository=" + repo,
                "eclipse=" + home,
                "workspace=${LOCALAPPDATA}/AgenticDebugger/java-e2e-workspace",
                "bridgePort=" + System.getenv().getOrDefault("BRIDGE_PORT", "21766"), ""));
        System.out.println(Files.readString(Path.of("dev-e2e/e2e.properties")));
    }

    // ---- install, uninstall, reinstall the zip ---------------------------------------------------------------------

    static void zipLifecycle() throws Exception {
        Path home = testEclipse();
        List<Path> zips;
        try (var files = Files.list(Path.of("eclipse-update-site/target"))) {
            zips = files.filter(f -> f.getFileName().toString().matches("agentic-debugger-update-site-.*\\.zip")).toList();
        }
        if (zips.size() != 1) fail("expected one update-site zip in eclipse-update-site/target, found " + zips.size());
        Path site = Files.createTempDirectory("agentdebug-site");
        unzip(zips.get(0), site);
        String version;
        try (var files = Files.list(site.resolve("features"))) {
            String jar = files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith(FEATURE + "_"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no feature jar in the zip"));
            version = jar.substring(FEATURE.length() + 1, jar.length() - 4);
        }
        String siteUri = site.toUri().toString();
        List<String> director = List.of(launcher(home).toString(), "-nosplash", "-application",
                "org.eclipse.equinox.p2.director", "-destination", home.toString());
        List<String> lines = new ArrayList<>(List.of("### Install tests: zip " + zips.get(0).getFileName() + " (" + version + ")", ""));

        // An earlier build may be installed (local test Eclipse): remove it first, so the zip's install is a real one
        if (roots(director).contains(IU + "/")) run(plus(director, "-uninstallIU", IU), null);

        run(plus(director, "-repository", siteUri, "-installIU", IU + "/" + version), null);
        expect(roots(director).contains(IU + "/" + version), "installed after the first install");
        lines.add("- Install: PASS");

        run(plus(director, "-uninstallIU", IU), null);
        expect(!roots(director).contains(IU), "gone after uninstall");
        lines.add("- Uninstall: PASS");

        run(plus(director, "-repository", siteUri, "-installIU", IU + "/" + version), null);
        expect(roots(director).contains(IU + "/" + version), "installed after the reinstall");
        lines.add("- Reinstall: PASS");
        summary(lines);
    }

    // ---- close the test Eclipse ------------------------------------------------------------------------------------

    /**
     * Closes only the test Eclipse, gracefully, and waits up to 60 s. Windows: close-test-eclipse.ps1 (CloseMainWindow,
     * as if its window's X was clicked). Linux and macOS: a polite stop request (SIGTERM). Never a forced kill: a killed
     * Eclipse does not save its workspace (removed breakpoints come back).
     */
    static void closeTestEclipse() throws Exception {
        if (WINDOWS) {
            run(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    Path.of("dev-e2e/close-test-eclipse.ps1").toAbsolutePath().toString()), null);
            return;
        }
        String program = startProgram(testEclipse()).toAbsolutePath().normalize().toString();
        List<ProcessHandle> running = ProcessHandle.allProcesses().filter(p -> p.info().command()
                .map(c -> Path.of(c).toAbsolutePath().normalize().toString().equals(program)).orElse(false)).toList();
        if (running.isEmpty()) { System.out.println("Test Eclipse is not running (" + program + ")."); return; }
        running.forEach(ProcessHandle::destroy);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < until && running.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(500);
        if (running.stream().anyMatch(ProcessHandle::isAlive))
            fail("Test Eclipse did not close: a dialog may be waiting in it. Answer it, then run again.");
        System.out.println("Test Eclipse closed.");
    }

    // ---- Claude Code's registration --------------------------------------------------------------------------------

    /** claude mcp get eclipse-debugger: must exist and be Connected; on Windows it must start our launcher. */
    static void claudeCodeRegistration() throws Exception {
        List<String> command = WINDOWS ? List.of("cmd", "/c", "claude", "mcp", "get", "eclipse-debugger")
                : List.of("claude", "mcp", "get", "eclipse-debugger");
        String output;
        try {
            output = run(command, null);
        } catch (IllegalStateException | IOException notThere) {
            fail("Claude Code has no eclipse-debugger entry, or claude is not on the PATH. Set it up in Eclipse:"
                    + " Preferences > Agentic Debugger > Claude Code.");
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!output.contains("Connected") || output.contains("Failed")) problems.add("not Connected");
        if (WINDOWS && !output.contains("start-mcp-server.cmd")) problems.add("not our launcher start-mcp-server.cmd (Needs an update?)");
        if (!problems.isEmpty())
            fail("Claude Code's eclipse-debugger entry: " + String.join("; ", problems)
                    + ". Fix: Preferences > Agentic Debugger > Claude Code (Update or Set up).");
        System.out.println("Claude Code's eclipse-debugger entry is fine.");
    }

    static String roots(List<String> director) throws Exception {
        return run(plus(director, "-listInstalledRoots"), null);
    }

    static void expect(boolean ok, String what) { if (!ok) fail("the Agentic Debugger feature is not " + what); }

    // ---- results ---------------------------------------------------------------------------------------------------

    /** Sums Surefire/Failsafe XML (TEST-*.xml in a folder, or failsafe-summary.xml). Every path must have tests. */
    static void report(String title, int minimum, String[] paths) throws Exception {
        List<String> lines = new ArrayList<>(List.of("### " + title, "",
                "| Results | Tests | Failures | Errors | Skipped |", "|---|---:|---:|---:|---:|"));
        int[] total = new int[4];
        List<String> problems = new ArrayList<>();
        for (String p : paths) {
            int[] c = new int[4];
            Path path = Path.of(p);
            if (Files.isDirectory(path)) {
                try (var files = Files.list(path)) {
                    for (Path f : files.filter(x -> x.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                        String xml = Files.readString(f, StandardCharsets.UTF_8);
                        Matcher m = Pattern.compile("<testsuite\\b[^>]*>").matcher(xml);
                        if (m.find()) {
                            String tag = m.group();
                            c[0] += attr(tag, "tests"); c[1] += attr(tag, "failures");
                            c[2] += attr(tag, "errors"); c[3] += attr(tag, "skipped");
                        }
                    }
                }
            } else if (Files.isRegularFile(path)) {
                String xml = Files.readString(path, StandardCharsets.UTF_8);
                c[0] = element(xml, "completed"); c[1] = element(xml, "failures");
                c[2] = element(xml, "errors"); c[3] = element(xml, "skipped");
            }
            if (c[0] == 0) problems.add("no test results in " + p);
            lines.add("| " + p + " | " + c[0] + " | " + c[1] + " | " + c[2] + " | " + c[3] + " |");
            for (int i = 0; i < 4; i++) total[i] += c[i];
        }
        lines.add("| **Total** | **" + total[0] + "** | **" + total[1] + "** | **" + total[2] + "** | **" + total[3] + "** |");
        if (total[0] < minimum) problems.add(total[0] + " tests ran, at least " + minimum + " expected");
        if (total[1] + total[2] > 0) problems.add((total[1] + total[2]) + " failed");
        if (total[3] > 0) problems.add(total[3] + " skipped");
        lines.add("");
        lines.add(problems.isEmpty() ? "PASS" : "**FAIL:** " + String.join("; ", problems));
        summary(lines);
        if (!problems.isEmpty()) System.exit(1);
    }

    static int attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"(\\d+)\"").matcher(tag);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static int element(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(\\d+)</" + name + ">").matcher(xml);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ---- logs and screenshots --------------------------------------------------------------------------------------

    static void collect() throws IOException {
        Path out = Files.createDirectories(Path.of("ci-out"));
        String local = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home"));
        copy(Path.of(local, "AgenticDebugger/java-e2e-workspace/.metadata/.log"), out.resolve("eclipse-workspace.log"));
        copy(Path.of("eclipse-plugin-ui-tests/target/work/data/.metadata/.log"), out.resolve("ui-tests-eclipse.log"));
        copyTree(Path.of("dev-e2e/target"), out.resolve("dev-e2e"));
        copyTree(Path.of("testing/target/failsafe-reports"), out.resolve("failsafe-reports"));
        copyTree(Path.of("eclipse-plugin-ui-tests/target/surefire-reports"), out.resolve("ui-tests-reports"));
        copyTree(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug"), out.resolve("eclipse-agentdebug"),
                f -> f.toString().endsWith(".log"));
        try (var files = Files.walk(out)) { files.filter(Files::isRegularFile).forEach(System.out::println); }
    }

    static void copy(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    static void copyTree(Path from, Path to) throws IOException { copyTree(from, to, f -> true); }

    static void copyTree(Path from, Path to, java.util.function.Predicate<Path> keep) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var files = Files.walk(from)) {
            for (Path f : files.filter(Files::isRegularFile).filter(keep).toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                Files.createDirectories(target.getParent());
                Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void screenshot(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        var screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        var image = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(screen));
        javax.imageio.ImageIO.write(image, "png", file.toFile());
        System.out.println("Screenshot " + file);
    }

    // ---- small helpers ---------------------------------------------------------------------------------------------

    static String run(List<String> command, Path dir) throws Exception {
        System.out.println("RUN: " + String.join(" ", command));
        Path log = Files.createTempFile("ci-run", ".log");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                .directory(dir == null ? null : dir.toFile()).start();
        p.getOutputStream().close();
        boolean done = p.waitFor(15, TimeUnit.MINUTES);
        String output = Files.readString(log, StandardCharsets.UTF_8);
        System.out.println(output);
        if (!done) { p.destroyForcibly(); fail("timed out: " + command.get(0)); }
        if (p.exitValue() != 0) throw new IllegalStateException("exit " + p.exitValue() + ": " + String.join(" ", command));
        return output;
    }

    static List<String> plus(List<String> base, String... more) {
        List<String> all = new ArrayList<>(base);
        all.addAll(List.of(more));
        return all;
    }

    static String need(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) fail(name + " is not set (run the 'eclipse' mode first)");
        return v;
    }

    static void env(String name, String value) throws IOException {
        System.out.println(name + "=" + value);
        String file = System.getenv("GITHUB_ENV");
        if (file != null) Files.writeString(Path.of(file), name + "=" + value + "\n", StandardOpenOption.APPEND);
    }

    static void summary(List<String> lines) throws IOException {
        lines.forEach(System.out::println);
        String file = System.getenv("GITHUB_STEP_SUMMARY");
        if (file != null) Files.writeString(Path.of(file), String.join("\n", lines) + "\n\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void fail(String message) {
        System.err.println("FAILED: " + message);
        System.exit(1);
    }
}
