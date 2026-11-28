package org.learningfromexperience.eclipse.agentdebug.ui;

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
    /** Eclipse's status line (the message area at the left of the bottom trim). */
    private static final String STATUS_LINE = "org.eclipse.ui.StatusLine";
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
            // Already there: left over from a session that did not shut down cleanly (the test chain stops Eclipse
            // with Stop-Process), or added twice. Keep the first, drop any extra, never add another (28 Nov 11:00:
            // removing the rendered one and adding a new one showed two items).
            boolean found = false;
            for (var it = bottom.getChildren().iterator();
                    it.hasNext(); ) {
                if (!ID.equals(it.next().getElementId())) continue;
                if (found) it.remove();
                found = true;
            }
            if (found) return;
            MToolControl control = models.createModelElement(MToolControl.class);
            control.setElementId(ID);
            control.setContributionURI(CLASS);
            control.setToBeRendered(true);
            control.setVisible(true);
            // OPEN-69 (Ayan 28 Nov 10:25): on the left, just before Eclipse's message area, away from Copilot's and
            // Eclipse's items on the right. Without that area, first.
            int at = 0;
            for (int i = 0; i < bottom.getChildren().size(); i++) {
                if (STATUS_LINE.equals(bottom.getChildren().get(i).getElementId())) {
                    at = i;
                    break;
                }
            }
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
