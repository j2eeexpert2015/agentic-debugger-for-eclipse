package org.learningfromexperience.eclipse.agentdebug;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IStartup;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;


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

    /**
     * OPEN-70: a short welcome at the first start after installing: which agent was found, and "Set up now" opens its
     * Preferences page. It sets nothing up itself (no Claude Code command runs from here), so it never shows an error;
     * every setup case is on the Claude Code and GitHub Copilot pages. Shown once.
     */
    private static void offerFirstRunConfiguration() {
        var prefs = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
        if (prefs.getBoolean("setupPromptShown", false)) return;
        Display.getDefault().asyncExec(() -> {
            // Mark it seen before showing it; the settings stay reachable from Preferences.
            prefs.putBoolean("setupPromptShown", true);
            try { prefs.flush(); } catch (Exception e) { /* non-fatal preference persistence */ }
            try {
                org.learningfromexperience.eclipse.agentdebug.ui.WelcomeDialog.open(Display.getDefault().getActiveShell());
            } catch (RuntimeException | LinkageError failure) {
                System.err.println("[Eclipse Agentic Debugger] Welcome dialog not shown: " + failure);
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

        // OPEN-36: the agent status in the status bar (an E4 tool control, 27 Nov: the toolbar item was 7 px high).
        org.learningfromexperience.eclipse.agentdebug.ui.StatusTrim.install();

        // OPEN-69: remembers Claude Code's last finished investigation for the Claude Code preference page.
        org.learningfromexperience.eclipse.agentdebug.ui.ClaudeUsage.install();

        // OPEN-61: a quiet setup check about 30 s later, only for the integrations in use (nothing is started).
        org.learningfromexperience.eclipse.agentdebug.ui.SetupCheckRunner.scheduleQuietCheck();

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