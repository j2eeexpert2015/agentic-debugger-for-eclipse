package org.learningfromexperience.eclipse.agentdebug.integration;

import java.nio.file.Path;

/**
 * OPEN-23: what GitHub Copilot for Eclipse is told about our MCP server, as the JSON its {@code mcpRegistration}
 * extension point expects: {@code {"servers":{"eclipse-debugger":{"type":"stdio","command":...,"args":[...]}}}}.
 * Copilot shows this in its own approval dialog; the user approves once (again only when the JSON changes).
 * Pure functions, no Eclipse and no Copilot needed (unit tests CP-U1..U3).
 *
 * @author Ayan Dutta
 */
public final class CopilotRegistration {
    /** The bridge port Eclipse listens on by default ({@code EclipseBridgeServer}, {@code DebugMcpServer}). */
    static final String DEFAULT_PORT = "8765";

    private CopilotRegistration() { }

    /**
     * @param java the Java executable that runs the MCP server (Eclipse's own runtime, as for Claude)
     * @param jar the staged MCP server jar
     * @param port this Eclipse's bridge port (environment variable {@code AGENTDEBUG_PORT}); null or the default:
     *        no {@code env} entry, so a user's Copilot config stays the same as long as the paths do
     */
    public static String json(Path java, Path jar, String port) {
        StringBuilder server = new StringBuilder()
                .append("{\"type\":\"stdio\",\"command\":").append(quote(java.toString()))
                .append(",\"args\":[\"-jar\",").append(quote(jar.toString())).append(']');
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
