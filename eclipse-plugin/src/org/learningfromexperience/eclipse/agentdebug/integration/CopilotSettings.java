package org.learningfromexperience.eclipse.agentdebug.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OPEN-60: the two GitHub Copilot settings the plugin changes, so that only the "Agentic Debugger" agent uses our tools
 * and runs them without confirmations. Both are what Copilot's own pages write (Preferences &gt; GitHub Copilot):
 * <ul>
 *   <li><b>Tools per mode</b> ({@value #MODE_STATUS}, read once when Copilot starts, and its older form
 *       {@value #LEGACY_STATUS}, which Copilot applies to its Agent mode at once): our tools switched off for Copilot's
 *       built-in Agent mode ({@value #AGENT_MODE}). Every custom agent without its own {@code tools:} list follows Agent
 *       mode, Ask mode never has MCP tools, and our agent has its own list ({@link CopilotAgentTools}), so afterwards
 *       only our agent (and an agent whose author lists our tools by name) can call them.</li>
 *   <li><b>Auto-approve</b> ({@value #AUTO_APPROVE_SERVERS}, read at every tool call): our server, so Copilot does not
 *       ask before each of our tools. Only together with the first setting: then the approval reaches no other mode.</li>
 * </ul>
 * <b>Safety rules:</b> only the entries for our server are changed, everything else is written back as it was (key
 * order kept); a value in an unknown format is never written ({@link Outcome#REFUSED}); when {@value #MODE_STATUS} is
 * still empty, Copilot falls back to {@value #LEGACY_STATUS}, so that value is copied in first and the user's choices for
 * other servers stay in force; the auto-approve entry is removed only when the plugin added it.
 * <p>Pure Java over a {@link Store} (the Eclipse side passes Copilot's preference node): unit tests CS2-U1..U10 in
 * {@code CopilotSettingsTest}, with a copy of real settings from 27 Nov 2026.
 *
 * @author Ayan Dutta
 */
public final class CopilotSettings {

    static final String MODE_STATUS = "mcpToolsModeStatus";
    static final String LEGACY_STATUS = "mcpToolsStatus";
    static final String AUTO_APPROVE_SERVERS = "autoApproveMcpServers";
    static final String AGENT_MODE = "agent-mode";

    /** Copilot's settings (or a test map). {@code get} answers null for a missing key. */
    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    public enum Outcome { CHANGED, ALREADY, REFUSED }

    /** What a step did, and a line for the user. */
    public record Result(Outcome outcome, String message) {
        public boolean ok() { return outcome != Outcome.REFUSED; }
    }

    private CopilotSettings() { }

    /**
     * Switches our tools off (or, with {@code enabled}, back on) for Copilot's built-in Agent mode, in both forms of
     * the setting. {@code tools} are our MCP tool names.
     */
    public static Result agentModeTools(Store store, String server, Collection<String> tools, boolean enabled) {
        if (tools.isEmpty()) return new Result(Outcome.REFUSED, "Not applied: agent template missing.");
        Map<String, Object> modes;
        Map<String, Object> legacy;
        try {
            legacy = toolStatus(store.get(LEGACY_STATUS));
            String raw = store.get(MODE_STATUS);
            if (blank(raw)) {
                modes = new LinkedHashMap<>();
                if (legacy != null) modes.put(AGENT_MODE, copy(legacy));
            } else {
                modes = modeStatus(raw);
            }
        } catch (IllegalArgumentException unknownFormat) {
            return new Result(Outcome.REFUSED, "Not applied: unknown Copilot settings format. Use Copilot's Tools page.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> agentMode = (Map<String, Object>) modes.computeIfAbsent(AGENT_MODE, k -> new LinkedHashMap<>());
        boolean changedModes = setAll(agentMode, server, tools, enabled);
        boolean changedLegacy = false;
        if (legacy == null) legacy = new LinkedHashMap<>();
        changedLegacy = setAll(legacy, server, tools, enabled);
        if (!changedModes && !changedLegacy) {
            return new Result(Outcome.ALREADY, enabled ? "All Copilot modes may use our tools."
                    : "Only Agentic Debugger uses our tools.");
        }
        if (changedModes) store.put(MODE_STATUS, MiniJson.write(modes));
        if (changedLegacy) store.put(LEGACY_STATUS, MiniJson.write(legacy));
        return new Result(Outcome.CHANGED, enabled ? "All Copilot modes may use our tools."
                : "Only Agentic Debugger uses our tools.");
    }

    /** True when every one of our tools is off for Agent mode in {@value #MODE_STATUS} (what Copilot reads at start). */
    public static boolean agentModeToolsOff(Store store, String server, Collection<String> tools) {
        try {
            String raw = store.get(MODE_STATUS);
            Map<String, Object> agentMode;
            if (blank(raw)) {
                agentMode = toolStatus(store.get(LEGACY_STATUS));
            } else {
                Object value = modeStatus(raw).get(AGENT_MODE);
                agentMode = value instanceof Map<?, ?> map ? cast(map) : null;
            }
            if (agentMode == null || !(agentMode.get(server) instanceof Map<?, ?> ours)) return false;
            for (String tool : tools) if (!Boolean.FALSE.equals(ours.get(tool))) return false;
            return !tools.isEmpty();
        } catch (IllegalArgumentException unknownFormat) {
            return false;
        }
    }

    /**
     * Adds our server to Copilot's auto-approve list. The answer's outcome is {@link Outcome#CHANGED} only when the
     * plugin added it (the caller remembers that, so {@link #withdrawAutoApprove} removes only its own entry).
     */
    public static Result autoApprove(Store store, String server) {
        List<Object> list;
        try {
            list = stringList(store.get(AUTO_APPROVE_SERVERS));
        } catch (IllegalArgumentException unknownFormat) {
            return new Result(Outcome.REFUSED, "Not applied: unknown auto-approve format.");
        }
        if (contains(list, server)) return new Result(Outcome.ALREADY, "No confirmations.");
        list.add(server);
        store.put(AUTO_APPROVE_SERVERS, MiniJson.write(list));
        return new Result(Outcome.CHANGED, "No confirmations.");
    }

    /** Removes our server from the auto-approve list; the caller asks for this only when the plugin added it. */
    public static Result withdrawAutoApprove(Store store, String server) {
        List<Object> list;
        try {
            list = stringList(store.get(AUTO_APPROVE_SERVERS));
        } catch (IllegalArgumentException unknownFormat) {
            return new Result(Outcome.REFUSED, "Not applied: unknown auto-approve format.");
        }
        boolean removed = list.removeIf(entry -> entry instanceof String s && s.toLowerCase(Locale.ROOT)
                .equals(server.toLowerCase(Locale.ROOT)));
        if (!removed) return new Result(Outcome.ALREADY, "Copilot asks before each tool.");
        store.put(AUTO_APPROVE_SERVERS, MiniJson.write(list));
        return new Result(Outcome.CHANGED, "Copilot asks before each tool.");
    }

    /** True when our server is in Copilot's auto-approve list. */
    public static boolean autoApproved(Store store, String server) {
        try {
            return contains(stringList(store.get(AUTO_APPROVE_SERVERS)), server);
        } catch (IllegalArgumentException unknownFormat) {
            return false;
        }
    }

    /**
     * Whether Copilot has approved our MCP server ({@code mcpExtensionPointContrib}, written by Copilot's "Confirm MCP
     * Server Registration" dialog): TRUE, FALSE, or null when Copilot has not seen it yet or the format is unknown.
     * Also gives the stored command, to compare with the launcher.
     */
    public record Approval(Boolean approved, String command) { }

    public static Approval approval(Store store, String pluginId) {
        try {
            String raw = store.get("mcpExtensionPointContrib");
            if (blank(raw)) return new Approval(null, null);
            if (!(MiniJson.parse(raw) instanceof Map<?, ?> all) || !(all.get(pluginId) instanceof Map<?, ?> ours)) {
                return new Approval(null, null);
            }
            Boolean approved = ours.get("isApproved") instanceof Boolean b ? b : null;
            String command = null;
            if (ours.get("mcpServers") instanceof Map<?, ?> servers
                    && servers.get(CopilotAgentTools.SERVER_KEY) instanceof Map<?, ?> server
                    && server.get("command") instanceof String c) command = c;
            return new Approval(approved, command);
        } catch (IllegalArgumentException unknownFormat) {
            return new Approval(null, null);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Sets our tools to {@code enabled} in a server-to-tools map; true when something changed. */
    private static boolean setAll(Map<String, Object> servers, String server, Collection<String> tools, boolean enabled) {
        Object existing = servers.get(server);
        Map<String, Object> ours = existing instanceof Map<?, ?> map ? cast(map) : new LinkedHashMap<>();
        boolean changed = !(existing instanceof Map<?, ?>);
        for (String tool : tools) {
            if (!Boolean.valueOf(enabled).equals(ours.get(tool))) {
                ours.put(tool, enabled);
                changed = true;
            }
        }
        servers.put(server, ours);
        return changed;
    }

    /** {@code {mode: {server: {tool: boolean}}}}; throws on any other shape. */
    private static Map<String, Object> modeStatus(String raw) {
        if (!(MiniJson.parse(raw) instanceof Map<?, ?> modes)) throw new IllegalArgumentException("not an object");
        for (Object mode : modes.values()) checkToolStatus(mode);
        return cast(modes);
    }

    /** {@code {server: {tool: boolean}}}, or null when blank; throws on any other shape. */
    private static Map<String, Object> toolStatus(String raw) {
        if (blank(raw)) return null;
        Object value = MiniJson.parse(raw);
        checkToolStatus(value);
        return cast((Map<?, ?>) value);
    }

    private static void checkToolStatus(Object value) {
        if (!(value instanceof Map<?, ?> servers)) throw new IllegalArgumentException("a mode is not an object");
        for (Object tools : servers.values()) {
            if (!(tools instanceof Map<?, ?> map)) throw new IllegalArgumentException("a server is not an object");
            for (Object on : map.values()) {
                if (!(on instanceof Boolean)) throw new IllegalArgumentException("a tool is not true or false");
            }
        }
    }

    private static List<Object> stringList(String raw) {
        if (blank(raw)) return new ArrayList<>();
        if (!(MiniJson.parse(raw) instanceof List<?> list)) throw new IllegalArgumentException("not a list");
        for (Object entry : list) if (!(entry instanceof String)) throw new IllegalArgumentException("not a string");
        return new ArrayList<>(list);
    }

    private static boolean contains(List<Object> list, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (Object entry : list) if (entry instanceof String s && s.toLowerCase(Locale.ROOT).equals(lower)) return true;
        return false;
    }

    private static Map<String, Object> copy(Map<String, Object> servers) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : servers.entrySet()) {
            copy.put(e.getKey(), e.getValue() instanceof Map<?, ?> map ? new LinkedHashMap<>(cast(map)) : e.getValue());
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
