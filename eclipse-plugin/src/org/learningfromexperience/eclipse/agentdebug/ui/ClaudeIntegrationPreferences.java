package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.core.runtime.jobs.Job;
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

/** Preferences -> Agentic Debugger: explicit, safe MCP configuration actions. */
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
        button(root, "Configure Claude Code", "configure");
        button(root, "Verify registration", "verify");
        button(root, "Remove managed registration", "remove");
        return root;
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
