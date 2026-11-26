package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** Manages ONLY the user-scoped eclipse-debugger entry through the official Claude CLI.
 *  Never parses/writes ~/.claude.json, never touches project .mcp.json.
 *
 * @author Ayan Dutta
 */
public final class ClaudeIntegration {
    public static final String SERVER = "eclipse-debugger";
    private static final String PAYLOAD = "lib/agentic-debugger-mcp.jar";
    /** The skill packed into the plugin by the build (OPEN-32), copied from {@code skills/eclipse-debugger}. */
    private static final String SKILL_PAYLOAD = "lib/skill/eclipse-debugger";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Path directory;

    public ClaudeIntegration() {
        this(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug"));
    }

    ClaudeIntegration(Path directory) { this.directory = directory; }

    private Path jar() { return directory.resolve("agentic-debugger-mcp.jar"); }
    private Path marker() { return directory.resolve("claude-registration.properties"); }

    /**
     * Called on Eclipse startup, silently refreshes only an already-managed payload: the MCP server jar and (OPEN-32)
     * the Claude Code skill. Only for users who let the plugin configure Claude (the marker file exists).
     */
    public void refreshBundledRuntime() throws Exception {
        if (!Files.isRegularFile(marker())) return;
        stageJar();
        stageSkill();
    }

    /**
     * Copies the bundled skill to {@code ~/.claude/skills/eclipse-debugger/} where it differs, keeping the user's own
     * edits ({@link SkillInstaller}). Writes what it did to the Eclipse console. Never throws: the skill is a help for
     * the agent, not needed for the MCP server to work.
     */
    void stageSkill() {
        try {
            Bundle bundle = FrameworkUtil.getBundle(ClaudeIntegration.class);
            if (bundle == null) return;
            java.util.Enumeration<URL> entries = bundle.findEntries(SKILL_PAYLOAD, "*", true);
            if (entries == null) return;
            Map<String, byte[]> files = new java.util.TreeMap<>();
            while (entries.hasMoreElements()) {
                URL url = entries.nextElement();
                String path = url.getPath();
                int at = path.indexOf(SKILL_PAYLOAD + "/");
                if (at < 0 || path.endsWith("/")) continue;
                try (InputStream in = url.openStream()) {
                    files.put(path.substring(at + SKILL_PAYLOAD.length() + 1), in.readAllBytes());
                }
            }
            if (files.isEmpty()) return;
            List<String> done = SkillInstaller.install(files, skillFolder(), directory.resolve("skill-installed.properties"));
            for (String line : done) System.out.println("[Eclipse Agentic Debugger] Skill " + line);
        } catch (Exception | LinkageError failure) {
            System.err.println("[Eclipse Agentic Debugger] Skill update failed: " + failure.getMessage());
        }
    }

    /** {@code ~/.claude/skills/eclipse-debugger}; Claude Code reads user skills from there. */
    private static Path skillFolder() {
        return Path.of(System.getProperty("user.home"), ".claude", "skills", "eclipse-debugger");
    }

    /**
     * OPEN-23: stages the bundled MCP server jar into this instance's folder (copied only when it changed) and returns
     * its path. Used for GitHub Copilot, which needs the jar without Claude being configured.
     */
    public Path stageRuntimeJar() throws Exception {
        stageJar();
        return jar().toAbsolutePath();
    }

    /** OPEN-23: the Java executable that runs the MCP server, Eclipse's own runtime (as for Claude). */
    public static Path javaExecutable() throws IOException { return locateJava(); }

    private void stageJar() throws Exception {
        Bundle bundle = FrameworkUtil.getBundle(ClaudeIntegration.class);
        if (bundle == null) throw new IOException("Eclipse OSGi bundle is unavailable");
        URL resource = bundle.getEntry(PAYLOAD);
        if (resource == null) throw new IOException("Bundled MCP runtime is missing: " + PAYLOAD);
        RuntimeJarInstaller.installIfChanged(resource, jar());
    }

    public String status() throws Exception {
        Path cli = locateClaude();
        Result r = invoke(cli, "mcp", "get", SERVER);
        if (r.exit() != 0) return "Not configured";
        ClaudeRegistration registration = ClaudeRegistration.parse(r.text());
        if (registration.exactJar(jar())) return "Configured (user scope)";
        if (registration.legacyJar(Path.of(System.getProperty("user.home")), isWindows())) {
            return "Update available — previous Agentic Debugger registration detected";
        }
        return "Existing eclipse-debugger registration is not recognized; no changes made";
    }

    /** Registers on first use; recognizes same-path registrations even without a marker. */
    public String configure() throws Exception {
        Path cli = locateClaude();
        Result existing = invoke(cli, "mcp", "get", SERVER);
        if (existing.exit() == 0) {
            ClaudeRegistration registration = ClaudeRegistration.parse(existing.text());
            if (registration.exactJar(jar())) {
                stageJar();
                writeMarker(); // Adopt our exact, user-scoped registration without touching Claude JSON.
                stageSkill();
                return "Already configured (user scope). Bundled MCP runtime and skill refreshed.";
            }
            if (registration.legacyJar(Path.of(System.getProperty("user.home")), isWindows())) {
                throw new MigrationRequiredException(registration.command + " " + registration.args);
            }
            throw new IOException("An unrecognized eclipse-debugger entry exists. Nothing was changed. "
                    + "Inspect it with 'claude mcp get eclipse-debugger'.");
        }
        Path java = locateJava();
        stageJar();
        register(cli, java.toString(), jar().toAbsolutePath().toString());
        verifyCurrent(cli);
        writeMarker();
        stageSkill();
        return "Configured at user scope, with the eclipse-debugger skill. Restart Claude Code to load the MCP server.";
    }

    /** Called ONLY after displaying the existing command and receiving explicit approval. */
    public String migrateExisting() throws Exception {
        Path cli = locateClaude();
        Result existing = invoke(cli, "mcp", "get", SERVER);
        if (existing.exit() != 0) throw new IOException("No existing entry to migrate. Use Configure instead.");
        ClaudeRegistration previous = ClaudeRegistration.parse(existing.text());
        if (!previous.legacyJar(Path.of(System.getProperty("user.home")), isWindows())) {
            throw new IOException("Registration changed or is not a recognized legacy installation. Nothing changed.");
        }
        Path java = locateJava();
        stageJar(); // Prepare new JAR before touching Claude configuration.
        Result removed = invoke(cli, "mcp", "remove", SERVER, "--scope", "user");
        if (removed.exit() != 0) throw new IOException("Could not remove previous user entry: " + removed.text());
        try {
            register(cli, java.toString(), jar().toAbsolutePath().toString());
            verifyCurrent(cli);
            writeMarker();
        } catch (Exception cause) {
            // Restore the exact old command if replacement fails. Do not modify other MCP servers.
            try {
                Result current = invoke(cli, "mcp", "get", SERVER);
                if (current.exit() == 0 && ClaudeRegistration.parse(current.text()).exactJar(jar())) {
                    invoke(cli, "mcp", "remove", SERVER, "--scope", "user");
                }
                register(cli, previous.command, previous.registeredJar());
            } catch (Exception rollback) {
                cause.addSuppressed(rollback);
                throw new IOException("Migration failed and automatic rollback failed. "
                        + "Inspect 'claude mcp get eclipse-debugger'.", cause);
            }
            throw new IOException("Migration failed; previous registration restored. " + cause.getMessage(), cause);
        }
        return "Registration updated to the Eclipse-bundled MCP server. Restart Claude Code.";
    }

    private void register(Path cli, String command, String serverJar) throws Exception {
        Result add = invoke(cli, "mcp", "add", "--transport", "stdio", "--scope", "user", SERVER,
                "--", command, "-jar", serverJar);
        if (add.exit() != 0) throw new IOException("Claude registration failed: " + add.text());
    }

    private void verifyCurrent(Path cli) throws Exception {
        Result verify = invoke(cli, "mcp", "get", SERVER);
        if (verify.exit() != 0 || !ClaudeRegistration.parse(verify.text()).exactJar(jar())) {
            throw new IOException("Claude Code registration does not match the bundled MCP server.");
        }
    }

    public String remove() throws Exception {
        Path cli = locateClaude();
        Result current = invoke(cli, "mcp", "get", SERVER);
        if (current.exit() != 0 || !ClaudeRegistration.parse(current.text()).exactJar(jar())) {
            throw new IOException("Registration is not confirmed as belonging to this plugin; nothing removed.");
        }
        Result removed = invoke(cli, "mcp", "remove", SERVER, "--scope", "user");
        if (removed.exit() != 0) throw new IOException("Claude Code could not remove registration: " + removed.text());
        Files.deleteIfExists(marker());
        return "Removed the plugin's user-level registration. Other MCP settings were preserved.";
    }

    public static final class MigrationRequiredException extends IOException {
        public MigrationRequiredException(String existingCommand) {
            super("Previous Agentic Debugger registration detected: " + existingCommand);
        }
    }

    private void writeMarker() throws Exception {
        Properties props = new Properties();
        props.setProperty("server", SERVER);
        props.setProperty("jar", jar().toAbsolutePath().toString());
        props.setProperty("sha256", RuntimeJarInstaller.sha256(jar()));
        Path tmp = Files.createTempFile(directory, "claude-registration-", ".tmp");
        try {
            try (var out = Files.newOutputStream(tmp)) { props.store(out, "Agentic Debugger managed registration"); }
            Files.move(tmp, marker(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
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
