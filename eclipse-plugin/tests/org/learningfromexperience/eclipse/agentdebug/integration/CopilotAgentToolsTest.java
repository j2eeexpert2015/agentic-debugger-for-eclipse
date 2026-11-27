package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for the Copilot agent's tool list and user-only lines, OPEN-60 (CT-U1 to CT-U4). Read the agent
 * template, MANIFEST.MF and the MCP server's source relative to the module folder (surefire's working directory). Run
 * by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class CopilotAgentToolsTest {

    private static final Path TEMPLATE = Path.of("copilot/agentic_debugger.agent.md");
    private static final Path SERVER = Path.of("../mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java");

    private static String read(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8); }

    @Test @DisplayName("CT-U1 the agent's tool list is exactly the server's tools plus Copilot's four read-only tools")
    void ctU1() throws Exception {
        TreeSet<String> registered = new TreeSet<>();
        Matcher m = Pattern.compile("(?:Tool\\.builder|bridgeTool)\\(\\s*\"([a-z_]+)\"").matcher(read(SERVER));
        while (m.find()) registered.add(m.group(1));
        assertTrue(registered.size() >= 30, "found the server's tools: " + registered);
        String template = read(TEMPLATE);
        assertEquals(registered, new TreeSet<>(CopilotAgentTools.mcpToolNames(template)),
                "agent template tools: must name every tool of DebugMcpServer (and nothing else) as '"
                        + CopilotAgentTools.SERVER_GROUP + "/<tool>'");
        List<String> others = new ArrayList<>();
        for (String entry : CopilotAgentTools.toolEntries(template)) {
            if (!entry.startsWith(CopilotAgentTools.SERVER_GROUP + "/")) others.add(entry);
        }
        assertEquals(CopilotAgentTools.COPILOT_READ_TOOLS, others, "only read-only Copilot tools: no terminal, no edits");
    }

    @Test @DisplayName("CT-U2 the agent has both user-only lines in its front matter")
    void ctU2() throws Exception {
        String template = read(TEMPLATE);
        assertTrue(CopilotAgentTools.userOnly(template));
        assertTrue(CopilotAgentTools.frontMatter(template).contains("name: Agentic Debugger"));
        assertFalse(CopilotAgentTools.userOnly("---\nname: x\ndisable-model-invocation: true\n---\n"),
                "both lines are needed (Copilot for Eclipse 0.22 reads only the x- line)");
    }

    @Test @DisplayName("CT-U3 the server name Copilot uses is the bundle name plus the server key")
    void ctU3() throws Exception {
        String manifest = read(Path.of("META-INF/MANIFEST.MF"));
        assertTrue(manifest.contains("Bundle-Name: " + CopilotAgentTools.PLUGIN_DISPLAY_NAME + "\n")
                || manifest.contains("Bundle-Name: " + CopilotAgentTools.PLUGIN_DISPLAY_NAME + "\r\n"),
                "MANIFEST.MF Bundle-Name must stay '" + CopilotAgentTools.PLUGIN_DISPLAY_NAME
                        + "': Copilot's tool keys and settings use it");
        assertEquals("Agentic Debugger for Eclipse IDE: eclipse-debugger", CopilotAgentTools.SERVER_GROUP);
        assertEquals(ClaudeIntegration.SERVER, CopilotAgentTools.SERVER_KEY, "the key offered to Copilot");
    }

    @Test @DisplayName("CT-U4 tool list reading: quotes removed, list ends at the next key, no front matter gives none")
    void ctU4() {
        String text = "---\nname: a\ntools:\n  - \"s: x/one\"\n  - 'two'\n  - three\nmodel: m\n---\nbody\n";
        assertEquals(List.of("s: x/one", "two", "three"), CopilotAgentTools.toolEntries(text));
        assertEquals(List.of(), CopilotAgentTools.toolEntries("no front matter\ntools:\n  - x\n"));
        assertEquals(List.of(), CopilotAgentTools.toolEntries("---\ntools:\n  - x\n"), "unclosed front matter");
    }
}
