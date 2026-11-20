package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** Manages ONLY the user-scoped eclipse-debugger entry through the official Claude CLI.
 *  Never parses/writes ~/.claude.json, never touches project .mcp.json. */
public final class ClaudeIntegration {
    public static final String SERVER = "eclipse-debugger";
    private static final String PAYLOAD = "lib/agentic-debugger-mcp.jar";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Path directory;

    public ClaudeIntegration() {
        this(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug"));
    }

    ClaudeIntegration(Path directory) { this.directory = directory; }

    private Path jar() { return directory.resolve("agentic-debugger-mcp.jar"); }
    private Path marker() { return directory.resolve("claude-registration.properties"); }

    /** Called on Eclipse startup, silently refreshes only an already-managed payload. */
    public void refreshBundledRuntime() throws Exception {
        if (Files.isRegularFile(marker())) stageJar();
    }

    private void stageJar() throws Exception {
        Bundle bundle = FrameworkUtil.getBundle(ClaudeIntegration.class);
        if (bundle == null) throw new IOException("Eclipse OSGi bundle is unavailable");
        URL resource = bundle.getEntry(PAYLOAD);
        if (resource == null) throw new IOException("Bundled MCP runtime is missing: " + PAYLOAD);
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "agentic-mcp-", ".jar");
        try {
            try (InputStream in = resource.openStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(temp) < 1000) throw new IOException("Bundled MCP runtime is incomplete");
            try { Files.move(temp, jar(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, jar(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public String status() throws Exception {
        Path cli = locateClaude();
        Result r = invoke(cli, "mcp", "get", SERVER);
        if (r.exit() != 0) return "Not registered with Claude Code";
        return isOwned(cli) ? "Configured (user scope)" : "Existing registration found; not managed by this plugin";
    }

    public String configure() throws Exception {
        Path cli = locateClaude();
        Result existing = invoke(cli, "mcp", "get", SERVER);
        if (existing.exit() == 0) {
            if (isOwned(cli)) {
                refreshBundledRuntime();
                return "Already configured. MCP runtime refreshed.";
            }
            throw new IOException("An eclipse-debugger MCP entry already exists. It was NOT changed. "
                    + "Review it with 'claude mcp get eclipse-debugger' before continuing.");
        }
        Path java = locateJava();
        stageJar();
        Result add = invoke(cli, "mcp", "add", "--transport", "stdio", "--scope", "user", SERVER,
                "--", java.toString(), "-jar", jar().toAbsolutePath().toString());
        if (add.exit() != 0) throw new IOException("Claude registration failed: " + add.text());
        Result verify = invoke(cli, "mcp", "get", SERVER);
        if (verify.exit() != 0 || !mentionsExpectedJar(verify.text())) {
            throw new IOException("Registration could not be verified. Inspect 'claude mcp get eclipse-debugger'; "
                    + "no existing settings were overwritten.");
        }
        writeMarker();
        return "Configured at user scope. Restart Claude Code to load the MCP server.";
    }

    public String remove() throws Exception {
        Path cli = locateClaude();
        if (!isOwned(cli)) throw new IOException("Registration is not confirmed as plugin-managed; nothing was removed.");
        Result result = invoke(cli, "mcp", "remove", SERVER, "--scope", "user");
        if (result.exit() != 0) throw new IOException("Claude Code could not remove registration: " + result.text());
        if (invoke(cli, "mcp", "get", SERVER).exit() == 0) {
            throw new IOException("A same-named registration still resolves; inspect other scopes. Nothing else was deleted.");
        }
        Files.deleteIfExists(marker());
        // The JAR remains on disk for recovery; it is harmless and may be replaced by future configuration.
        return "Removed only the plugin-managed user-level registration. Other MCP settings were preserved.";
    }

    private boolean isOwned(Path cli) throws Exception {
        if (!Files.isRegularFile(marker())) return false;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(marker())) { props.load(in); }
        if (!jar().toAbsolutePath().toString().equals(props.getProperty("jar"))) return false;
        Result get = invoke(cli, "mcp", "get", SERVER);
        return get.exit() == 0 && mentionsExpectedJar(get.text());
    }

    private boolean mentionsExpectedJar(String output) {
        // Claude's own 'mcp get' is the source of truth; require exact registered JAR path.
        return output.contains(jar().toAbsolutePath().toString());
    }

    private void writeMarker() throws Exception {
        Properties props = new Properties();
        props.setProperty("server", SERVER);
        props.setProperty("jar", jar().toAbsolutePath().toString());
        props.setProperty("sha256", sha256(jar()));
        Path tmp = Files.createTempFile(directory, "claude-registration-", ".tmp");
        try {
            try (var out = Files.newOutputStream(tmp)) { props.store(out, "Agentic Debugger managed registration"); }
            Files.move(tmp, marker(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path locateJava() throws IOException {
        String executable = isWindows() ? "java.exe" : "java";
        Path bundled = Path.of(System.getProperty("java.home"), "bin", executable);
        if (Files.isRegularFile(bundled)) return bundled.toAbsolutePath();
        throw new IOException("Java runtime binary not found: " + bundled);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path locateClaude() throws IOException {
        String[] names = isWindows() ? new String[]{"claude.exe", "claude.cmd", "claude.bat"} : new String[]{"claude"};
        Map<String,String> env = System.getenv();
        String value = env.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        for (String part : value.split(java.util.regex.Pattern.quote(isWindows() ? ";" : ":"))) {
            String folder = part.trim().replaceAll("^\"|\"$", "");
            if (folder.isEmpty()) continue;
            for (String name : names) {
                Path path = Path.of(folder, name);
                if (Files.isRegularFile(path)) return path.toAbsolutePath();
            }
        }
        for (String name : names) {
            Path nativeBin = Path.of(System.getProperty("user.home"), ".local", "bin", name);
            if (Files.isRegularFile(nativeBin)) return nativeBin.toAbsolutePath();
        }
        throw new IOException("Claude Code CLI not found. Install Claude Code first and restart Eclipse.");
    }

    private static Result invoke(Path claude, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(claude.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Claude Code CLI timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output.strip());
    }

    private record Result(int exit, String text) { }
}
