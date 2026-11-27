package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for the two GitHub Copilot settings the plugin changes, OPEN-60 (CS2-U1 to CS2-U9). The values are
 * copied from a real Copilot for Eclipse 0.22 workspace (test Eclipse, 27 Nov 2026), so the format is Copilot's own.
 *
 * @author Ayan Dutta
 */
class CopilotSettingsTest {

    private static final String SERVER = CopilotAgentTools.SERVER_GROUP;
    private static final List<String> TOOLS = List.of("eclipse_ping", "set_breakpoint", "wait_for_stop");

    /** Copilot's values on 27 Nov 2026 (prefs file, with the properties escaping removed). */
    private static final String BUILT_IN = "{\"java_debugger\":true,\"run_in_terminal\":true,\"run_subagent\":true,"
            + "\"read_file\":true}";
    private static final String REAL_MODES = "{\"agent-mode\":{\"Built-in Tools\":" + BUILT_IN + "},"
            + "\"file:///c%3A/Users/vmadmin/AppData/Local/AgenticDebugger/java-e2e-workspace/spring-boot-debugging/"
            + ".github/agents/agentic_debugger.agent.md\":{}}";
    private static final String REAL_LEGACY = "{\"Built-in Tools\":" + BUILT_IN + "}";
    private static final String REAL_APPROVE = "[\"Agentic Debugger for Eclipse IDE: eclipse-debugger\"]";
    private static final String REAL_CONTRIB = "{\"org.learningfromexperience.eclipse.agentdebug\":{\"isTrusted\":false,"
            + "\"isApproved\":true,\"pluginDisplayName\":\"Agentic Debugger for Eclipse IDE\",\"mcpServers\":"
            + "{\"eclipse-debugger\":{\"type\":\"stdio\",\"command\":\"C:\\\\Users\\\\vmadmin\\\\.eclipse-agentdebug"
            + "\\\\start-mcp-server.cmd\",\"args\":[]}}}}";

    /** A map standing in for Copilot's preference node; counts the writes. */
    private static final class MapStore implements CopilotSettings.Store {
        final Map<String, String> values = new HashMap<>();
        int writes;
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); writes++; }
    }

    private static MapStore real() {
        MapStore store = new MapStore();
        store.values.put(CopilotSettings.MODE_STATUS, REAL_MODES);
        store.values.put(CopilotSettings.LEGACY_STATUS, REAL_LEGACY);
        store.values.put(CopilotSettings.AUTO_APPROVE_SERVERS, REAL_APPROVE);
        store.values.put("mcpExtensionPointContrib", REAL_CONTRIB);
        return store;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) { return (Map<String, Object>) value; }

    @Test @DisplayName("CS2-U1 real settings: our tools off for Agent mode in both keys, everything else unchanged")
    void cs2U1() {
        MapStore store = real();
        CopilotSettings.Result r = CopilotSettings.agentModeTools(store, SERVER, TOOLS, false);
        assertEquals(CopilotSettings.Outcome.CHANGED, r.outcome());
        Map<String, Object> modes = obj(MiniJson.parse(store.get(CopilotSettings.MODE_STATUS)));
        assertEquals(List.of("agent-mode", "file:///c%3A/Users/vmadmin/AppData/Local/AgenticDebugger/java-e2e-workspace/"
                + "spring-boot-debugging/.github/agents/agentic_debugger.agent.md"), List.copyOf(modes.keySet()),
                "modes and their order kept");
        Map<String, Object> agent = obj(modes.get("agent-mode"));
        assertEquals(MiniJson.parse(BUILT_IN), agent.get("Built-in Tools"), "Copilot's own tools untouched");
        assertEquals(Map.of("eclipse_ping", false, "set_breakpoint", false, "wait_for_stop", false), agent.get(SERVER));
        Map<String, Object> legacy = obj(MiniJson.parse(store.get(CopilotSettings.LEGACY_STATUS)));
        assertEquals(Map.of("eclipse_ping", false, "set_breakpoint", false, "wait_for_stop", false), legacy.get(SERVER));
        assertTrue(store.get(CopilotSettings.MODE_STATUS).startsWith("{\"agent-mode\":{\"Built-in Tools\":{\"java_debugger\""),
                "written back in Copilot's key order");
        assertTrue(CopilotSettings.agentModeToolsOff(store, SERVER, TOOLS));
        int writes = store.writes;
        assertEquals(CopilotSettings.Outcome.ALREADY, CopilotSettings.agentModeTools(store, SERVER, TOOLS, false).outcome());
        assertEquals(writes, store.writes, "nothing written the second time");
    }

    @Test @DisplayName("CS2-U2 empty per-mode setting: the older one is copied in first, so the user's choices stay")
    void cs2U2() {
        MapStore store = new MapStore();
        store.values.put(CopilotSettings.LEGACY_STATUS, "{\"Built-in Tools\":{\"run_in_terminal\":false},"
                + "\"other: server\":{\"x\":false}}");
        assertFalse(CopilotSettings.agentModeToolsOff(store, SERVER, TOOLS));
        assertTrue(CopilotSettings.agentModeTools(store, SERVER, TOOLS, false).ok());
        Map<String, Object> agent = obj(obj(MiniJson.parse(store.get(CopilotSettings.MODE_STATUS))).get("agent-mode"));
        assertEquals(Map.of("run_in_terminal", false), agent.get("Built-in Tools"));
        assertEquals(Map.of("x", false), agent.get("other: server"), "another server's choice kept");
        assertTrue(CopilotSettings.agentModeToolsOff(store, SERVER, TOOLS));
    }

    @Test @DisplayName("CS2-U3 an unknown format is never written")
    void cs2U3() {
        for (String bad : List.of("[1,2]", "{\"agent-mode\":[]}", "{\"agent-mode\":{\"s\":{\"t\":\"yes\"}}}", "{broken")) {
            MapStore store = new MapStore();
            store.values.put(CopilotSettings.MODE_STATUS, bad);
            CopilotSettings.Result r = CopilotSettings.agentModeTools(store, SERVER, TOOLS, false);
            assertEquals(CopilotSettings.Outcome.REFUSED, r.outcome(), bad);
            assertEquals(0, store.writes, bad);
            assertFalse(CopilotSettings.agentModeToolsOff(store, SERVER, TOOLS));
        }
        assertEquals(CopilotSettings.Outcome.REFUSED,
                CopilotSettings.agentModeTools(real(), SERVER, List.of(), false).outcome(), "no tool names, no change");
    }

    @Test @DisplayName("CS2-U4 switching back on sets our tools true again")
    void cs2U4() {
        MapStore store = real();
        CopilotSettings.agentModeTools(store, SERVER, TOOLS, false);
        assertEquals(CopilotSettings.Outcome.CHANGED, CopilotSettings.agentModeTools(store, SERVER, TOOLS, true).outcome());
        Map<String, Object> agent = obj(obj(MiniJson.parse(store.get(CopilotSettings.MODE_STATUS))).get("agent-mode"));
        assertEquals(Map.of("eclipse_ping", true, "set_breakpoint", true, "wait_for_stop", true), agent.get(SERVER));
        assertFalse(CopilotSettings.agentModeToolsOff(store, SERVER, TOOLS));
    }

    @Test @DisplayName("CS2-U5 auto-approve: already there (added by hand) is left alone; added and withdrawn otherwise")
    void cs2U5() {
        MapStore real = real();
        assertEquals(CopilotSettings.Outcome.ALREADY, CopilotSettings.autoApprove(real, SERVER).outcome());
        assertEquals(0, real.writes);
        MapStore store = new MapStore();
        store.values.put(CopilotSettings.AUTO_APPROVE_SERVERS, "[\"other\"]");
        assertFalse(CopilotSettings.autoApproved(store, SERVER));
        assertEquals(CopilotSettings.Outcome.CHANGED, CopilotSettings.autoApprove(store, SERVER).outcome());
        assertEquals("[\"other\",\"" + SERVER + "\"]", store.get(CopilotSettings.AUTO_APPROVE_SERVERS));
        assertTrue(CopilotSettings.autoApproved(store, SERVER));
        assertEquals(CopilotSettings.Outcome.CHANGED, CopilotSettings.withdrawAutoApprove(store, SERVER).outcome());
        assertEquals("[\"other\"]", store.get(CopilotSettings.AUTO_APPROVE_SERVERS));
        MapStore empty = new MapStore();
        assertEquals(CopilotSettings.Outcome.CHANGED, CopilotSettings.autoApprove(empty, SERVER).outcome());
    }

    @Test @DisplayName("CS2-U6 auto-approve: Copilot compares case-insensitively, and an unknown format is refused")
    void cs2U6() {
        MapStore store = new MapStore();
        store.values.put(CopilotSettings.AUTO_APPROVE_SERVERS, "[\"" + SERVER.toUpperCase() + "\"]");
        assertTrue(CopilotSettings.autoApproved(store, SERVER));
        assertEquals(CopilotSettings.Outcome.CHANGED, CopilotSettings.withdrawAutoApprove(store, SERVER).outcome());
        MapStore bad = new MapStore();
        bad.values.put(CopilotSettings.AUTO_APPROVE_SERVERS, "{\"a\":1}");
        assertEquals(CopilotSettings.Outcome.REFUSED, CopilotSettings.autoApprove(bad, SERVER).outcome());
        assertEquals(0, bad.writes);
    }

    @Test @DisplayName("CS2-U7 Copilot's approval of our server and the command it approved")
    void cs2U7() {
        CopilotSettings.Approval a = CopilotSettings.approval(real(), "org.learningfromexperience.eclipse.agentdebug");
        assertEquals(Boolean.TRUE, a.approved());
        assertEquals("C:\\Users\\vmadmin\\.eclipse-agentdebug\\start-mcp-server.cmd", a.command());
        assertNull(CopilotSettings.approval(new MapStore(), "x").approved());
        assertNull(CopilotSettings.approval(real(), "another.plugin").approved());
    }

    @Test @DisplayName("CS2-U8 MiniJson reads and writes Copilot's JSON, escapes included, and rejects broken text")
    void cs2U8() {
        String text = "{\"a\":[true,false,null,1,2.5,\"x\\\"y\\\\z\\n\\u00e9\"],\"b\":{}}";
        Object parsed = MiniJson.parse(text);
        assertEquals("{\"a\":[true,false,null,1,2.5,\"x\\\"y\\\\z\\n\u00e9\"],\"b\":{}}", MiniJson.write(parsed));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\" 1}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(""));
    }

    @Test @DisplayName("CS2-U9 the tool names come from the real agent template")
    void cs2U9() throws Exception {
        String template = java.nio.file.Files.readString(java.nio.file.Path.of("copilot/agentic_debugger.agent.md"));
        List<String> names = CopilotAgentTools.mcpToolNames(template);
        MapStore store = real();
        assertTrue(CopilotSettings.agentModeTools(store, SERVER, names, false).ok());
        assertTrue(CopilotSettings.agentModeToolsOff(store, SERVER, names));
        assertTrue(names.contains("evaluate_expression") && names.contains("begin_debug_investigation"));
    }
}
