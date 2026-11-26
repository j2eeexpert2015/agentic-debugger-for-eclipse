package org.learningfromexperience.eclipse.agentdebug.integration;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.eclipse.ui.extensions.IMcpRegistrationProvider;

/**
 * OPEN-23: offers our MCP server to GitHub Copilot for Eclipse through Copilot's extension point
 * {@code com.microsoft.copilot.eclipse.ui.mcpRegistration} (plugin.xml). Copilot calls this when it starts, then asks
 * the user once, in its own dialog, whether to allow the server. Nothing in Copilot's settings files is written.
 * <p>Loaded only by Copilot: when Copilot is not installed, the extension is ignored and this class is never loaded
 * (the import of Copilot's package is optional in MANIFEST.MF).
 * <p>Stages the MCP server jar (the same file Claude uses, see {@link CopilotRegistration#runtimeFolder}) and answers
 * with the JSON from {@link CopilotRegistration#json}. On a failure it answers null: Copilot then simply offers no
 * server, and the cause is written to the Eclipse console.
 *
 * @author Ayan Dutta
 */
public final class CopilotMcpProvider implements IMcpRegistrationProvider {

    @Override
    public CompletableFuture<String> getMcpServerConfigurations() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String port = System.getenv("AGENTDEBUG_PORT");
                Path folder = CopilotRegistration.runtimeFolder(Path.of(System.getProperty("user.home")), port);
                Path jar = new ClaudeIntegration(folder).stageRuntimeJar();
                String json = CopilotRegistration.json(ClaudeIntegration.javaExecutable(), jar, port);
                System.out.println("[Eclipse Agentic Debugger] Offered the eclipse-debugger MCP server to GitHub Copilot: "
                        + jar);
                return json;
            } catch (Exception | LinkageError failure) {
                System.err.println("[Eclipse Agentic Debugger] Could not offer the MCP server to GitHub Copilot: "
                        + failure);
                return null;
            }
        });
    }
}
