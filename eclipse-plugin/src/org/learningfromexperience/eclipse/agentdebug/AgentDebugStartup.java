package org.learningfromexperience.eclipse.agentdebug;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IStartup;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;
import org.eclipse.jface.dialogs.MessageDialog;


import org.learningfromexperience.eclipse.agentdebug.bridge.EclipseBridgeServer;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;

/**
 * Starts the local debugger bridge when the Eclipse workbench opens.
 *
 * @author Ayan Dutta
 */
public class AgentDebugStartup implements IStartup {

    private static final String PLUGIN_ID =
            "org.learningfromexperience.eclipse.agentdebug";

    private static void offerFirstRunConfiguration() {
        var prefs = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
        if (prefs.getBoolean("setupPromptShown", false)) return;
        Display.getDefault().asyncExec(() -> {
            // Mark prompt seen before showing it; users can always configure later from Preferences.
            prefs.putBoolean("setupPromptShown", true);
            try { prefs.flush(); } catch (Exception e) { /* non-fatal preference persistence */ }
            boolean configure = MessageDialog.openQuestion(Display.getDefault().getActiveShell(),
                    "Agentic Debugger for Eclipse IDE",
                    "Agentic Debugger is installed. Configure the bundled MCP server for Claude Code now? "
                    + "Your other MCP registrations will not be modified.");
            if (configure) {
                Job configureJob = Job.create("Configure Claude Code MCP integration", monitor -> {
                    String result;
                    try { result = new ClaudeIntegration().configure(); }
                    catch (ClaudeIntegration.MigrationRequiredException migration) {
                        Display.getDefault().asyncExec(() -> {
                            boolean approved = MessageDialog.openQuestion(Display.getDefault().getActiveShell(),
                                    "Update Agentic Debugger Registration",
                                    migration.getMessage() + "\n\nReplace only the previous user-scoped "
                                    + "eclipse-debugger entry with the bundled MCP server? "
                                    + "Other MCP registrations will not be modified.");
                            if (approved) {
                                Job migrate = Job.create("Migrate Agentic Debugger MCP", m -> {
                                    String response;
                                    try { response = new ClaudeIntegration().migrateExisting(); }
                                    catch (Exception failure) { response = "Migration failed: " + failure.getMessage(); }
                                    String text = response;
                                    Display.getDefault().asyncExec(() -> MessageDialog.openInformation(
                                            Display.getDefault().getActiveShell(), "Agentic Debugger", text));
                                    return Status.OK_STATUS;
                                });
                                migrate.setSystem(true); migrate.schedule();
                            }
                        });
                        return Status.OK_STATUS;
                    }
                    catch (Exception ex) { result = "Configuration failed: " + ex.getMessage()
                            + "\n\nOpen Window > Preferences > Agentic Debugger to retry."; }
                    String message = result;
                    Display.getDefault().asyncExec(() -> MessageDialog.openInformation(
                            Display.getDefault().getActiveShell(), "Agentic Debugger", message));
                    return Status.OK_STATUS;
                });
                configureJob.setSystem(true);
                configureJob.schedule();
            }
        });
    }

    @Override
    public void earlyStartup() {

        System.out.println(
                "[Eclipse Agentic Debugger] Early startup executed."
        );

        // OPEN-36: open the Agent Activity view (without taking the focus) when an agent investigation starts.
        org.learningfromexperience.eclipse.agentdebug.ui.ActivityViewOpener.install();

        Job startupJob = new Job(
                "Eclipse Agentic Debugger - Startup"
        ) {

            @Override
            protected IStatus run(IProgressMonitor monitor) {

                if (monitor.isCanceled()) {
                    return Status.CANCEL_STATUS;
                }

                System.out.println(
                        "[Eclipse Agentic Debugger] Background initialization successful."
                );

                // Refresh an already-configured MCP runtime only when its contents have changed.
                String fixture = System.getenv("AGENTDEBUG_FIXTURE_SOURCE");
                try { if (fixture == null) new ClaudeIntegration().refreshBundledRuntime(); }
                catch (Exception error) {
                    System.err.println("[Eclipse Agentic Debugger] MCP runtime refresh failed: " + error.getMessage());
                }

                // Start communication bridge.
                try {

                    if (fixture != null) {
                        for (String item : fixture.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                            String trimmed = item.trim();
                            if (!trimmed.isEmpty()) {
                                DevelopmentFixture.prepare(java.nio.file.Path.of(trimmed), monitor);
                            }
                        }
                    }
                    EclipseBridgeServer bridgeServer =
                            new EclipseBridgeServer();

                    bridgeServer.start();

                    // Register bridge for lifecycle management.
                    Activator.setBridgeServer(bridgeServer);

                } catch (Exception e) {

                    return new Status(
                            IStatus.ERROR,
                            PLUGIN_ID,
                            "Failed to start Eclipse bridge server",
                            e
                    );
                }

                // Offer first-run setup; never change Claude configuration without explicit approval.
                if (fixture == null) offerFirstRunConfiguration();
                return Status.OK_STATUS;
            }
        };

        // Run initialization in the background.
        startupJob.setSystem(true);

        startupJob.schedule();
    }
}