package org.learningfromexperience.eclipse.agentdebug.ui;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import org.osgi.framework.FrameworkUtil;

/**
 * OPEN-23 step 2: project menu "Agentic Debugger &gt; Add Agentic Debugger Agent for GitHub Copilot". Writes the bundled
 * custom agent ({@code copilot/agentic_debugger.agent.md}) into the selected project through
 * {@link CopilotAgentInstaller}, refreshes the project and says what happened and what to do next.
 * Does not need GitHub Copilot to be installed: it only writes a file.
 *
 * @author Ayan Dutta
 */
public final class AddCopilotAgentHandler extends AbstractHandler {

    static final String TEMPLATE = "copilot/agentic_debugger.agent.md";
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
            String template = template();
            Path folder = project.getLocation().toFile().toPath();
            CopilotAgentInstaller.Result result = CopilotAgentInstaller.install(folder, template);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            String where = project.getName() + "/" + CopilotAgentInstaller.AGENT_FILE;
            String next = "\n\nIn Copilot Chat, choose \"Agentic Debugger\" in the mode list (next to Ask, Agent, Plan)."
                    + "\nCopilot must allow the eclipse-debugger server once: Preferences > GitHub Copilot >"
                    + " Model Context Protocol (MCP) > Edit MCP Servers from Plug-ins.";
            String message = switch (result) {
                case CREATED -> "Added " + where + "." + next;
                case UPDATED -> "Updated " + where + " to the plugin's current version." + next;
                case UNCHANGED -> where + " is already up to date." + next;
                case KEPT_EDITED -> where + " was changed by you, so it was left as it is. The plugin's current version"
                        + " is next to it as agentic_debugger.agent.md.new (Copilot does not load that file).";
            };
            System.out.println("[Eclipse Agentic Debugger] Copilot agent " + result + ": " + where);
            MessageDialog.openInformation(shell, TITLE, message);
        } catch (Exception failure) {
            System.err.println("[Eclipse Agentic Debugger] Could not add the Copilot agent: " + failure);
            MessageDialog.openError(shell, TITLE, "Could not add the agent: " + failure.getMessage());
        }
        return null;
    }

    private static IProject selectedProject(ExecutionEvent event) {
        if (!(HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection)
                || selection.size() != 1) {
            return null;
        }
        IResource resource = Adapters.adapt(selection.getFirstElement(), IResource.class);
        return resource == null ? Adapters.adapt(selection.getFirstElement(), IProject.class) : resource.getProject();
    }

    private static String template() throws Exception {
        URL entry = FrameworkUtil.getBundle(AddCopilotAgentHandler.class).getEntry(TEMPLATE);
        if (entry == null) throw new IllegalStateException(TEMPLATE + " is missing from the plugin");
        try (InputStream in = entry.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
