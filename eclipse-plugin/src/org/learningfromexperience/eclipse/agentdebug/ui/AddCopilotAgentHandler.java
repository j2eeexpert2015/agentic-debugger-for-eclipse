package org.learningfromexperience.eclipse.agentdebug.ui;

import java.nio.file.Path;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotAgentInstaller;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotSkill;

/**
 * OPEN-23 step 2: project menu "Agentic Debugger &gt; Add Agentic Debugger Agent for GitHub Copilot". Writes the bundled
 * custom agent ({@code copilot/agentic_debugger.agent.md}) into the selected project through
 * {@link CopilotAgentInstaller}, installs the eclipse-debugger skill for Copilot when Copilot does not already read it
 * ({@link CopilotSkill}; user-wide, removed on the preferences page), refreshes the project and says what happened and
 * what to do next.
 * Does not need GitHub Copilot to be installed: it only writes a file.
 *
 * @author Ayan Dutta
 */
public final class AddCopilotAgentHandler extends AbstractHandler {

    private static final String TITLE = "Agentic Debugger for GitHub Copilot";

    @Override
    public Object execute(ExecutionEvent event) {
        var shell = HandlerUtil.getActiveShell(event);
        IProject project = selectedProject(event);
        if (project == null || project.getLocation() == null) {
            MessageDialog.openInformation(shell, TITLE, "Select one project in the Project or Package Explorer first.");
            return null;
        }
        try {
            String template = CopilotIntegration.template();
            Path folder = project.getLocation().toFile().toPath();
            CopilotAgentInstaller.Result result = CopilotAgentInstaller.install(folder, template);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            String where = project.getName() + "/" + CopilotAgentInstaller.AGENT_FILE;
            String next = "\n\nIn Copilot Chat, pick \"Agentic Debugger\" in the mode list."
                    + "\nNot listed? Open Configure Agents... once and close it.";
            String message = switch (result) {
                case CREATED -> "Added to " + project.getName() + "." + next;
                case UPDATED -> "Updated in " + project.getName() + "." + next;
                case UNCHANGED -> "Already up to date in " + project.getName() + "." + next;
                case KEPT_EDITED -> "Kept your changed file; the new version is next to it (.new).";
            };
            String skill;
            try {
                skill = CopilotSkill.install();
            } catch (Exception skillFailure) {
                skill = "Skill: could not be installed (" + skillFailure.getMessage() + "); the agent works without it.";
            }
            System.out.println("[Eclipse Agentic Debugger] Copilot agent " + result + ": " + where + ". " + skill);
            String duplicates = CopilotPreferences.duplicates();
            MessageDialog.openInformation(shell, TITLE, message + "\n\n" + skill
                    + (duplicates.isEmpty() ? "" : "\n\n" + duplicates));
        } catch (Exception failure) {
            System.err.println("[Eclipse Agentic Debugger] Could not add the Copilot agent: " + failure);
            MessageDialog.openError(shell, TITLE, "Could not add the agent: " + failure.getMessage());
        }
        return null;
    }

    static IProject selectedProject(ExecutionEvent event) {
        if (!(HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection)
                || selection.size() != 1) {
            return null;
        }
        IResource resource = Adapters.adapt(selection.getFirstElement(), IResource.class);
        return resource == null ? Adapters.adapt(selection.getFirstElement(), IProject.class) : resource.getProject();
    }

}
