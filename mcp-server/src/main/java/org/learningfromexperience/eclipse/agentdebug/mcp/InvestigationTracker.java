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
        if (explicitId != null && !explicitId.isBlank()) return correct(explicitId);
        if (open.size() == 1) return open.iterator().next();
        if (open.size() > 1) throw new IllegalArgumentException("Several investigations are open in this session "
                + open + "; pass investigationId so the breakpoint or session is cleaned up with the right one");
        return null;
    }

    /**
     * OPEN-64 (Ayan 28 Nov 01:17, seen several times with Copilot's models): a model copies the long investigation ID
     * wrongly ("…-4f6f-bf1c?", "…-9f09-ea9ff", a character dropped). An ID this session does not know is replaced by
     * the open investigation of this session that starts with the same first 8 characters, when there is exactly one.
     * Only this session's own investigations are candidates, so nothing of another agent can be reached this way.
     * Any other ID is returned unchanged (and Eclipse refuses it as before).
     */
    public synchronized String correct(String givenId) {
        if (givenId == null) return null;
        String given = givenId.strip();
        if (given.length() < 8 || open.contains(given)) return given;
        String prefix = given.substring(0, 8).toLowerCase(java.util.Locale.ROOT);
        String match = null;
        for (String id : open) {
            if (id.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                if (match != null) return given;   // ambiguous: leave it to Eclipse to refuse
                match = id;
            }
        }
        return match == null ? given : match;
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
