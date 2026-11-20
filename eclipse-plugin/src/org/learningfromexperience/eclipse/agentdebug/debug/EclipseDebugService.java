package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import org.eclipse.jdt.debug.core.IJavaDebugTarget;

public class EclipseDebugService {

    public record DebugSessionInfo(
            String launchName,
            String targetName,
            boolean terminated,
            boolean disconnected,
            boolean suspended
    ) {
    }

    /**
     * Retrieves active Java debugging sessions managed by Eclipse.
     */
    public List<DebugSessionInfo> listDebugSessions() {

        List<DebugSessionInfo> sessions = new ArrayList<>();

        ILaunchManager launchManager =
                DebugPlugin.getDefault().getLaunchManager();

        ILaunch[] launches = launchManager.getLaunches();

        // -----------------------------------------------------
        // Diagnostic logging
        // -----------------------------------------------------

        System.out.println(
                "[Eclipse Agentic Debugger] DIAGNOSTIC: Total launches = "
                + launches.length
        );

        for (ILaunch launch : launches) {

            System.out.println(
                    "[Eclipse Agentic Debugger] DIAGNOSTIC: Launch mode = "
                    + launch.getLaunchMode()
            );

            System.out.println(
                    "[Eclipse Agentic Debugger] DIAGNOSTIC: Launch terminated = "
                    + launch.isTerminated()
            );

            System.out.println(
                    "[Eclipse Agentic Debugger] DIAGNOSTIC: Debug targets = "
                    + launch.getDebugTargets().length
            );

            for (IDebugTarget target : launch.getDebugTargets()) {

                System.out.println(
                        "[Eclipse Agentic Debugger] DIAGNOSTIC: Target class = "
                        + target.getClass().getName()
                );

                System.out.println(
                        "[Eclipse Agentic Debugger] DIAGNOSTIC: Is Java target = "
                        + (target instanceof IJavaDebugTarget)
                );

                System.out.println(
                        "[Eclipse Agentic Debugger] DIAGNOSTIC: Terminated = "
                        + target.isTerminated()
                );

                System.out.println(
                        "[Eclipse Agentic Debugger] DIAGNOSTIC: Target suspended = "
                        + target.isSuspended()
                );

                System.out.println(
                        "[Eclipse Agentic Debugger] DIAGNOSTIC: Disconnected = "
                        + target.isDisconnected()
                );

                // -------------------------------------------------
                // NEW: Thread-level suspension diagnostics
                // -------------------------------------------------

                try {

                    IThread[] threads = target.getThreads();

                    System.out.println(
                            "[Eclipse Agentic Debugger] DIAGNOSTIC: Thread count = "
                            + threads.length
                    );

                    for (IThread thread : threads) {

                        System.out.println(
                                "[Eclipse Agentic Debugger] DIAGNOSTIC: Thread = "
                                + thread.getName()
                                + ", suspended = "
                                + thread.isSuspended()
                        );
                    }

                } catch (DebugException e) {

                    System.err.println(
                            "[Eclipse Agentic Debugger] "
                            + "Unable to inspect threads: "
                            + e.getMessage()
                    );
                }
            }
        }

        // -----------------------------------------------------
        // Filter active Java debugging sessions
        // -----------------------------------------------------

        for (ILaunch launch : launches) {

            // Only include launches started in debug mode.
            if (!ILaunchManager.DEBUG_MODE.equals(
                    launch.getLaunchMode())) {

                continue;
            }

            ILaunchConfiguration configuration =
                    launch.getLaunchConfiguration();

            String launchName = configuration != null
                    ? configuration.getName()
                    : "Unknown Launch";

            for (IDebugTarget target : launch.getDebugTargets()) {

                // Only include Java debugging targets.
                if (!(target instanceof IJavaDebugTarget)) {
                    continue;
                }

                try {

                    // Exclude terminated debugging sessions.
                    if (target.isTerminated()) {
                        continue;
                    }

                    // Check individual threads for suspension.
                    boolean suspended = hasSuspendedThread(target);

                    DebugSessionInfo session = new DebugSessionInfo(
                            launchName,
                            target.getName(),
                            target.isTerminated(),
                            target.isDisconnected(),
                            suspended
                    );

                    sessions.add(session);

                } catch (DebugException e) {

                    System.err.println(
                            "[Eclipse Agentic Debugger] "
                            + "Unable to inspect debug target: "
                            + e.getMessage()
                    );
                }
            }
        }

        System.out.println(
                "[Eclipse Agentic Debugger] DIAGNOSTIC: "
                + "Filtered active Java sessions = "
                + sessions.size()
        );

        return sessions;
    }

    /**
     * Determines whether at least one thread in the
     * debugging target is currently suspended.
     *
     * @param target Eclipse debugging target
     * @return true if any thread is suspended
     * @throws DebugException if thread inspection fails
     */
    private boolean hasSuspendedThread(IDebugTarget target)
            throws DebugException {

        IThread[] threads = target.getThreads();

        for (IThread thread : threads) {

            if (thread.isSuspended()) {
                return true;
            }
        }

        return false;
    }
}