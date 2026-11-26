package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * OPEN-23: what GitHub Copilot for Eclipse is told about our MCP server, as the JSON its {@code mcpRegistration}
 * extension point expects: {@code {"servers":{"eclipse-debugger":{"type":"stdio","command":...,"args":[]}}}}.
 * Copilot shows this in its own approval dialog; the user approves once, and again whenever the JSON changes.
 * <p><b>Stable command (OPEN-23 step e, 26 Nov).</b> Copilot keeps the approval only while the JSON stays exactly the
 * same (Copilot for Eclipse 0.22, {@code McpExtensionPointManager}: stored JSON compared as a string; signing the plugin
 * changes only the "Signed" label, not this). The Java of Eclipse lives in a folder whose name has a version
 * (JustJ), so naming it in the JSON made every Eclipse update show the server as Denied again. The command is
 * therefore a small launcher script next to the staged jar ({@link #writeLauncher}); the script names the current Java
 * and is rewritten when it changes, while the JSON names only the script and stays the same. Copilot starts it
 * through cross-spawn (a {@code .cmd} works on Windows) and stops it with {@code taskkill /t}, so the Java process
 * ends with it.
 * <p><b>Windows only for now</b> (Ayan, 26 Nov): Linux and macOS keep the direct command ({@link #jsonDirect}) until
 * the {@code .sh} launcher has run there in a real build (CP-U7 on a Linux and a macOS machine, README OPEN-54).
 * <p>Pure functions and plain files, no Eclipse and no Copilot needed (unit tests CP-U1..U7).
 *
 * @author Ayan Dutta
 */
public final class CopilotRegistration {
    /** The bridge port Eclipse listens on by default ({@code EclipseBridgeServer}, {@code DebugMcpServer}). */
    static final String DEFAULT_PORT = "8765";

    private CopilotRegistration() { }

    /** File name of the launcher script: {@code start-mcp-server.cmd} on Windows, {@code start-mcp-server.sh} elsewhere. */
    static String launcherName(boolean windows) {
        return windows ? "start-mcp-server.cmd" : "start-mcp-server.sh";
    }

    /** True on Windows (where the launcher is a {@code .cmd} file). */
    public static boolean windows() {
        return File.separatorChar == '\\';
    }

    /**
     * The launcher script's text: runs {@code java -jar jar}, passing on any arguments, with nothing else on stdout
     * (stdout carries the MCP protocol). Windows: CRLF line ends, {@code %} doubled so a path keeps it; elsewhere:
     * {@code exec} (the shell is replaced by Java), paths in single quotes.
     */
    static String launcherText(Path java, Path jar, boolean windows) {
        if (windows) {
            return "@echo off\r\n"
                    + "rem Starts the eclipse-debugger MCP server for GitHub Copilot (Agentic Debugger for Eclipse).\r\n"
                    + "rem Written by the plugin at every Eclipse start: when Eclipse's Java changes, only this file\r\n"
                    + "rem changes, so GitHub Copilot keeps its approval. Do not edit.\r\n"
                    + "\"" + batch(java.toString()) + "\" -jar \"" + batch(jar.toString()) + "\" %*\r\n";
        }
        return "#!/bin/sh\n"
                + "# Starts the eclipse-debugger MCP server for GitHub Copilot (Agentic Debugger for Eclipse).\n"
                + "# Written by the plugin at every Eclipse start: when Eclipse's Java changes, only this file\n"
                + "# changes, so GitHub Copilot keeps its approval. Do not edit.\n"
                + "exec " + shell(java.toString()) + " -jar " + shell(jar.toString()) + " \"$@\"\n";
    }

    /**
     * Writes the launcher into {@code folder} when its text differs from the file there (an unchanged file is not
     * touched, so a server Copilot is running from it is never disturbed); executable on Linux and macOS.
     * @return the launcher's path, the command Copilot is given
     */
    public static Path writeLauncher(Path folder, Path java, Path jar, boolean windows) throws IOException {
        Path launcher = folder.resolve(launcherName(windows));
        String text = launcherText(java, jar, windows);
        if (!Files.isRegularFile(launcher) || !text.equals(Files.readString(launcher, StandardCharsets.UTF_8))) {
            Files.createDirectories(folder);
            Path tmp = folder.resolve(launcher.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, launcher, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!windows) launcher.toFile().setExecutable(true, false);
        return launcher;
    }

    private static String batch(String path) {
        return path.replace("%", "%%");
    }

    private static String shell(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * @param launcher the launcher script ({@link #writeLauncher}); the only path in the JSON, so the JSON (and Copilot's
     *        approval) stays the same when Eclipse's Java changes
     * @param port this Eclipse's bridge port (environment variable {@code AGENTDEBUG_PORT}); null or the default:
     *        no {@code env} entry
     */
    public static String json(Path launcher, String port) {
        return servers(quote(launcher.toString()), "[]", port);
    }

    /**
     * The direct form, {@code java -jar <jar>}, used on Linux and macOS until the launcher is tested there: Copilot asks
     * again when Eclipse's Java changes (README FAQ).
     */
    public static String jsonDirect(Path java, Path jar, String port) {
        return servers(quote(java.toString()), "[\"-jar\"," + quote(jar.toString()) + "]", port);
    }

    private static String servers(String command, String args, String port) {
        StringBuilder server = new StringBuilder()
                .append("{\"type\":\"stdio\",\"command\":").append(command)
                .append(",\"args\":").append(args);
        if (port != null && !port.isBlank() && !DEFAULT_PORT.equals(port.trim())) {
            server.append(",\"env\":{\"AGENTDEBUG_PORT\":").append(quote(port.trim())).append('}');
        }
        server.append('}');
        return "{\"servers\":{" + quote(ClaudeIntegration.SERVER) + ":" + server + "}}";
    }

    /**
     * Where the MCP server jar is staged for Copilot: {@code ~/.eclipse-agentdebug} (the same jar Claude uses) for the
     * default port; a folder of its own for another port, so a second Eclipse (such as the test Eclipse on 21766) never
     * replaces the jar a user's normal Eclipse registered.
     */
    public static Path runtimeFolder(Path home, String port) {
        Path base = home.resolve(".eclipse-agentdebug");
        if (port == null || port.isBlank() || DEFAULT_PORT.equals(port.trim())) return base;
        return base.resolve("port-" + port.trim().replaceAll("[^0-9]", ""));
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
