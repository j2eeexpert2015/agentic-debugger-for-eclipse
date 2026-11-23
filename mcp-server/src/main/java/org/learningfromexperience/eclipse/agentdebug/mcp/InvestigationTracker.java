package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The investigations this MCP session (one agent) has begun and not yet finished (CORE-17).
 *
 * <ul>
 *   <li>A breakpoint or launch request without an investigationId is attached to the session's only
 *       open investigation, so it is cleaned up with it; with several open ones it is rejected,
 *       because the owner would be a guess.</li>
 *   <li>When the agent goes away (stdin closed, process shutdown), the server finishes whatever is
 *       still open.</li>
 * </ul>
 * This is cleanup path 2 of 4; the overview of all paths is in the plugin's DebugInvestigationPolicy
 * class comment and docs/decisions.md ADR-008. Plain Java; unit-tested by {@code InvestigationTrackerTest}.
 *
 * @author Ayan Dutta
 */
public final class InvestigationTracker {

    private static final Pattern ID = Pattern.compile("\"investigationId\"\\s*:\\s*\"([^\"]+)\"");

    private final Set<String> open = new LinkedHashSet<>();

    /** Updates the open set from a bridge command and Eclipse's reply. */
    public synchronized void observe(String command, String response) {
        if (command == null || response == null) return;
        boolean success = response.contains("\"status\":\"success\"");
        if (command.startsWith("BEGIN_INVESTIGATION")) {
            Matcher m = ID.matcher(response);
            if (success && m.find()) open.add(m.group(1));
        } else if (command.startsWith("FINISH_INVESTIGATION ")) {
            String id = decode(command.substring("FINISH_INVESTIGATION ".length()).trim().split(" ")[0]);
            // Gone either way once Eclipse finished it, expired it, or never knew it.
            if (success || response.contains("expired") || response.contains("Unknown investigationId")
                    || response.contains("already completed")) open.remove(id);
        }
    }

    /**
     * The investigation a resource-creating request belongs to: the explicit ID if given; otherwise
     * the only open investigation; otherwise none (no investigation open). Throws if several are open.
     */
    public synchronized String resolve(String explicitId) {
        if (explicitId != null && !explicitId.isBlank()) return explicitId;
        if (open.size() == 1) return open.iterator().next();
        if (open.size() > 1) throw new IllegalArgumentException("Several investigations are open in this session "
                + open + "; pass investigationId so the breakpoint or session is cleaned up with the right one");
        return null;
    }

    public synchronized List<String> openIds() {
        return new ArrayList<>(open);
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return value;
        }
    }
}
