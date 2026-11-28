package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Action;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Done;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Registration;

/**
 * OPEN-69: Preferences &gt; Agentic Debugger &gt; Claude Code, for Claude Code only (before on the main page).
 * <ul>
 *   <li><b>Setup</b>: Claude Code's entry for our server, from {@code claude mcp get eclipse-debugger}
 *       ({@link ClaudeIntegration#registration()}), checked in the background when the page opens and on Check again;
 *       Set up ({@code configure}), Update ({@code migrateExisting}, an entry of an older version), Remove (asks first),
 *       and, when set up, what Eclipse knows about Claude Code's debugging ({@link ClaudeUsage}).</li>
 *   <li><b>Skill</b>: {@code ~/.claude/skills/eclipse-debugger}; Install and Update ({@link ClaudeIntegration#installSkill()}).</li>
 * </ul>
 * Every case, and which button is enabled in it, is {@link PreferenceStates} (tests PS-U1..U8). One action at a time:
 * while one runs, nothing on the page is enabled.
 *
 * @author Ayan Dutta
 */
public final class ClaudeCodePreferences extends PreferencePage implements IWorkbenchPreferencePage {

    /** This page's id in plugin.xml. */
    public static final String ID = "org.learningfromexperience.eclipse.agentdebug.preferences.claude";

    private Composite root;
    private PrefUi.Section setup;
    private PrefUi.Section skill;
    private final AtomicBoolean busy = new AtomicBoolean();
    private Registration registration = Registration.CHECKING;
    private String detail;
    private Done done = Done.NOTHING;
    private PreferenceStates.SkillState skillState = PreferenceStates.SkillState.WORKING;

    @Override public void init(IWorkbench workbench) { noDefaultAndApplyButton(); }

    @Override
    protected Control createContents(Composite parent) {
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        PrefUi.audience(root, "For Claude Code only.", "Lets Claude Code in your terminal use the Eclipse debugger.");

        Map<Action, String> setupButtons = new LinkedHashMap<>();
        setupButtons.put(Action.SET_UP, "Set up");
        setupButtons.put(Action.UPDATE, "Update");
        setupButtons.put(Action.CHECK, "Check again");
        setupButtons.put(Action.REMOVE, "Remove");
        setup = new PrefUi.Section(PrefUi.group(root, "Setup", "Adds our MCP server to Claude Code for your user"
                + " (entry 'eclipse-debugger'). An entry that is not ours is never changed. Checked with"
                + " 'claude mcp get eclipse-debugger'."), setupButtons, this::setupAction);

        Map<Action, String> skillButtons = new LinkedHashMap<>();
        skillButtons.put(Action.INSTALL, "Install");
        skillButtons.put(Action.UPDATE, "Update");
        skill = new PrefUi.Section(PrefUi.group(root, "Skill", "Tells the agent how to debug with these tools. Kept in"
                + " ~/.claude/skills/eclipse-debugger. GitHub Copilot reads it there too. A copy you changed is never"
                + " overwritten."), skillButtons, this::skillAction);
        check(Done.NOTHING);
        return root;
    }

    /** Checks the entry and the skill in the background; the page shows "Checking…" meanwhile. */
    private void check(Done after) {
        run(Registration.CHECKING, () -> {
            ClaudeIntegration.RegistrationCheck r = new ClaudeIntegration().registration();
            return new Object[] {r.state(), r.detail(), after};
        });
    }

    private void setupAction(Action action) {
        switch (action) {
            case CHECK -> check(Done.NOTHING);
            case SET_UP -> run(Registration.WORKING, () -> {
                try {
                    new ClaudeIntegration().configure();
                } catch (ClaudeIntegration.MigrationRequiredException older) {
                    // Found an older entry meanwhile: show it; Update is the button for it.
                } catch (Exception failure) {
                    return new Object[] {Registration.FAILED, "Set up failed: " + message(failure), Done.NOTHING};
                }
                ClaudeIntegration.RegistrationCheck r = new ClaudeIntegration().registration();
                return new Object[] {r.state(), r.detail(), Done.SET_UP};
            });
            case UPDATE -> run(Registration.WORKING, () -> {
                try {
                    new ClaudeIntegration().migrateExisting();
                } catch (Exception failure) {
                    return new Object[] {Registration.FAILED, "Update failed: " + message(failure), Done.NOTHING};
                }
                ClaudeIntegration.RegistrationCheck r = new ClaudeIntegration().registration();
                return new Object[] {r.state(), r.detail(), Done.SET_UP};
            });
            case REMOVE -> {
                if (!MessageDialog.openConfirm(getShell(), "Remove from Claude Code",
                        "Remove the Agentic Debugger entry from Claude Code?\n\nYour other MCP entries are not changed."
                                + " You can set it up again here at any time.")) return;
                run(Registration.WORKING, () -> {
                    try {
                        new ClaudeIntegration().remove();
                    } catch (Exception failure) {
                        return new Object[] {Registration.FAILED, "Remove failed: " + message(failure), Done.NOTHING};
                    }
                    ClaudeIntegration.RegistrationCheck r = new ClaudeIntegration().registration();
                    return new Object[] {r.state(), r.detail(), Done.REMOVED};
                });
            }
            default -> { }
        }
    }

    private void skillAction(Action action) {
        if (action != Action.INSTALL && action != Action.UPDATE) return;
        // The Setup section keeps what it showed; it is only disabled while the skill is written.
        Registration before = registration;
        String beforeDetail = detail;
        Done beforeDone = done;
        run(Registration.WORKING, () -> {
            try {
                new ClaudeIntegration().installSkill();
            } catch (Exception failure) {
                return new Object[] {before, beforeDetail, beforeDone, "The skill could not be written: " + message(failure)};
            }
            return new Object[] {before, beforeDetail, beforeDone};
        });
    }

    /**
     * Runs {@code work} in the background (it runs the {@code claude} command or writes files), with nothing enabled
     * meanwhile; then shows its result {@code {Registration, detail, Done[, error to show]}} and the skill's state, read
     * again.
     */
    private void run(Registration meanwhile, Supplier<Object[]> work) {
        if (!busy.compareAndSet(false, true)) return;
        registration = meanwhile;
        skillState = PreferenceStates.SkillState.WORKING;
        draw();
        Job job = Job.create("Agentic Debugger: Claude Code", monitor -> {
            Object[] result;
            try {
                result = work.get();
            } catch (RuntimeException | LinkageError failure) {
                result = new Object[] {Registration.FAILED, message(failure), Done.NOTHING};
            }
            PreferenceStates.SkillState s = PreferenceStates.skillState(ClaudeIntegration.claudeSkillFolder(),
                    ClaudeIntegration.bundledSkillVersion());
            Object[] r = result;
            if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                busy.set(false);
                if (root.isDisposed()) return;
                registration = (Registration) r[0];
                detail = (String) r[1];
                done = (Done) r[2];
                skillState = s;
                draw();
                if (r.length > 3 && r[3] != null) MessageDialog.openError(getShell(), "Claude Code skill", (String) r[3]);
            });
            else busy.set(false);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void draw() {
        boolean working = busy.get();
        String usage = registration == Registration.CURRENT ? ClaudeUsage.line() : null;
        setup.show(PreferenceStates.claudeSetup(registration, detail, done, usage));
        String installed = PreferenceStates.version(ClaudeIntegration.claudeSkillFolder().resolve("SKILL.md"));
        PreferenceStates.View s = PreferenceStates.claudeSkill(working ? PreferenceStates.SkillState.WORKING : skillState,
                installed, ClaudeIntegration.bundledSkillVersion());
        skill.show(s);
    }

    private static String message(Throwable failure) {
        String m = failure.getMessage();
        return m == null || m.isBlank() ? failure.getClass().getSimpleName() : m;
    }
}
