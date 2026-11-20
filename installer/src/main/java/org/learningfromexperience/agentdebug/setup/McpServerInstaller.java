package org.learningfromexperience.agentdebug.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Installs the embedded MCP executable in a stable, per-user location. */
final class McpServerInstaller {
    static final String PAYLOAD = "/payload/agentic-debugger-mcp-1.0.0-SNAPSHOT.jar";
    private static final String MANAGED_BY = "agentic-debugger-setup";
    private final Path directory;

    McpServerInstaller(Path directory) {
        this.directory = directory;
    }

    Path installedJar() {
        return directory.resolve("mcp-server.jar");
    }

    Path metadata() {
        return directory.resolve("installation.properties");
    }

    boolean isManaged() throws IOException {
        if (!Files.isRegularFile(metadata())) return false;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata())) {
            properties.load(input);
        }
        return MANAGED_BY.equals(properties.getProperty("managedBy"));
    }

    void verifyPayloadExists() {
        if (getClass().getResource(PAYLOAD) == null) {
            throw new IllegalStateException("The installer JAR is incomplete: embedded MCP server is missing. "
                    + "Build mcp-server before packaging installer.");
        }
    }

    void stage() throws IOException {
        verifyPayloadExists();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "mcp-server-", ".jar.tmp");
        try {
            try (InputStream input = getClass().getResourceAsStream(PAYLOAD)) {
                if (input == null) throw new IOException("Embedded MCP payload missing");
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary, installedJar(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void markManaged() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("managedBy", MANAGED_BY);
        properties.setProperty("serverName", ClaudeCodeConfigurator.SERVER_NAME);
        Path temp = Files.createTempFile(directory, "install-", ".properties.tmp");
        try {
            try (var out = Files.newOutputStream(temp)) {
                properties.store(out, "Agentic Debugger installation metadata");
            }
            Files.move(temp, metadata(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    void uninstallFiles() throws IOException {
        if (!isManaged()) throw new IOException("No setup-managed installation was found; refusing to delete files.");
        Files.deleteIfExists(installedJar());
        Files.deleteIfExists(metadata());
        // Do not recursively delete the directory; it might contain user files.
    }
}
