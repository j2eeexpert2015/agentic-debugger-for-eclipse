package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * Agent Activity in a window of its own (OPEN-36, default; user check 25 Nov 02:38).
 *
 * <p><b>Why a window of its own.</b> An Eclipse view, also a floating one, belongs to one perspective: when Eclipse
 * switched to the Debug perspective, the view of the Java perspective was hidden, and the Debug perspective's copy was
 * not shown reliably. This window belongs to the Eclipse window instead: it stays above Eclipse (not above other
 * programs, so the Claude terminal stays usable), moves to the background and is minimized with Eclipse, and no
 * perspective change, view or console output can hide it.
 *
 * <p><b>Place.</b> The first time at the right edge of the Eclipse window, from near the bottom to a little above the
 * middle ({@link PerspectivePolicy#floatingBounds}). Wherever the user drags or resizes it, it opens there next time
 * (saved in the plugin's preferences; a place on a screen that is no longer connected is not used).
 *
 * <p><b>Opening.</b> Opened by {@link ActivityViewOpener} when an investigation starts, without taking the keyboard
 * from the terminal: if Eclipse was the active program, the window that was active is made active again. Closed by
 * the user, it stays closed until the next investigation, or until the status bar's agent status is clicked. One
 * window per Eclipse session; the content is {@link ActivityPanel}, the same as the docked view.
 *
 * <p>Never throws to its callers: Agent Activity is a convenience and must never affect debugging.
 *
 * @author Ayan Dutta
 */
final class ActivityWindow {

    private static final String PLUGIN_ID = "org.learningfromexperience.eclipse.agentdebug";
    private static Shell shell;
    private static ActivityPanel panel;

    private ActivityWindow() {
    }

    /** True while the window is open (UI thread). */
    static boolean isOpen() {
        return shell != null && !shell.isDisposed();
    }

    /**
     * Opens the window, or shows it again when it is minimized or hidden. Call on the UI thread.
     *
     * @param window the Eclipse window it belongs to
     * @param activate true when the user asked for it (status bar click): the window then becomes active; false when
     *        it opens by itself, and the keyboard stays where it was
     */
    static void open(IWorkbenchWindow window, boolean activate) {
        try {
            if (isOpen()) {
                if (shell.getMinimized()) shell.setMinimized(false);
                if (!shell.getVisible()) shell.setVisible(true);
                if (activate) shell.setActive();
                return;
            }
            Shell owner = window.getShell();
            if (owner == null || owner.isDisposed()) return;
            Display display = owner.getDisplay();
            Shell before = display.getActiveShell();

            Shell created = new Shell(owner, SWT.TITLE | SWT.CLOSE | SWT.MAX | SWT.RESIZE | SWT.MODELESS);
            shell = created;
            created.setText("Agent Activity");
            created.setImages(owner.getImages());
            created.setLayout(new FillLayout());
            panel = new ActivityPanel(created, () -> page(window),
                    reason -> created.setText("Agent Activity - " + reason));
            int[] at = firstPlace(owner, display);
            created.setBounds(at[0], at[1], at[2], at[3]);
            created.addListener(SWT.Move, event -> remember(created));
            created.addListener(SWT.Resize, event -> remember(created));
            created.addListener(SWT.Dispose, event -> closed(created));

            created.setVisible(true);   // not open(): open() would also make it the active window
            if (activate) {
                created.setActive();
            } else if (before != null && !before.isDisposed() && before != created) {
                before.setActive();     // Eclipse was the active program: give the keyboard back to where it was
            }
        } catch (Exception | LinkageError | org.eclipse.swt.SWTError unavailable) {
            Platform.getLog(ActivityWindow.class).warn("[Agentic Debugger] Could not open the Agent Activity window: "
                    + unavailable);
        }
    }

    /** Closes the window (for example when the user switches to the docked view). */
    static void close() {
        if (isOpen()) shell.dispose();
    }

    private static IWorkbenchPage page(IWorkbenchWindow window) {
        try {
            return window.getActivePage();
        } catch (RuntimeException gone) {
            return null;
        }
    }

    /** Where the user last put it if that is still on a screen, otherwise the first place at the right edge. */
    private static int[] firstPlace(Shell owner, Display display) {
        int[] saved = PerspectivePolicy.parseBounds(preferences().get(PerspectivePolicy.ACTIVITY_WINDOW_BOUNDS, null));
        if (saved != null) {
            List<int[]> screens = new ArrayList<>();
            for (Monitor monitor : display.getMonitors()) {
                Rectangle area = monitor.getClientArea();
                screens.add(new int[] {area.x, area.y, area.width, area.height});
            }
            if (PerspectivePolicy.titleOnScreen(saved, screens)) return saved;
        }
        Rectangle eclipse = owner.getBounds();
        return PerspectivePolicy.floatingBounds(eclipse.x, eclipse.y, eclipse.width, eclipse.height);
    }

    /** Keeps the place and size while the window is in its normal state (not maximized or minimized). */
    private static void remember(Shell window) {
        if (window.isDisposed() || window.getMaximized() || window.getMinimized()) return;
        Rectangle b = window.getBounds();
        preferences().put(PerspectivePolicy.ACTIVITY_WINDOW_BOUNDS,
                PerspectivePolicy.formatBounds(b.x, b.y, b.width, b.height));
    }

    private static void closed(Shell window) {
        if (panel != null) panel.dispose();
        panel = null;
        if (shell == window) shell = null;
        try {
            preferences().flush();
        } catch (Exception notSaved) {
            // The place is kept for this Eclipse session anyway.
        }
    }

    private static IEclipsePreferences preferences() {
        return InstanceScope.INSTANCE.getNode(PLUGIN_ID);
    }
}
