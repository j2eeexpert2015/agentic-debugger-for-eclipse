package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit 5 unit tests for the setup check, OPEN-61 (SC-U1 to SC-U6). No Eclipse, no Copilot: a map stands in for
 * Copilot's settings, a temporary folder for home and projects, and a small Java program for the MCP server.
 *
 * @author Ayan Dutta
 */
class SetupCheckTest {

    @TempDir Path home;

    private static final String SERVER = CopilotAgentTools.SERVER_GROUP;
    private static final String TEMPLATE = "---\nname: Agentic Debugger\ntools:\n  - \"" + SERVER + "/eclipse_ping\"\n  - \""
            + SERVER + "/wait_for_stop\"\n  - \"read_file\"\n" + CopilotAgentTools.NO_MODEL_INVOCATION + "\n"
            + CopilotAgentTools.USER_ONLY_POLICY + "\n---\nbody\n";

    private static final class MapStore implements CopilotSettings.Store {
        final Map<String, String> values = new HashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
    }

    private MapStore approved() {
        MapStore store = new MapStore();
        store.values.put("mcpExtensionPointContrib", "{\"p\":{\"isApproved\":true,\"mcpServers\":{\"eclipse-debugger\":"
                + "{\"command\":\"" + home.resolve("start-mcp-server.cmd").toString().replace("\\", "\\\\") + "\"}}}}");
        return store;
    }

    private SetupCheck.Snapshot snapshot(boolean copilot, MapStore store, boolean agentOnly, Map<String, Path> agents,
                                         boolean claude, SetupCheck.Probe probe) {
        return new SetupCheck.Snapshot(8765, true, true, TEMPLATE, copilot, "0.22.0.v1", agentOnly, store, "p", null,
                agents, claude, claude, null, probe);
    }

    private static boolean has(List<SetupCheck.Row> rows, SetupCheck.Level level, String text) {
        for (SetupCheck.Row row : rows) if (row.level() == level && row.text().contains(text)) return true;
        return false;
    }

    @Test @DisplayName("SC-U1 the quiet check says nothing to users who use neither Copilot nor Claude Code")
    void scU1() {
        Map<String, Path> agents = Map.of("all workspaces", CopilotAgentInstaller.userAgentFile(home));
        assertEquals(List.of(), SetupCheck.rows(snapshot(false, null, true, agents, false, null)));
        assertEquals(List.of(), SetupCheck.rows(snapshot(true, new MapStore(), true, agents, false, null)),
                "Copilot installed, but no agent and our server never offered");
    }

    @Test @DisplayName("SC-U2 Copilot in use, isolated and auto-approved: OK; auto-approved without isolation: FAIL")
    void scU2() throws Exception {
        CopilotAgentInstaller.installForUser(home, TEMPLATE);
        Map<String, Path> agents = Map.of("all workspaces", CopilotAgentInstaller.userAgentFile(home));
        MapStore store = approved();
        CopilotSettings.agentModeTools(store, SERVER, List.of("eclipse_ping", "wait_for_stop"), false);
        CopilotSettings.autoApprove(store, SERVER);
        List<SetupCheck.Row> rows = SetupCheck.rows(snapshot(true, store, true, agents, false, null));
        assertEquals(SetupCheck.Level.OK, SetupCheck.worst(rows), SetupCheck.report(rows, "rows"));
        assertTrue(has(rows, SetupCheck.Level.OK, "only our agent uses them"));
        MapStore leaky = approved();
        CopilotSettings.autoApprove(leaky, SERVER);
        List<SetupCheck.Row> bad = SetupCheck.rows(snapshot(true, leaky, true, agents, false, null));
        assertEquals(SetupCheck.Level.FAIL, SetupCheck.worst(bad));
        assertTrue(has(bad, SetupCheck.Level.FAIL, "auto-approved while Agent mode"));
    }

    @Test @DisplayName("SC-U3 the agent in several places, and an edited agent without the user-only lines, are warned")
    void scU3() throws Exception {
        Path project = Files.createDirectories(home.resolve("proj"));
        CopilotAgentInstaller.installForUser(home, TEMPLATE);
        CopilotAgentInstaller.install(project, TEMPLATE);
        Map<String, Path> agents = new LinkedHashMap<>();
        agents.put("all workspaces", CopilotAgentInstaller.userAgentFile(home));
        agents.put("proj", project.resolve(CopilotAgentInstaller.AGENT_FILE));
        List<SetupCheck.Row> rows = SetupCheck.rows(snapshot(true, approved(), true, agents, false, null));
        assertTrue(has(rows, SetupCheck.Level.WARN, "installed 2 times"));
        Files.writeString(project.resolve(CopilotAgentInstaller.AGENT_FILE), "---\nname: Agentic Debugger\n---\nmine\n",
                StandardCharsets.UTF_8);
        rows = SetupCheck.rows(snapshot(true, approved(), true, agents, false, null));
        assertTrue(has(rows, SetupCheck.Level.WARN, "lacks our tool list or the user-only lines"));
    }

    @Test @DisplayName("SC-U4 a server whose tools differ from the agent's list fails, naming the differences")
    void scU4() {
        Map<String, Path> agents = Map.of();
        SetupCheck.Probe probe = new SetupCheck.Probe(List.of("eclipse_ping", "new_tool"), null);
        List<SetupCheck.Row> rows = SetupCheck.rows(snapshot(false, null, true, agents, false, probe));
        assertTrue(has(rows, SetupCheck.Level.FAIL, "not offered: [wait_for_stop]"), SetupCheck.report(rows, ""));
        assertTrue(has(rows, SetupCheck.Level.FAIL, "not in the agent: [new_tool]"));
        SetupCheck.Probe same = new SetupCheck.Probe(List.of("wait_for_stop", "eclipse_ping"), null);
        assertTrue(has(SetupCheck.rows(snapshot(false, null, true, agents, false, same)), SetupCheck.Level.OK, "match"));
    }

    @Test @DisplayName("SC-U5 tool names come only from the tool objects, not from their schemas")
    void scU5() {
        String reply = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"a_tool\",\"inputSchema\":"
                + "{\"properties\":{\"name\":{\"type\":\"string\"}}},\"description\":\"has \\\"name\\\" in it\"},"
                + "{\"description\":\"x\",\"name\":\"b_tool\"}]}}";
        assertEquals(List.of("a_tool", "b_tool"), SetupCheck.toolNames(reply));
    }

    @Test @DisplayName("SC-U6 probeServer starts a program, sends initialize and tools/list, and reads the tool names")
    void scU6() throws Exception {
        Path fake = home.resolve("FakeServer.java");
        Files.writeString(fake, """
                import java.io.*;
                public class FakeServer {
                    public static void main(String[] a) throws Exception {
                        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                        String line;
                        while ((line = in.readLine()) != null) {
                            if (line.contains("\\"id\\":1")) System.out.println("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":1,\\"result\\":{}}");
                            if (line.contains("\\"id\\":2")) System.out.println("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":2,\\"result\\":{\\"tools\\":[{\\"name\\":\\"eclipse_ping\\"},{\\"name\\":\\"port_\\" }]}}"
                                    .replace("port_", System.getenv("AGENTDEBUG_PORT")));
                            System.out.flush();
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Map<String, String> env = new HashMap<>();
        env.put("AGENTDEBUG_PORT", "x9");
        SetupCheck.Probe probe = SetupCheck.probeServer(List.of(java.toString(), fake.toString()), env, Duration.ofSeconds(60));
        assertNull(probe.error(), String.valueOf(probe.error()));
        assertEquals(List.of("eclipse_ping", "x9"), probe.tools());
        SetupCheck.Probe broken = SetupCheck.probeServer(List.of(home.resolve("missing-program").toString()), env,
                Duration.ofSeconds(5));
        assertTrue(broken.error() != null && broken.tools().isEmpty());
    }
}
