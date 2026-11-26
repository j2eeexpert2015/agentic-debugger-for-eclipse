package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotSkill;

/** Preferences -> Agentic Debugger: explicit, safe MCP configuration actions.
 *
 * @author Ayan Dutta
 */
public final class ClaudeIntegrationPreferences extends PreferencePage implements IWorkbenchPreferencePage {
    private Label status;
    @Override public void init(IWorkbench workbench) { }
    @Override protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        Label description = new Label(root, SWT.WRAP);
        description.setText("Register the bundled Agentic Debugger MCP server with Claude Code. "
                + "Only the user-scoped 'eclipse-debugger' entry is managed. "
                + "Existing registrations are never silently replaced.");
        description.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        status = new Label(root, SWT.WRAP);
        status.setText("Click Verify to check Claude Code registration.");
        status.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Button automaticPerspective = new Button(root, SWT.CHECK);
        automaticPerspective.setText("Switch to the Debug perspective for agent-controlled sessions");
        var settings = InstanceScope.INSTANCE.getNode("org.learningfromexperience.eclipse.agentdebug");
        automaticPerspective.setSelection(PerspectivePolicy.switchToDebugPerspective(
                settings.get(PerspectivePolicy.SWITCH_TO_DEBUG_PERSPECTIVE, null)));
        Label perspectiveHelp = new Label(root, SWT.WRAP);
        perspectiveHelp.setText("On (default): when an agent investigation starts, Eclipse switches to the Debug "
                + "perspective first and then opens Agent Activity there, so the view is not left behind by a later "
                + "switch. The Eclipse window is not brought to the front, so the keyboard stays in your terminal. "
                + "Off: Eclipse stays in the current perspective; on a stop, Variables, Breakpoints, Expressions, Debug "
                + "and Console are shown there without taking the keyboard focus. "
                + "Eclipse's own Run/Debug > Perspectives settings can still ask to switch; Agent Activity follows.");
        GridData helpLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        helpLayout.widthHint = 500;
        perspectiveHelp.setLayoutData(helpLayout);
        automaticPerspective.addListener(SWT.Selection, event -> {
            settings.putBoolean(PerspectivePolicy.SWITCH_TO_DEBUG_PERSPECTIVE, automaticPerspective.getSelection());
            try { settings.flush(); } catch (Exception error) {
                status.setText("Failed to save perspective preference: " + error.getMessage());
            }
        });
        Button floatingActivity = new Button(root, SWT.CHECK);
        floatingActivity.setText("Show Agent Activity in its own window");
        floatingActivity.setSelection(PerspectivePolicy.floatingActivityView(
                settings.get(PerspectivePolicy.FLOATING_ACTIVITY_VIEW, null)));
        Label floatingHelp = new Label(root, SWT.WRAP);
        floatingHelp.setText("On (default): Agent Activity opens in a window of its own at the right of Eclipse. It stays "
                + "above Eclipse in every perspective; no perspective change, view or console output can hide it. Drag or "
                + "resize it anywhere, even to a second screen, and it opens there next time. "
                + "Off: it opens as a view in an area of its own next to the Console of each perspective.");
        GridData floatingLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        floatingLayout.widthHint = 500;
        floatingHelp.setLayoutData(floatingLayout);
        floatingActivity.addListener(SWT.Selection, event -> {
            settings.putBoolean(PerspectivePolicy.FLOATING_ACTIVITY_VIEW, floatingActivity.getSelection());
            if (!floatingActivity.getSelection()) ActivityWindow.close();   // the docked view takes over
            try { settings.flush(); } catch (Exception error) {
                status.setText("Failed to save the Agent Activity preference: " + error.getMessage());
            }
        });
        button(root, "Configure Claude Code", "configure");
        button(root, "Verify registration", "verify");
        button(root, "Remove managed registration", "remove");
        copilotSkill(root);
        return root;
    }

    /**
     * OPEN-23: the eclipse-debugger skill for GitHub Copilot. Installed by "Add Agentic Debugger Agent for GitHub
     * Copilot" (project menu) or here; removed only here. Never installed automatically.
     */
    private void copilotSkill(Composite root) {
        Label heading = new Label(root, SWT.WRAP);
        heading.setText("\nGitHub Copilot skill (eclipse-debugger)");
        Label skillStatus = new Label(root, SWT.WRAP);
        skillStatus.setText(CopilotSkill.status());
        GridData statusLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        statusLayout.widthHint = 500;
        skillStatus.setLayoutData(statusLayout);
        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        Button install = new Button(buttons, SWT.PUSH);
        install.setText("Install skill for GitHub Copilot");
        Button remove = new Button(buttons, SWT.PUSH);
        remove.setText("Remove skill for GitHub Copilot");
        install.addListener(SWT.Selection, event -> {
            String message;
            try { message = CopilotSkill.install(); } catch (Exception error) { message = "Failed: " + error.getMessage(); }
            skillStatus.setText(message);
            root.layout(true, true);
        });
        remove.addListener(SWT.Selection, event -> {
            String message;
            try { message = CopilotSkill.remove(); } catch (Exception error) { message = "Failed: " + error.getMessage(); }
            skillStatus.setText(message);
            root.layout(true, true);
        });
    }
    private void button(Composite root, String title, String action) {
        Button button = new Button(root, SWT.PUSH); button.setText(title);
        button.addListener(SWT.Selection, event -> {
            button.setEnabled(false);
            status.setText("Working...");
            Job job = Job.create("Agentic Debugger: " + action, monitor -> {
                String message;
                try {
                    ClaudeIntegration integration = new ClaudeIntegration();
                    message = switch(action) {
                        case "configure" -> integration.configure();
                        case "verify" -> integration.status();
                        case "remove" -> integration.remove();
                        default -> throw new IllegalArgumentException(action);
                    };
                } catch (ClaudeIntegration.MigrationRequiredException migration) {
                    String previous = migration.getMessage();
                    if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                        if (root.isDisposed()) return;
                        boolean approved = MessageDialog.openQuestion(root.getShell(),
                                "Update Agentic Debugger Registration",
                                previous + "\n\nReplace ONLY the existing user-scoped eclipse-debugger entry "
                                + "with the MCP server bundled with this Eclipse plugin? "
                                + "All other MCP entries will remain untouched.");
                        if (approved) {
                            status.setText("Updating previous registration...");
                            root.layout(true, true);
                            Job update = Job.create("Update Agentic Debugger registration", monitor2 -> {
                                String response;
                                try { response = new ClaudeIntegration().migrateExisting(); }
                                catch (Exception error) { response = "Failed: " + error.getMessage(); }
                                String finalResponse = response;
                                if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                                    if (!root.isDisposed()) { status.setText(finalResponse); root.layout(true, true); }
                                });
                                return Status.OK_STATUS;
                            });
                            update.setSystem(true); update.schedule();
                        } else {
                            status.setText("Migration cancelled; existing registration unchanged.");
                            root.layout(true, true);
                        }
                    });
                    message = null;
                } catch (Exception error) { message = "Failed: " + error.getMessage(); }
                String result = message;
                if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                    if (!root.isDisposed()) {
                        if (result != null) status.setText(result);
                        button.setEnabled(true);
                        root.layout(true, true);
                    }
                });
                return Status.OK_STATUS;
            });
            job.setSystem(true); job.schedule();
        });
    }
    @Override protected void performDefaults() { super.performDefaults(); }
}
