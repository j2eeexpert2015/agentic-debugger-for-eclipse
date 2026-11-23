package org.learningfromexperience.eclipse.agentdebug.debug;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Keeps JDT's "Repeated exception occurrence" dialog away from agent investigations.
 *
 * <p>JDT preference {@code org.eclipse.jdt.debug.PREF_SUSPEND_ON_RECURRENCE_STRATEGY}
 * (node {@code org.eclipse.jdt.debug}) decides what happens when the same exception instance
 * hits an exception breakpoint again, for example when Spring rethrows it:
 * {@code RECURRENCE_UNCONFIGURED} (default) opens a modal dialog, {@code SKIP_RECURRENCES}
 * suspends once per instance, {@code SUSPEND_ALWAYS} suspends every time.
 *
 * <p>The modal dialog blocks JDT's debug event handling until someone clicks it. Observed on
 * 23 Nov 2026: the stop was never reported to {@code wait_for_stop}, and terminating the
 * launch left the JVM shown as running, which broke every following run.
 *
 * <p>Policy: only when the user never made a choice ({@code RECURRENCE_UNCONFIGURED}) and an
 * agent creates an exception breakpoint, set {@code SKIP_RECURRENCES}; put the original value
 * back when no agent exception breakpoint remains (and when the plugin stops). A user who chose
 * {@code SKIP_RECURRENCES} or {@code SUSPEND_ALWAYS} is never changed, because neither shows a dialog.
 *
 * @author Ayan Dutta
 */
public final class ExceptionRecurrencePolicy {

    static final String NODE = "org.eclipse.jdt.debug";
    static final String KEY = "org.eclipse.jdt.debug.PREF_SUSPEND_ON_RECURRENCE_STRATEGY";
    static final String ASK = "RECURRENCE_UNCONFIGURED";
    static final String SKIP = "SKIP_RECURRENCES";

    /** Instance-scope value before the agent changed it; null = not changed by the agent. */
    private static String saved;
    private static boolean changed;

    private ExceptionRecurrencePolicy() {
    }

    /** Effective value (instance, then default scope). */
    public static synchronized String current() {
        return Platform.getPreferencesService().getString(NODE, KEY, ASK, null);
    }

    /** Called before an agent exception breakpoint is created. Returns the value now in effect. */
    public static synchronized String beforeAgentExceptionBreakpoint() {
        if (!changed && ASK.equals(current())) {
            IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(NODE);
            saved = prefs.get(KEY, null);
            prefs.put(KEY, SKIP);
            flush(prefs);
            changed = true;
        }
        return current();
    }

    /**
     * Called after agent exception breakpoints were removed. Restores the user's original value
     * when no agent exception breakpoint is left. Returns true if it restored the value.
     */
    public static synchronized boolean restoreIfUnused(boolean agentExceptionBreakpointsRemain) {
        if (!changed || agentExceptionBreakpointsRemain) return false;
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(NODE);
        // Respect a value the user set meanwhile (anything other than the one we wrote).
        if (SKIP.equals(prefs.get(KEY, null))) {
            if (saved == null) prefs.remove(KEY); else prefs.put(KEY, saved);
            flush(prefs);
        }
        changed = false;
        saved = null;
        return true;
    }

    /** True while the agent's temporary SKIP_RECURRENCES value is in place. */
    public static synchronized boolean changedByAgent() {
        return changed;
    }

    private static void flush(IEclipsePreferences prefs) {
        try {
            prefs.flush();
        } catch (Exception ignored) {
            // The in-memory value is what JDT reads; persisting is best effort.
        }
    }
}
