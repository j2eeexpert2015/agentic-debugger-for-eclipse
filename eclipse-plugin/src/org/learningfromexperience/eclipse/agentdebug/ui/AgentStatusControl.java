package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;
import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;

/**
 * Agent status in the Eclipse status bar (OPEN-36): while an investigation runs (updated every second) orange
 * "● Agent: thinking · 8 s" when the agent works without the debugger and red "● Agent: waiting for a stop · 12 s"
 * (running, paused) while it debugs, the same colours as the Agent Activity banner (OPEN-48); green "finished" or
 * yellow "ended without finish" with the time taken at the end, empty when no agent has debugged yet. A click opens the Agent Activity view. Registered in plugin.xml
 * ({@code toolbar:org.eclipse.ui.trim.status}).
 *
 * @author Ayan Dutta
 */
public final class AgentStatusControl extends WorkbenchWindowControlContribution {

    private static final Color THINKING = new Color(0xc2, 0x41, 0x0c);
    private static final Color DEBUGGING = new Color(0xb9, 0x1c, 0x1c);
    private static final Color DONE = new Color(0x16, 0x65, 0x34);
    private static final Color ENDED = new Color(0xa1, 0x62, 0x07);

    private Label label;
    private boolean blink;
    private final Consumer<String> listener = id -> schedule();
    private final Runnable tick = this::tick;

    @Override
    protected Control createControl(Composite parent) {
        Composite box = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 4;
        box.setLayout(layout);
        label = new Label(box, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));
        label.setToolTipText("Agent Activity: click to open the view");
        label.addListener(SWT.MouseUp, event -> openView());
        ActivityStore.global().addListener(listener);
        label.addDisposeListener(event -> ActivityStore.global().removeListener(listener));
        refresh();
        return box;
    }

    @Override
    protected int computeWidth(Control control) {
        return 280;
    }

    private void schedule() {
        Label current = label;
        if (current == null || current.isDisposed()) return;
        Display display = current.getDisplay();
        if (!display.isDisposed()) display.asyncExec(this::refresh);
    }

    /** Redraws the text; while an investigation runs, again in one second (for the time). */
    private void refresh() {
        if (label == null || label.isDisposed()) return;
        ActivityStore.Investigation inv = ActivityStore.global().current();
        String text = ActivityPage.statusBar(inv, System.currentTimeMillis());
        // While running, the dot blinks (filled, hollow) every second, like the view's live dot.
        boolean running = inv != null && !inv.finished();
        blink = running && !blink;
        label.setText(text == null ? "" : (running && !blink ? "\u25CB " : "\u25CF ") + text);
        String phase = ActivityPage.phase(inv);
        label.setForeground(phase == null ? null : switch (phase) {
            case ActivityPage.PHASE_THINKING -> THINKING;
            case ActivityPage.PHASE_DEBUGGING -> DEBUGGING;
            case ActivityPage.PHASE_FINISHED -> DONE;
            default -> ENDED;
        });
        label.getParent().layout();
        Display display = label.getDisplay();
        display.timerExec(-1, tick);
        if (inv != null && !inv.finished()) display.timerExec(1000, tick);
    }

    private void tick() {
        refresh();
    }

    private void openView() {
        try {
            IWorkbenchWindow window = getWorkbenchWindow();
            if (window != null && ActivityViewOpener.ownWindow()) {
                ActivityWindow.open(window, true);       // the user asked for it: it becomes the active window
                return;
            }
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page == null) return;
            page.showView(AgentActivityView.ID, null, IWorkbenchPage.VIEW_CREATE);
            ActivityViewPlacement.ensureOwnArea(window);
            page.showView(AgentActivityView.ID);
        } catch (Exception unavailable) {
            System.err.println("[Eclipse Agentic Debugger] Could not open Agent Activity: " + unavailable.getMessage());
        }
    }
}
