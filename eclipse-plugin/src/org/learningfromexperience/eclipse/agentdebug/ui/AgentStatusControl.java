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
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityPage;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityStore;

/**
 * Agent status in the Eclipse status bar (OPEN-36): "● Agent: waiting for a stop · 12 s" in blue while an
 * investigation runs (updated every second), green "finished" or amber "ended without finish" with the time taken
 * at the end, empty when no agent has debugged yet. A click opens the Agent Activity view. Registered in plugin.xml
 * ({@code toolbar:org.eclipse.ui.trim.status}).
 *
 * @author Ayan Dutta
 */
public final class AgentStatusControl extends WorkbenchWindowControlContribution {

    private static final Color RUNNING = new Color(0x1e, 0x40, 0xaf);
    private static final Color DONE = new Color(0x16, 0x65, 0x34);
    private static final Color ENDED = new Color(0x92, 0x40, 0x0e);

    private Label label;
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
        label.setText(text == null ? "" : "● " + text);
        label.setForeground(inv == null ? null : !inv.finished() ? RUNNING
                : "FINISHED".equals(inv.outcome()) ? DONE : ENDED);
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
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page != null) page.showView(AgentActivityView.ID);
        } catch (Exception unavailable) {
            System.err.println("[Eclipse Agentic Debugger] Could not open Agent Activity: " + unavailable.getMessage());
        }
    }
}
