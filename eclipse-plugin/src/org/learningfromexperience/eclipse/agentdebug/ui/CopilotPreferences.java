package org.learningfromexperience.eclipse.agentdebug.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotAgentInstaller;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotSkill;

/**
 * Preferences &gt; Agentic Debugger &gt; GitHub Copilot:
 * <ul>
 *   <li>OPEN-60: "Use eclipse-debugger tools only in the Agentic Debugger agent, without confirmations" (on by default,
 *       {@link CopilotIntegration});</li>
 *   <li>OPEN-23 (d): the agent for all workspaces ({@code ~/.copilot/agents}), Install and Remove, with a warning when it
 *       is also in projects (Copilot lists every copy);</li>
 *   <li>the eclipse-debugger skill for Copilot (moved here from the main page).</li>
 * </ul>
 *
 * @author Ayan Dutta
 */
public final class CopilotPreferences extends PreferencePage implements IWorkbenchPreferencePage {

    private static final int WIDTH = 520;

    @Override public void init(IWorkbench workbench) { noDefaultAndApplyButton(); }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        if (!CopilotIntegration.copilotInstalled()) {
            wrap(root, "GitHub Copilot is not installed.");
        }
        agentOnly(root);
        userAgent(root);
        skill(root);
        return root;
    }

    /** OPEN-60. */
    private void agentOnly(Composite root) {
        heading(root, "Copilot modes");
        Button box = new Button(root, SWT.CHECK);
        box.setText("Use eclipse-debugger only in the Agentic Debugger agent, without confirmations");
        box.setSelection(CopilotIntegration.agentOnly());
        wrap(root, "Off: every Copilot mode may use it, with confirmations.\nApplies after an Eclipse restart.");
        Label result = wrap(root, "Status: " + CopilotIntegration.lastResult());
        box.addListener(SWT.Selection, event -> {
            boolean on = box.getSelection();
            result.setText("Applying...");
            Job job = Job.create("Agentic Debugger: Copilot setting", monitor -> {
                String said = CopilotIntegration.setAgentOnly(on);
                if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                    if (root.isDisposed()) return;
                    result.setText("Status: " + said + "\nRestart Eclipse to be sure.");
                    root.layout(true, true);
                });
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        });
    }

    /** OPEN-23 (d). */
    private void userAgent(Composite root) {
        heading(root, "Agent for all workspaces");
        Label status = wrap(root, userAgentStatus());
        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        Button install = new Button(buttons, SWT.PUSH);
        install.setText("Install for all workspaces");
        Button remove = new Button(buttons, SWT.PUSH);
        remove.setText("Remove for all workspaces");
        install.addListener(SWT.Selection, event -> {
            String message;
            try {
                CopilotAgentInstaller.Result r = CopilotAgentInstaller.installForUser(home(), CopilotIntegration.template());
                Path file = CopilotAgentInstaller.userAgentFile(home());
                message = (switch (r) {
                    case CREATED -> "Installed.";
                    case UPDATED -> "Updated.";
                    case UNCHANGED -> "Already up to date.";
                    case KEPT_EDITED -> "Kept your changed file; the new version is next to it (.new).";
                }) + "\nIn Copilot Chat, pick \"Agentic Debugger\" in the mode list.";
                try { CopilotSkill.install(); } catch (Exception skillFailure) { /* optional */ }
            } catch (Exception failure) {
                message = "Could not install the agent: " + failure.getMessage();
            }
            status.setText(join(message, duplicates()));
            root.layout(true, true);
        });
        remove.addListener(SWT.Selection, event -> {
            String message;
            try {
                CopilotAgentInstaller.Removal r = CopilotAgentInstaller.removeForUser(home(), false);
                if (r == CopilotAgentInstaller.Removal.EDITED_NEEDS_CONFIRMATION) {
                    boolean confirmed = MessageDialog.openQuestion(getShell(), "Agentic Debugger for GitHub Copilot",
                            "You changed the agent file. Remove it anyway?");
                    r = confirmed ? CopilotAgentInstaller.removeForUser(home(), true) : r;
                }
                message = switch (r) {
                    case REMOVED -> "Removed.";
                    case NOT_PRESENT -> "Not installed.";
                    case EDITED_NEEDS_CONFIRMATION -> "Kept your changed file.";
                };
            } catch (Exception failure) {
                message = "Could not remove the agent: " + failure.getMessage();
            }
            status.setText(join(message, duplicates()));
            root.layout(true, true);
        });
    }

    private static String join(String first, String second) {
        return second.isEmpty() ? first : first + "\n" + second;
    }

    private String userAgentStatus() {
        Path file = CopilotAgentInstaller.userAgentFile(home());
        String state;
        try {
            state = switch (CopilotAgentInstaller.state(file, CopilotIntegration.template())) {
                case MISSING -> "Not installed.";
                case CURRENT -> "Installed.";
                case OLDER -> "Installed (older version): click Install to update.";
                case EDITED -> "Installed (changed by you).";
            };
        } catch (Exception unreadable) {
            state = "Could not read " + file + ".";
        }
        return join(state, duplicates());
    }

    /** A warning when the agent is in several places: Copilot lists each copy separately. */
    static String duplicates() {
        List<String> places = new ArrayList<>();
        if (Files.isRegularFile(CopilotAgentInstaller.userAgentFile(home()))) places.add("all workspaces");
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null && Files.isRegularFile(
                        project.getLocation().toFile().toPath().resolve(CopilotAgentInstaller.AGENT_FILE))) {
                    places.add("project " + project.getName());
                }
            }
        } catch (RuntimeException | LinkageError noWorkspace) { /* outside a workspace */ }
        if (places.size() <= 1) return "";
        return "Installed in " + places.size() + " places: " + String.join(", ", places)
                + ".\nCopilot lists each copy; keep one.";
    }

    /** The eclipse-debugger skill for Copilot (before OPEN-60 on the main page). */
    private void skill(Composite root) {
        heading(root, "Skill for Copilot");
        Label status = wrap(root, CopilotSkill.status());
        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        Button install = new Button(buttons, SWT.PUSH);
        install.setText("Install skill for GitHub Copilot");
        Button remove = new Button(buttons, SWT.PUSH);
        remove.setText("Remove skill for GitHub Copilot");
        install.addListener(SWT.Selection, event -> {
            String message;
            try { message = CopilotSkill.install(); } catch (Exception error) { message = "Failed: " + error.getMessage(); }
            status.setText(message);
            root.layout(true, true);
        });
        remove.addListener(SWT.Selection, event -> {
            String message;
            try { message = CopilotSkill.remove(); } catch (Exception error) { message = "Failed: " + error.getMessage(); }
            status.setText(message);
            root.layout(true, true);
        });
    }

    static void heading(Composite root, String text) {
        Label label = new Label(root, SWT.WRAP);
        label.setText("\n" + text);
        label.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
    }

    static Label wrap(Composite root, String text) {
        Label label = new Label(root, SWT.WRAP);
        label.setText(text == null ? "" : text);
        GridData layout = new GridData(SWT.FILL, SWT.TOP, true, false);
        layout.widthHint = WIDTH;
        label.setLayoutData(layout);
        return label;
    }

    private static Path home() { return Path.of(System.getProperty("user.home")); }
}
