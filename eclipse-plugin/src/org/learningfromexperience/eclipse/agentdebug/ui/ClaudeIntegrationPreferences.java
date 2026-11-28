package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates;
import org.learningfromexperience.eclipse.agentdebug.integration.SetupCheck;

/**
 * Preferences &gt; Agentic Debugger, for every agent (OPEN-69, Ayan 28 Nov: "which section is for Copilot, which for
 * Claude?"): While an agent debugs (Debug perspective, Agent Activity window), Code the agent runs (ask before
 * expressions and tracepoints) and the setup check, grouped by Eclipse, Claude Code and GitHub Copilot. Claude Code's
 * setup moved to its own page ({@link ClaudeCodePreferences}); Copilot's is {@link CopilotPreferences}. The page id and
 * every preference key are unchanged.
 *
 * @author Ayan Dutta
 */
public final class ClaudeIntegrationPreferences extends PreferencePage implements IWorkbenchPreferencePage {

    /** This page's id in plugin.xml (unchanged). */
    public static final String ID = "org.learningfromexperience.eclipse.agentdebug.preferences";

    @Override public void init(IWorkbench workbench) { noDefaultAndApplyButton(); }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        PrefUi.audience(root, "For every agent.", null);
        Link pages = new Link(root, SWT.NONE);
        pages.setText("Agent setup: <a href=\"" + ClaudeCodePreferences.ID + "\">Claude Code</a>  ·  <a href=\""
                + CopilotPreferences.ID + "\">GitHub Copilot</a>"
                + (CopilotIntegration.copilotInstalled() ? "" : " (not installed)"));
        pages.addListener(SWT.Selection, event -> open(event.text));

        IEclipsePreferences settings = InstanceScope.INSTANCE.getNode("org.learningfromexperience.eclipse.agentdebug");
        Composite debugs = PrefUi.group(root, "While an agent debugs", null);
        Button perspective = PrefUi.check(debugs, "Switch to the Debug perspective when an agent starts",
                "The Eclipse window does not come to the front, so you keep typing where you were. Off: Eclipse stays"
                        + " in the current perspective and shows the debug views there.",
                PerspectivePolicy.switchToDebugPerspective(settings.get(PerspectivePolicy.SWITCH_TO_DEBUG_PERSPECTIVE, null)));
        perspective.addListener(SWT.Selection, event -> save(settings, PerspectivePolicy.SWITCH_TO_DEBUG_PERSPECTIVE,
                perspective.getSelection()));
        Button window = PrefUi.check(debugs, "Show Agent Activity in its own window",
                "A separate window that stays on top of Eclipse; move it anywhere, even to a second screen, and it opens"
                        + " there next time. Off: a view next to the Console.",
                PerspectivePolicy.floatingActivityView(settings.get(PerspectivePolicy.FLOATING_ACTIVITY_VIEW, null)));
        window.addListener(SWT.Selection, event -> {
            if (!window.getSelection()) ActivityWindow.close();   // the docked view takes over
            save(settings, PerspectivePolicy.FLOATING_ACTIVITY_VIEW, window.getSelection());
        });

        // OPEN-58/OPEN-60: whether Eclipse asks before the agent runs code in the program; off by default.
        Composite code = PrefUi.group(root, "Code the agent runs", null);
        Button ask = PrefUi.check(code, "Ask before running expressions and tracepoints",
                "Off (default): they run at once. On: Eclipse asks you each time.", EvaluationConsentDialog.askEnabled());
        ask.addListener(SWT.Selection, event -> save(settings, EvaluationConsentDialog.ASK_BEFORE_RUNNING_CODE,
                ask.getSelection()));

        setupCheck(root);
        return root;
    }

    private void save(IEclipsePreferences settings, String key, boolean value) {
        settings.putBoolean(key, value);
        try {
            settings.flush();
            setErrorMessage(null);
        } catch (Exception error) {
            setErrorMessage("Could not save the setting: " + error.getMessage());
        }
    }

    private void open(String pageId) {
        if (getContainer() instanceof IWorkbenchPreferenceContainer container) container.openPage(pageId, null);
    }

    private Composite root;
    private Button check;
    private Button copy;
    private StyledText result;
    /** The full report of the result shown, null before the first one. */
    private String report;
    private Label checkedAt;
    /** The last check found something to fix: check again when the user comes back to this page. */
    private boolean problems;

    /**
     * OPEN-69 (Ayan 28 Nov: "after fixing, the status must update"): when this page is shown again in the same
     * Preferences window (for example after approving the server on Copilot's page) and the last check found a
     * problem, the check runs again by itself.
     */
    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible && problems && check != null && !check.isDisposed() && check.getEnabled()) runCheck();
    }

    /**
     * OPEN-61: the setup check and "Copy report". OPEN-69: runs by itself when the page opens, showing the last result
     * at once (with its time) until the fresh one is in; problems first and numbered, in colour; "Check again".
     */
    private void setupCheck(Composite parent) {
        root = parent;
        Composite group = PrefUi.group(parent, "Setup check", "Checks Eclipse, the MCP server, Claude Code and GitHub"
                + " Copilot. Copy report copies every detail (paths, versions) for a bug report.");
        Composite buttons = new Composite(group, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        buttons.setLayout(layout);
        check = new Button(buttons, SWT.PUSH);
        check.setText("Check again");
        copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy report");
        copy.setEnabled(false);
        checkedAt = new Label(group, SWT.NONE);
        checkedAt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        checkedAt.setForeground(PrefUi.colour(PreferenceStates.Tone.MUTED));
        result = new StyledText(group, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        result.setEditable(false);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.widthHint = PrefUi.WIDTH;
        data.heightHint = 170;
        result.setLayoutData(data);
        var last = SetupCheckRunner.lastDeepRows();
        if (last != null) {
            report = SetupCheckRunner.report(last);
            problems = last.stream().anyMatch(r -> r.level() == SetupCheck.Level.WARN || r.level() == SetupCheck.Level.FAIL);
            show(SetupCheck.summary(last));
            checkedAt.setText("Checked at " + SetupCheckRunner.lastDeepAt());
        }
        check.addListener(SWT.Selection, event -> runCheck());
        runCheck();
        copy.addListener(SWT.Selection, event -> {
            org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(root.getDisplay());
            try {
                clipboard.setContents(new Object[] {report},
                        new org.eclipse.swt.dnd.Transfer[] {org.eclipse.swt.dnd.TextTransfer.getInstance()});
            } finally {
                clipboard.dispose();
            }
        });
    }

    private void runCheck() {
        check.setEnabled(false);
        copy.setEnabled(false);
        String before = SetupCheckRunner.lastDeepAt();
        if (report == null) result.setText("Checking…");
        checkedAt.setText(report == null || before == null ? "Checking…" : "Checked at " + before + ". Checking again…");
        Job job = Job.create("Agentic Debugger: setup check", monitor -> {
            var rows = SetupCheckRunner.run(true);
            String full = SetupCheckRunner.report(rows);
            String summary = SetupCheck.summary(rows);
            boolean found = rows.stream().anyMatch(r -> r.level() == SetupCheck.Level.WARN || r.level() == SetupCheck.Level.FAIL);
            if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                if (result.isDisposed()) return;
                report = full;
                problems = found;
                show(summary);
                checkedAt.setText("Checked at " + SetupCheckRunner.lastDeepAt());
                check.setEnabled(true);
                copy.setEnabled(true);
            });
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /** The summary with its coloured parts ({@link SetupCheck#spans}). */
    private void show(String summary) {
        result.setText(summary);
        for (SetupCheck.Span span : SetupCheck.spans(summary)) {
            StyleRange range = new StyleRange();
            range.start = span.start();
            range.length = span.length();
            switch (span.style()) {
                case OK -> range.foreground = PrefUi.colour(PreferenceStates.Tone.OK);
                case WARN -> range.foreground = PrefUi.colour(PreferenceStates.Tone.WARN);
                case FAIL -> range.foreground = PrefUi.colour(PreferenceStates.Tone.BAD);
                case INFO -> range.foreground = new Color(29, 78, 216);
                case BOLD -> range.fontStyle = SWT.BOLD;
            }
            result.setStyleRange(range);
        }
    }
}
