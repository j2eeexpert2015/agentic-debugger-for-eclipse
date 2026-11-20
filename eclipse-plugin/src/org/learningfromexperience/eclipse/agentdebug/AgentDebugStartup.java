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
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseDebugService;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;

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

                // Refresh an already-configured bundled runtime without modifying Claude settings.
                try { new ClaudeIntegration().refreshBundledRuntime(); }
                catch (Exception error) {
                    System.err.println("[Eclipse Agentic Debugger] MCP runtime refresh failed: " + error.getMessage());
                }

                // Initialize Eclipse Debug Service.
                EclipseDebugService debugService =
                        new EclipseDebugService();

                // Retrieve currently active Java debugging sessions.
                var sessions = debugService.listDebugSessions();

                System.out.println(
                        "[Eclipse Agentic Debugger] Active Java debug sessions: "
                        + sessions.size()
                );

                // Display debugging session information.
                sessions.forEach(session ->
                        System.out.println(
                                "[Eclipse Agentic Debugger] " + session
                        )
                );

                // Start communication bridge.
                try {

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
                offerFirstRunConfiguration();
                return Status.OK_STATUS;
            }
        };

        // Run initialization in the background.
        startupJob.setSystem(true);

        startupJob.schedule();
    }
}