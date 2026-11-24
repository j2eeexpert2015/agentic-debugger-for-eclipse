package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.PerspectiveAdapter;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;

/**
 * Opens Agent Activity when an agent investigation starts, and shows an Eclipse notification when it ends (OPEN-36),
 * once per investigation each.
 *
 * <p><b>Own window (default).</b> {@link ActivityWindow}: belongs to the Eclipse window, not to a perspective, so the
 * switch to the Debug perspective cannot hide it (user check 25 Nov 02:38: the floating view of the Java perspective
 * disappeared at the switch). A floating view this plugin made earlier ({@link ActivityViewPlacement#AUTO_FLOATED}) is
 * closed in each perspective it shows up in, so there is only one Agent Activity.
 *
 * <p><b>Docked view</b> (Preferences &gt; Agentic Debugger, own window off). The view is shown with
 * {@link IWorkbenchPage#VIEW_VISIBLE}: brought to the front of its own area, never activated, so the keyboard stays
 * where the user types (the Claude terminal). Its area: to the right of the Console (plugin.xml for a new or reset
 * perspective; {@link ActivityViewPlacement} moves it there when it shares a stack with other views, for example
 * Variables, so no other view can hide it); shown again in any perspective activated during an investigation.
 *
 * <p>In both cases: if the user closes it during an investigation, it is not opened again until the next one.
 *
 * <p>The notification (bottom right, fades out by itself) says how the investigation ended, how long it took and the
 * first line of the agent's analysis; it needs the {@code org.eclipse.jface.notifications} bundle, and without it
 * nothing is shown.
 *
 * @author Ayan Dutta
 */
public final class ActivityViewOpener {

    private static final AtomicReference<String> opened = new AtomicReference<>();
    private static final AtomicReference<String> notified = new AtomicReference<>();
    private static volatile boolean installed;

    private ActivityViewOpener() {
    }

    /** Starts listening to the store; called once at startup. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        ActivityStore.global().addListener(ActivityViewOpener::changed);
    }

    private static void changed(String investigationId) {
        ActivityStore.Investigation current = ActivityStore.global().current();
        if (current == null || !current.id().equals(investigationId)) return;
        if (current.finished()) {
            if (!investigationId.equals(notified.getAndSet(investigationId))) {
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed()) display.asyncExec(() -> notifyFinished(current));
            }
            return;
        }
        String before = opened.getAndSet(investigationId);
        if (investigationId.equals(before)) return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return;
        display.asyncExec(ActivityViewOpener::show);
    }

    private static void notifyFinished(ActivityStore.Investigation inv) {
        try {
            // The perspective may have changed during the run (for example to Debug): keep the view clear there too.
            IWorkbenchWindow window = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getActiveWorkbenchWindow() : null;
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (ownWindow()) {
                if (page != null) ActivityViewPlacement.hideAutoFloated(window, page);
            } else if (page != null && page.findView(AgentActivityView.ID) != null) {
                ActivityViewPlacement.ensureOwnArea(window);
                page.showView(AgentActivityView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            }
        } catch (Exception | LinkageError ignored) {
            // Placement is a convenience.
        }
        try {
            boolean finished = "FINISHED".equals(inv.outcome());
            String title = finished ? "Agent investigation finished" : "Agent investigation ended without a finish";
            org.eclipse.jface.notifications.NotificationPopup.forDisplay(Display.getDefault())
                    .title(title, true).text(ActivityPage.notification(inv)).open();
        } catch (Exception | LinkageError unavailable) {
            // No notification bundle, or no display: the view and the status bar still say it.
        }
    }

    private static void show() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) return;
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows.length == 0) return;
                window = windows[0];
            }
            listenToPerspectives(window);
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;
            // First the perspective the session will end up in, then the view: a switch after the view was shown
            // would leave it behind in the old perspective.
            if (switchToDebug()) {
                IPerspectiveDescriptor debug = PlatformUI.getWorkbench().getPerspectiveRegistry()
                        .findPerspectiveWithId(DEBUG_PERSPECTIVE);
                if (debug != null && (page.getPerspective() == null || !DEBUG_PERSPECTIVE.equals(page.getPerspective().getId())))
                    TerminalCarrier.switchPerspective(window, page, debug);   // the terminal views come along
            }
            if (ownWindow()) {
                ActivityViewPlacement.hideAutoFloated(window, page);
                ActivityWindow.open(window, false);
            } else {
                place(window, page);
            }
        } catch (Exception | LinkageError unavailable) {
            // Showing the view is a convenience; it must never affect debugging.
            Platform.getLog(ActivityViewOpener.class).warn("[Agentic Debugger] Could not open Agent Activity: " + unavailable);
        }
    }

    /** Creates the view in the page's perspective, puts it where nothing hides it, brings it to the front. */
    private static void place(IWorkbenchWindow window, IWorkbenchPage page) throws Exception {
        // Never activated: the keyboard stays where the user types.
        page.showView(AgentActivityView.ID, null, IWorkbenchPage.VIEW_CREATE);
        ActivityViewPlacement.ensureOwnArea(window);
        page.showView(AgentActivityView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
    }

    /** Agent Activity in its own window (default), or as a docked view: Preferences &gt; Agentic Debugger. */
    static boolean ownWindow() {
        return PerspectivePolicy.floatingActivityView(InstanceScope.INSTANCE
                .getNode("org.learningfromexperience.eclipse.agentdebug").get(PerspectivePolicy.FLOATING_ACTIVITY_VIEW, null));
    }

    /**
     * Whether the session will run in the Debug perspective: this plugin's preference, or Eclipse's own Run/Debug >
     * Perspectives setting "Open the associated perspective when an application suspends" set to Always.
     */
    static boolean switchToDebug() {
        boolean ours = PerspectivePolicy.switchToDebugPerspective(InstanceScope.INSTANCE
                .getNode("org.learningfromexperience.eclipse.agentdebug").get(PerspectivePolicy.SWITCH_TO_DEBUG_PERSPECTIVE, null));
        String eclipse = InstanceScope.INSTANCE.getNode("org.eclipse.debug.ui")
                .get("org.eclipse.debug.ui.switch_perspective_on_suspend", "prompt");
        return ours || "always".equalsIgnoreCase(eclipse);
    }

    private static final String DEBUG_PERSPECTIVE = "org.eclipse.debug.ui.DebugPerspective";
    private static final java.util.Set<IWorkbenchWindow> watched = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Whatever changes the perspective during an investigation (this plugin, Eclipse's own setting, the user): the
     * docked view is shown again in the new perspective, so it is never left behind; with the own window, an earlier
     * floating view of this plugin is closed there (the window itself is not affected by perspectives).
     */
    private static void listenToPerspectives(IWorkbenchWindow window) {
        synchronized (watched) {
            if (!watched.add(window)) return;
        }
        window.addPerspectiveListener(new PerspectiveAdapter() {
            /** Terminal views of the perspective being left, for a switch this plugin did not make itself. */
            private java.util.List<TerminalCarrier.Open> leaving = java.util.List.of();

            @Override
            public void perspectiveDeactivated(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
                ActivityStore.Investigation current = ActivityStore.global().current();
                leaving = current != null && !current.finished() ? TerminalCarrier.open(page) : java.util.List.of();
            }

            @Override
            public void perspectiveActivated(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
                java.util.List<TerminalCarrier.Open> terminals = leaving;
                leaving = java.util.List.of();
                if (!terminals.isEmpty()) {
                    // Eclipse's own switch on suspend, or the user's, during an investigation: the terminals come along.
                    Display shown = Display.getDefault();
                    if (shown != null && !shown.isDisposed())
                        shown.asyncExec(() -> TerminalCarrier.carry(window, page, terminals));
                }
                boolean ownWindow = ownWindow();
                ActivityStore.Investigation current = ActivityStore.global().current();
                if (!ownWindow && (current == null || !recent(current))) return;
                Display display = Display.getDefault();
                if (display == null || display.isDisposed()) return;
                display.asyncExec(() -> {
                    try {
                        if (ownWindow) ActivityViewPlacement.hideAutoFloated(window, page);
                        else place(window, page);
                    } catch (Exception | LinkageError ignored) {
                        // A convenience only.
                    }
                });
            }
        });
    }

    /** Running, or finished less than ten minutes ago (its result is still worth seeing). */
    private static boolean recent(ActivityStore.Investigation inv) {
        if (!inv.finished()) return true;
        try {
            return java.time.Duration.between(java.time.Instant.parse(inv.finishedAt()), java.time.Instant.now()).toMinutes() < 10;
        } catch (RuntimeException unknown) {
            return false;
        }
    }
}
