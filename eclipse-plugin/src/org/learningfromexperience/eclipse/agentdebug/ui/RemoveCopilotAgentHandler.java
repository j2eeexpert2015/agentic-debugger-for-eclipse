package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotAgentInstaller;

/**
 * OPEN-23: project menu "Agentic Debugger &gt; Remove Agentic Debugger Agent for GitHub Copilot". Removes the agent file
 * written by {@link AddCopilotAgentHandler}; asks first when the user changed it. The user-wide skill stays (other
 * projects and Claude Code may use it); the message says where to remove it.
 *
 * @author Ayan Dutta
 */
public final class RemoveCopilotAgentHandler extends AbstractHandler {

    private static final String TITLE = "Agentic Debugger for GitHub Copilot";

    @Override
    public Object execute(ExecutionEvent event) {
        var shell = HandlerUtil.getActiveShell(event);
        IProject project = AddCopilotAgentHandler.selectedProject(event);
        if (project == null || project.getLocation() == null) {
            MessageDialog.openInformation(shell, TITLE, "Select one project in the Project or Package Explorer first.");
            return null;
        }
        String where = project.getName() + "/" + CopilotAgentInstaller.AGENT_FILE;
        try {
            var folder = project.getLocation().toFile().toPath();
            CopilotAgentInstaller.Removal removal = CopilotAgentInstaller.remove(folder, false);
            if (removal == CopilotAgentInstaller.Removal.EDITED_NEEDS_CONFIRMATION) {
                boolean confirmed = MessageDialog.openQuestion(shell, TITLE, "You have changed " + where
                        + " (or wrote it yourself). Remove it anyway?");
                if (!confirmed) {
                    MessageDialog.openInformation(shell, TITLE, "Kept " + where + ".");
                    return null;
                }
                removal = CopilotAgentInstaller.remove(folder, true);
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            String message = removal == CopilotAgentInstaller.Removal.NOT_PRESENT
                    ? "No agent in " + project.getName() + "."
                    : "Removed from " + project.getName() + ".\n"
                            + (java.nio.file.Files.isRegularFile(CopilotAgentInstaller.userAgentFile(
                                    java.nio.file.Path.of(System.getProperty("user.home"))))
                                    ? "The copy for all workspaces stays."
                                    : "Start a New Chat if one is open.");
            System.out.println("[Eclipse Agentic Debugger] Copilot agent " + removal + ": " + where);
            MessageDialog.openInformation(shell, TITLE, message);
        } catch (Exception failure) {
            System.err.println("[Eclipse Agentic Debugger] Could not remove the Copilot agent: " + failure);
            MessageDialog.openError(shell, TITLE, "Could not remove the agent: " + failure.getMessage());
        }
        return null;
    }
}
