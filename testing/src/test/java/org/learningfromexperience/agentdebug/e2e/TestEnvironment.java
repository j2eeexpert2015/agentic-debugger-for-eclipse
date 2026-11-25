package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where the integration tests run: the repository, the test Eclipse's workspace and bridge port, read from
 * {@code dev-e2e/e2e.properties} (the same file DevE2E uses), and the approvals given on the command line
 * ({@code -Dfixture.post.approved=true}, {@code -Dclaude.approved=true}). Fails with a message that says what to do.
 *
 * @author Ayan Dutta
 */
final class TestEnvironment {
    final Path root;
    final Path workspace;
    final int port;

    private static TestEnvironment current;

    private TestEnvironment(Path root, Path workspace, int port) {
        this.root = root;
        this.workspace = workspace;
        this.port = port;
    }

    /** The environment of this run, read once. */
    static synchronized TestEnvironment get() throws IOException {
        if (current == null) current = load();
        return current;
    }

    private static TestEnvironment load() throws IOException {
        Path root = repositoryRoot();
        Path settings = root.resolve("dev-e2e/e2e.properties");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(settings)) { properties.load(in); }
        String rawWorkspace = setting(properties, "workspace", settings);
        String local = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home"));
        Path workspace = Path.of(rawWorkspace.replace("${user.home}", System.getProperty("user.home"))
                .replace("${LOCALAPPDATA}", local)).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace))
            throw new IllegalStateException("The test workspace " + workspace + " does not exist. Run: java dev-e2e/DevE2E.java deploy");
        int port = Integer.parseInt(setting(properties, "bridgePort", settings));
        if (port < 1024 || port > 65535 || port == 8765)
            throw new IllegalStateException("bridgePort in " + settings + " must be a dedicated port (1024-65535, not 8765)");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
        } catch (IOException notRunning) {
            throw new IllegalStateException("The test Eclipse is not running (nothing listens on port " + port + ")."
                    + " Start it with: java dev-e2e/DevE2E.java start   (or deploy, to install a new build first)");
        }
        return new TestEnvironment(root, workspace.toRealPath(), port);
    }

    /** The repository: the first folder upwards from the working folder that has dev-e2e/e2e.properties. */
    private static Path repositoryRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path folder = start; folder != null; folder = folder.getParent())
            if (Files.isRegularFile(folder.resolve("dev-e2e/e2e.properties")) && Files.isRegularFile(folder.resolve("pom.xml")))
                return folder;
        throw new IllegalStateException("No dev-e2e/e2e.properties found from " + start + " upwards."
                + " Create it once per machine from dev-e2e/e2e.properties.example (java dev-e2e/DevE2E.java config checks it)");
    }

    private static String setting(Properties properties, String key, Path file) {
        String value = properties.getProperty(key, "").strip();
        if (value.isEmpty() || value.contains("CHANGE_ME")) throw new IllegalStateException("Set " + key + " in " + file);
        return value;
    }

    /** True when the system property is exactly "true" (an unset Maven property arrives as "${...}"). */
    static boolean approved(String property) {
        return "true".equals(System.getProperty(property));
    }

    /** A setting given with -Dname=value; null when unset or left as an unresolved Maven "${...}". */
    static String option(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() || value.startsWith("${") ? null : value.strip();
    }
}
