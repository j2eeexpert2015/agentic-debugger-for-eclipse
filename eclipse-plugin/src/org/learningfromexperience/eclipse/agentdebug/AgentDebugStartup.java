package org.learningfromexperience.eclipse.agentdebug;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IStartup;

import org.learningfromexperience.eclipse.agentdebug.bridge.EclipseBridgeServer;
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseDebugService;

public class AgentDebugStartup implements IStartup {

    private static final String PLUGIN_ID =
            "org.learningfromexperience.eclipse.agentdebug";

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

                return Status.OK_STATUS;
            }
        };

        // Run initialization in the background.
        startupJob.setSystem(true);

        startupJob.schedule();
    }
}