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
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Follows only the Java debug session controlled by the agent and displays its suspended source.
 *
 * <p><b>Layout (OPEN-35).</b> By default the current perspective is kept, so the terminal in which Claude runs stays
 * visible. On the first stop of an agent launch the debug views ({@link PerspectivePolicy#DEBUG_VIEWS}) are opened in
 * the current perspective and Variables is brought to the front of its group; none of them is activated, so the
 * keyboard focus stays where the user left it (Claude may be asking something in the terminal). Where the views go in
 * the Java perspective is declared in plugin.xml, in their own group under the editor, never in the terminal's group.
 * Only a user who explicitly chose it in Preferences → Agentic Debugger gets a switch to the Debug perspective, once
 * per launch.
 *
 * @author Ayan Dutta
 */
public final class AgentDebugUiCoordinator implements AutoCloseable {
    private static final String PLUGIN_ID = "org.learningfromexperience.eclipse.agentdebug";
    private final AtomicReference<ILaunch> activeLaunch = new AtomicReference<>();
    private final IDebugEventSetListener listener = this::onEvents;
    private volatile String lastNavigation = "No suspended agent-controlled frame displayed";
    /** The launch whose views were already arranged (or perspective switched); later stops only navigate. */
    private final AtomicReference<ILaunch> arranged = new AtomicReference<>();

    public AgentDebugUiCoordinator() {
        DebugPlugin.getDefault().addDebugEventListener(listener);
    }

    /** Mark the exact launch object as agent-owned context, never all workspace debug sessions. */
    public void follow(ILaunch launch) {
        activeLaunch.set(launch);
        showSuspended(launch, false);
    }

    public String status() { return lastNavigation; }

    /**
     * Schedule navigation for a suspended stack frame without resuming execution. When the whole JVM is suspended
     * (a breakpoint with "suspend VM", a pause, an exception breakpoint), every thread is suspended, including JVM
     * threads such as "Reference Handler" whose top frame is JDK code (java.lang.ref.Reference): the thread shown is
     * chosen by {@link #bestThread}, never simply the first suspended one.
     */
    public boolean showSuspended(ILaunch launch, boolean force) {
        if (launch == null || launch.isTerminated()) return false;
        try {
            IThread thread = bestThread(launch);
            if (thread == null) return false;
            IStackFrame frame = displayFrame(thread);
            if (frame == null) return false;
            activeLaunch.set(launch);
            navigate(launch, frame, force);
            return true;
        } catch (Exception error) {
            lastNavigation = "Unable to inspect suspended source: " + error.getMessage();
        }
        return false;
    }

    /**
     * The suspended thread to show: first an application thread stopped at a breakpoint, then any suspended
     * application thread. JVM system threads (system thread group: Reference Handler, Finalizer, Signal Dispatcher,
     * Common-Cleaner ...) are never shown; when only they are suspended, nothing is shown.
     */
    static IThread bestThread(ILaunch launch) throws Exception {
        IThread other = null;
        for (var target : launch.getDebugTargets()) {
            for (IThread thread : target.getThreads()) {
                if (!thread.isSuspended() || !thread.hasStackFrames() || systemThread(thread)) continue;
                if (thread.getBreakpoints().length > 0) return thread;
                if (other == null) other = thread;
            }
        }
        return other;
    }

    /**
     * The frame to show in the editor: the top frame, unless it is JDK code (a pause or a step into the JDK leaves the
     * thread in java.*, jdk.*, sun.*, javax.* code); then the first frame of application code below it, so the user
     * sees where their program is ("LongRunningApplication.main:12") instead of Thread.class or Reference.class.
     * The Debug view still shows the whole stack. Falls back to the top frame when no application frame exists.
     */
    static IStackFrame displayFrame(IThread thread) throws Exception {
        IStackFrame top = thread.getTopStackFrame();
        for (IStackFrame frame : thread.getStackFrames()) {
            if (!(frame instanceof IJavaStackFrame javaFrame)) return top;
            if (!PerspectivePolicy.jdkType(javaFrame.getDeclaringTypeName())) return frame;
        }
        return top;
    }

    private static boolean systemThread(IThread thread) {
        try {
            return thread instanceof IJavaThread javaThread && javaThread.isSystemThread();
        } catch (Exception unknown) {
            return false;
        }
    }

    private void onEvents(DebugEvent[] events) {
        ILaunch selected = activeLaunch.get();
        if (selected == null) return;
        boolean suspended = false;
        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.SUSPEND) continue;
            Object source = event.getSource();
            if (!(source instanceof IDebugElement element) || element.getLaunch() != selected) continue;
            suspended = true;
            // The thread that hit a breakpoint or ended a step is the one to show, when it is an application thread.
            boolean realStop = event.getDetail() == DebugEvent.BREAKPOINT || event.getDetail() == DebugEvent.STEP_END;
            if (realStop && source instanceof IThread thread && !systemThread(thread)) {
                try {
                    IStackFrame frame = displayFrame(thread);
                    if (frame != null) { navigate(selected, frame, false); return; }
                } catch (Exception ignored) {
                    // fall back to the best suspended thread
                }
            }
        }
        // Follow only the launch the agent selected; unrelated sessions are never activated.
        if (suspended) showSuspended(selected, false);
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
                boolean firstStop = arranged.getAndSet(launch) != launch;
                boolean changePerspective = PerspectivePolicy.switchToDebugPerspective(InstanceScope.INSTANCE
                        .getNode(PLUGIN_ID).get(PerspectivePolicy.SWITCH_TO_DEBUG_PERSPECTIVE, null));
                if (changePerspective && firstStop) {
                    page = PlatformUI.getWorkbench().showPerspective(IDebugUIConstants.ID_DEBUG_PERSPECTIVE, window);
                } else if (!changePerspective) {
                    showDebugViews(page, firstStop);
                }
                IViewPart view = page.findView(IDebugUIConstants.ID_DEBUG_VIEW);
                // VIEW_CREATE: the Debug view provides the selected frame to Variables, but is never activated.
                if (view == null) view = page.showView(IDebugUIConstants.ID_DEBUG_VIEW, null, IWorkbenchPage.VIEW_CREATE);
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

    /**
     * Opens the debug views in the current perspective without activating any of them. Views the user closed are
     * opened again on every stop; Variables is brought to the front only on the first stop of a launch, so a tab the
     * user chooses afterwards is not taken away at each stop.
     */
    private static void showDebugViews(IWorkbenchPage page, boolean firstStop) {
        for (String id : PerspectivePolicy.DEBUG_VIEWS) {
            boolean front = firstStop && id.equals(PerspectivePolicy.FRONT_VIEW);
            try {
                if (front) page.showView(id, null, IWorkbenchPage.VIEW_VISIBLE);
                else if (page.findView(id) == null) page.showView(id, null, IWorkbenchPage.VIEW_CREATE);
            } catch (Exception unavailable) {
                // A view that cannot be opened (for example a missing bundle) must not stop source navigation.
                System.err.println("[Eclipse Agentic Debugger] Could not open view " + id + ": " + unavailable.getMessage());
            }
        }
    }

    @Override public void close() {
        DebugPlugin.getDefault().removeDebugEventListener(listener);
        activeLaunch.set(null);
        arranged.set(null);
    }
}
