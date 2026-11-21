package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Follows only the Java debug session controlled by the agent and displays its suspended source.
 *
 * @author Ayan Dutta
 */
public final class AgentDebugUiCoordinator implements AutoCloseable {
    private static final String PLUGIN_ID = "org.learningfromexperience.eclipse.agentdebug";
    private final AtomicReference<ILaunch> activeLaunch = new AtomicReference<>();
    private final IDebugEventSetListener listener = this::onEvents;
    private volatile String lastNavigation = "No suspended agent-controlled frame displayed";

    public AgentDebugUiCoordinator() {
        DebugPlugin.getDefault().addDebugEventListener(listener);
    }

    /** Mark the exact launch object as agent-owned context, never all workspace debug sessions. */
    public void follow(ILaunch launch) {
        activeLaunch.set(launch);
        showSuspended(launch, false);
    }

    public String status() { return lastNavigation; }

    /** Schedule navigation for a suspended stack frame without resuming execution. */
    public boolean showSuspended(ILaunch launch, boolean force) {
        if (launch == null || launch.isTerminated()) return false;
        try {
            for (var target : launch.getDebugTargets()) {
                for (IThread thread : target.getThreads()) {
                    if (thread.isSuspended() && thread.getStackFrames().length > 0) {
                        IStackFrame frame = thread.getTopStackFrame();
                        if (frame == null) continue;
                        activeLaunch.set(launch);
                        navigate(launch, frame, force);
                        return true;
                    }
                }
            }
        } catch (Exception error) {
            lastNavigation = "Unable to inspect suspended source: " + error.getMessage();
        }
        return false;
    }

    private void onEvents(DebugEvent[] events) {
        ILaunch selected = activeLaunch.get();
        if (selected == null) return;
        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.SUSPEND) continue;
            Object source = event.getSource();
            if (!(source instanceof IDebugElement element) || element.getLaunch() != selected) continue;
            // Follow only the launch the agent selected; unrelated sessions are never activated.
            showSuspended(selected, false);
            break;
        }
    }

    private void navigate(ILaunch launch, IStackFrame frame, boolean force) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (display.isDisposed() || launch != activeLaunch.get() || launch.isTerminated()) return;
            try {
                if (!PlatformUI.isWorkbenchRunning()) return;
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null) {
                    IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                    if (windows.length == 0) return;
                    window = windows[0];
                }
                IWorkbenchPage page = window.getActivePage();
                if (page == null) return;
                boolean changePerspective = InstanceScope.INSTANCE.getNode(PLUGIN_ID)
                        .getBoolean("autoDebugPerspective", true);
                if (changePerspective) {
                    page = PlatformUI.getWorkbench().showPerspective(IDebugUIConstants.ID_DEBUG_PERSPECTIVE, window);
                }
                IViewPart view = page.findView(IDebugUIConstants.ID_DEBUG_VIEW);
                if (view == null) view = page.showView(IDebugUIConstants.ID_DEBUG_VIEW);
                if (view.getSite().getSelectionProvider() != null) {
                    view.getSite().getSelectionProvider().setSelection(new StructuredSelection(frame));
                }
                // The public debug UI source lookup opens and annotates the exact suspended line.
                var location = DebugUITools.lookupSource(frame, null);
                if (location != null) DebugUITools.displaySource(location, page);
                lastNavigation = frame.getName() + ":" + frame.getLineNumber();
            } catch (Exception error) {
                lastNavigation = "Source navigation failed: " + error.getClass().getSimpleName()
                        + ": " + error.getMessage();
                System.err.println("[Eclipse Agentic Debugger] " + lastNavigation);
            }
        });
    }

    @Override public void close() {
        DebugPlugin.getDefault().removeDebugEventListener(listener);
        activeLaunch.set(null);
    }
}
