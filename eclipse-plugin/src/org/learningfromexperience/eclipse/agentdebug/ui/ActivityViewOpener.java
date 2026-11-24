package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityPage;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityStore;

/**
 * Opens the Agent Activity view when an agent investigation starts, and shows an Eclipse notification when it ends
 * (OPEN-36), once per investigation each.
 *
 * <p>The view is shown with {@link IWorkbenchPage#VIEW_VISIBLE}: brought to the front of its own area, never
 * activated, so the keyboard stays where the user types (the Claude terminal). Its area: to the right of the Console
 * (plugin.xml for a new or reset perspective; {@link ActivityViewPlacement} moves it there when it shares a stack with
 * other views, for example Variables, so no other view can hide it). If the user closes the view during an investigation, it is not opened
 * again until the next investigation.
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
            if (page != null && page.findView(AgentActivityView.ID) != null) {
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
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;
            // Create it, give it an area of its own if it shares one with Variables or the Console, then bring it to
            // the front of that area. Never activated: the keyboard stays where the user types.
            page.showView(AgentActivityView.ID, null, IWorkbenchPage.VIEW_CREATE);
            ActivityViewPlacement.ensureOwnArea(window);
            page.showView(AgentActivityView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
        } catch (Exception unavailable) {
            // Showing the view is a convenience; it must never affect debugging.
            System.err.println("[Eclipse Agentic Debugger] Could not open Agent Activity: " + unavailable.getMessage());
        }
    }
}
