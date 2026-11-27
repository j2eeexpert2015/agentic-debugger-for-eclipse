package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.function.Consumer;

import jakarta.annotation.PostConstruct;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;

/**
 * Agent status in the Eclipse status bar (OPEN-36): while an investigation runs (updated every second) orange
 * "● Agent: thinking · 8 s" when the agent works without the debugger and red "● Agent: waiting for a stop · 12 s"
 * (running, paused) while it debugs, the same colours as the Agent Activity banner (OPEN-48); green "finished" or
 * yellow "ended without finish" with the time taken at the end, empty when no agent has debugged yet (OPEN-61: then
 * "Agentic Debugger: check setup" in amber when the setup check found a problem; a click opens the preferences). A click opens the Agent Activity view. Added to the bottom trim by {@link StatusTrim}.
 *
 * @author Ayan Dutta
 */
public final class AgentStatusControl {

    private static final Color THINKING = new Color(0xc2, 0x41, 0x0c);
    private static final Color DEBUGGING = new Color(0xb9, 0x1c, 0x1c);
    private static final Color DONE = new Color(0x16, 0x65, 0x34);
    private static final Color ENDED = new Color(0xa1, 0x62, 0x07);

    private Label label;
    private boolean blink;
    private final Consumer<String> listener = id -> schedule();
    private final Runnable tick = this::tick;
    private final Runnable setupListener = this::schedule;
    /** OPEN-61: true while the label shows the setup note (a click then opens the preferences page). */
    private boolean showingSetup;

    /**
     * Called by Eclipse when it shows the status-bar item ({@link StatusTrim} adds it to the window's bottom trim as an
     * E4 tool control). 27 Nov: as a {@code <control>} in a status-bar toolbar the item was only 7 px high on Windows (a
     * toolbar with no buttons is that low), so the text was drawn but cut to a strip; a tool control of its own is
     * sized from the label (one line of its font).
     */
    @PostConstruct
    public void create(Composite parent) {
        createControl(parent);
    }

    private Control createControl(Composite parent) {
        Composite box = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 4;
        box.setLayout(layout);
        label = new Label(box, SWT.NONE);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, true);
        data.widthHint = 270;   // the label keeps its width even while empty, so a later text is not clipped to nothing
        // One line of the label's font, so the item is tall enough even while it is empty at start-up.
        GC gc = new GC(label);
        try {
            data.heightHint = gc.getFontMetrics().getHeight();
        } finally {
            gc.dispose();
        }
        label.setLayoutData(data);
        label.setToolTipText("Agent Activity: click to open the view");
        label.addListener(SWT.MouseUp, event -> openView());
        ActivityStore.global().addListener(listener);
        SetupCheckRunner.addListener(setupListener);
        label.addDisposeListener(event -> {
            ActivityStore.global().removeListener(listener);
            SetupCheckRunner.removeListener(setupListener);
        });
        refresh();
        return box;
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
        String setup = text == null ? SetupCheckRunner.problemLine() : null;
        showingSetup = setup != null;
        if (showingSetup) {
            // OPEN-61: no investigation to show, but the setup check found something: a quiet note, never a popup.
            label.setText("\u26A0 Agentic Debugger: check setup");
            label.setToolTipText(setup + "\nClick to open Preferences > Agentic Debugger (Check setup).");
            label.setForeground(ENDED);
            label.getParent().layout(true);
            return;
        }
        label.setToolTipText("Agent Activity: click to open the view");
        label.setText(text == null ? "" : (running && !blink ? "\u25CB " : "\u25CF ") + text);
        String phase = ActivityPage.phase(inv);
        label.setForeground(phase == null ? null : switch (phase) {
            case ActivityPage.PHASE_THINKING -> THINKING;
            case ActivityPage.PHASE_DEBUGGING -> DEBUGGING;
            case ActivityPage.PHASE_FINISHED -> DONE;
            default -> ENDED;
        });
        label.getParent().layout(true);
        Display display = label.getDisplay();
        display.timerExec(-1, tick);
        if (inv != null && !inv.finished()) display.timerExec(1000, tick);
    }

    private void tick() {
        refresh();
    }

    private void openView() {
        if (showingSetup) {
            try {
                org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(label.getShell(),
                        "org.learningfromexperience.eclipse.agentdebug.preferences", null, null).open();
            } catch (Exception unavailable) {
                System.err.println("[Eclipse Agentic Debugger] Could not open the preferences: " + unavailable.getMessage());
            }
            return;
        }
        try {
            IWorkbenchWindow window = null;
            for (IWorkbenchWindow each : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                if (each.getShell() == label.getShell()) window = each;
            }
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
