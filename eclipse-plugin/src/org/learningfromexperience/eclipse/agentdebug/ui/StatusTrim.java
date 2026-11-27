package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.SideValue;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimBar;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimmedWindow;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.menu.MToolControl;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Puts the agent status ({@link AgentStatusControl}) into the bottom trim of every Eclipse window as an E4 tool control
 * (27 Nov). Before, it was a {@code <control>} in a status-bar toolbar (plugin.xml); on Windows a toolbar without
 * buttons is only 7 px high, so its text was drawn but cut to a strip. A tool control is sized from its own content.
 *
 * <p>The control is removed again when Eclipse shuts down, so the saved workbench never refers to this plugin (no
 * error at the next start if the plugin was uninstalled in between). Never throws.
 *
 * @author Ayan Dutta
 */
public final class StatusTrim {

    static final String ID = "org.learningfromexperience.eclipse.agentdebug.agentStatus";
    private static final String CLASS = "bundleclass://org.learningfromexperience.eclipse.agentdebug/"
            + AgentStatusControl.class.getName();

    private StatusTrim() { }

    /** Adds the agent status to every open window and to windows opened later; call once at start-up. */
    public static void install() {
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            Display display = workbench.getDisplay();
            display.asyncExec(() -> {
                for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) add(window);
                workbench.addWindowListener(new IWindowListener() {
                    @Override public void windowOpened(IWorkbenchWindow window) { add(window); }
                    @Override public void windowClosed(IWorkbenchWindow window) { }
                    @Override public void windowActivated(IWorkbenchWindow window) { }
                    @Override public void windowDeactivated(IWorkbenchWindow window) { }
                });
                workbench.addWorkbenchListener(new IWorkbenchListener() {
                    @Override
                    public boolean preShutdown(IWorkbench closing, boolean forced) {
                        for (IWorkbenchWindow window : closing.getWorkbenchWindows()) remove(window);
                        return true;
                    }

                    @Override public void postShutdown(IWorkbench closing) { }
                });
            });
        } catch (RuntimeException | LinkageError unavailable) {
            System.err.println("[Eclipse Agentic Debugger] Status bar item not added: " + unavailable);
        }
    }

    private static void add(IWorkbenchWindow window) {
        try {
            MWindow model = ActivityViewPlacement.modelWindow(window);
            EModelService models = ActivityViewPlacement.modelService(window, model);
            if (!(model instanceof MTrimmedWindow trimmed) || models == null) {
                System.err.println("[Eclipse Agentic Debugger] Status bar item not added: no workbench model");
                return;
            }
            MTrimBar bottom = models.getTrim(trimmed, SideValue.BOTTOM);
            for (MUIElement child : bottom.getChildren()) {
                if (ID.equals(child.getElementId())) return;          // already there (an earlier session)
            }
            MToolControl control = models.createModelElement(MToolControl.class);
            control.setElementId(ID);
            control.setContributionURI(CLASS);
            control.setToBeRendered(true);
            control.setVisible(true);
            // Before Eclipse's own items on the right (heap, progress), after the message area on the left.
            int at = Math.max(0, bottom.getChildren().size() - 1);
            bottom.getChildren().add(at, control);
            System.out.println("[Eclipse Agentic Debugger] Status bar item added at " + at + " of "
                    + bottom.getChildren().size());
        } catch (RuntimeException | LinkageError failed) {
            System.err.println("[Eclipse Agentic Debugger] Status bar item not added: " + failed);
        }
    }

    private static void remove(IWorkbenchWindow window) {
        try {
            MWindow model = ActivityViewPlacement.modelWindow(window);
            EModelService models = ActivityViewPlacement.modelService(window, model);
            if (!(model instanceof MTrimmedWindow trimmed) || models == null) return;
            models.getTrim(trimmed, SideValue.BOTTOM).getChildren().removeIf(child -> ID.equals(child.getElementId()));
        } catch (RuntimeException | LinkageError ignored) {
            // shutting down: nothing to do
        }
    }
}
