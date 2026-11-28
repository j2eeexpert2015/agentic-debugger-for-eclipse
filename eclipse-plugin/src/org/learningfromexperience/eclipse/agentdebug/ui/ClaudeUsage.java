package org.learningfromexperience.eclipse.agentdebug.ui;

import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates;

/**
 * OPEN-69: what Eclipse knows about Claude Code's debugging, for the Claude Code preference page: the investigation
 * running now, else the last finished one. The last one is also stored in the preferences when it finishes, so the
 * page still knows it after an Eclipse restart. Only investigations started by Claude Code count (the MCP handshake's
 * client name). Eclipse cannot see an idle Claude Code session, so the page does not claim one.
 *
 * @author Ayan Dutta
 */
public final class ClaudeUsage {

    static final String KEY = "lastClaudeInvestigation";
    private static final String NODE = "org.learningfromexperience.eclipse.agentdebug";
    private static volatile boolean installed;

    private ClaudeUsage() { }

    /** Starts recording finished Claude Code investigations (called once at start-up). */
    public static void install() {
        if (installed) return;
        installed = true;
        ActivityStore.global().addListener(id -> {
            try {
                ActivityStore.Investigation inv = ActivityStore.global().current();
                if (inv == null || !inv.finished() || !claude(inv) || inv.finishedAt() == null) return;
                var prefs = InstanceScope.INSTANCE.getNode(NODE);
                String value = inv.finishedAt() + "\t" + ActivityPage.title(inv) + "\t" + inv.outcome();
                if (value.equals(prefs.get(KEY, null))) return;
                prefs.put(KEY, value);
                prefs.flush();
            } catch (Exception | LinkageError ignored) {
                // Only for the preference page's note; never disturbs Agent Activity.
            }
        });
    }

    /** The second line of the Claude Code page's Setup section ({@link PreferenceStates#usage}). */
    static String line() {
        ZonedDateTime now = ZonedDateTime.now();
        String lastTitle = null, lastWhen = null, lastOutcome = null;
        try {
            List<ActivityStore.Investigation> all = ActivityStore.global().investigations();
            for (int i = all.size() - 1; i >= 0; i--) {
                ActivityStore.Investigation inv = all.get(i);
                if (!claude(inv)) continue;
                if (!inv.finished()) {
                    String started = PreferenceStates.when(inv.startedAt(), now);
                    return PreferenceStates.usage(ActivityPage.title(inv),
                            started == null ? null : started.replace("today ", ""), null, null, null);
                }
                if (lastTitle == null) {
                    lastTitle = ActivityPage.title(inv);
                    lastWhen = PreferenceStates.when(inv.finishedAt(), now);
                    lastOutcome = inv.outcome();
                }
            }
            if (lastTitle == null) {
                String stored = InstanceScope.INSTANCE.getNode(NODE).get(KEY, null);
                String[] parts = stored == null ? new String[0] : stored.split("\t", 3);
                if (parts.length == 3) {
                    lastWhen = PreferenceStates.when(parts[0], now);
                    lastTitle = lastWhen == null ? null : parts[1];
                    lastOutcome = parts[2];
                }
            }
        } catch (Exception | LinkageError unknown) {
            lastTitle = null;
        }
        return PreferenceStates.usage(null, null, lastTitle, lastWhen, lastOutcome);
    }

    private static boolean claude(ActivityStore.Investigation inv) {
        return "Claude Code".equals(ActivityPage.agent(inv.client())[0]);
    }
}
