package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.List;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPartSashContainerElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Puts the docked Agent Activity view where no other view hides it (OPEN-36): not Variables (brought to the front at
 * every first stop), not the Console (brought to the front whenever the program prints). Used when Preferences &gt;
 * Agentic Debugger &gt; "Show Agent Activity in its own window" is off; by default Agent Activity is the
 * {@link ActivityWindow}, which needs no placing.
 *
 * <p><b>Floating views.</b> Until 25 Nov the view was detached into a floating view window per perspective (tag
 * {@link #AUTO_FLOATED}); the switch to the Debug perspective hid it. With the own window on, such a view is closed
 * ({@link #hideAutoFloated}). A view the user made floating by hand is left where it is.
 *
 * <p><b>Docked.</b>
 * plugin.xml declares an area of its own for a new or reset Java or Debug perspective. In a perspective that already
 * existed, or after the user moved the view, Eclipse keeps the view where it is, often in the stack with Variables.
 * So, before the view is shown for an investigation: when the view shares its stack with other views, it is moved
 * into a new stack of its own to the right of the Console's stack (or, without a Console in the perspective, to the
 * right of its current stack). A view that already has a stack of its own is left where the user put it.
 *
 * <p>Uses the Eclipse 4 workbench model, the same model Eclipse changes when a view is dragged. Never throws: if
 * anything is not as expected, the view simply stays where it is.
 *
 * @author Ayan Dutta
 */
final class ActivityViewPlacement {

    static final String STACK_ID = "org.learningfromexperience.eclipse.agentdebug.activityStack";
    /** Tag on a view this plugin made floating (until 25 Nov; kept in the workbench model across restarts). */
    static final String AUTO_FLOATED = "agentdebug.activity.autoFloated";
    private static final String CONSOLE = "org.eclipse.ui.console.ConsoleView";
    /** Share of the split area given to the Agent Activity stack. */
    private static final float RATIO = 0.45f;

    private ActivityViewPlacement() {
    }

    /**
     * Moves the view into a stack of its own when it shares one; call after the view exists in the page. Every reason
     * for leaving the view where it is goes to the Error Log (Window > Show View > Error Log), so a failure can be
     * seen and reported.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void ensureOwnArea(IWorkbenchWindow window) {
        try {
            MWindow model = modelWindow(window);
            EModelService models = modelService(window, model);
            if (model == null || models == null) {
                log("no workbench model (window " + (model != null) + ", model service " + (models != null) + ")");
                return;
            }
            MPerspective perspective = models.getActivePerspective(model);
            if (perspective == null) {
                log("no active perspective");
                return;
            }
            List<MPlaceholder> mine = models.findElements(perspective, AgentActivityView.ID, MPlaceholder.class, null);
            if (mine.isEmpty()) {
                log("the view is not in perspective " + perspective.getElementId());
                return;
            }
            MPlaceholder view = mine.get(0);
            Object current = view.getParent();
            if (!(current instanceof MPartStack stack)) {
                log("the view is not in a tab group but in " + (current == null ? "nothing" : current.getClass().getSimpleName()));
                return;
            }
            if (stack.getParent() instanceof MWindow) return;            // floating: the user's place, left alone
            if (visibleChildren(stack) <= 1) return;                     // already alone in its stack: nothing to do

            MPartSashContainerElement relTo = stack;
            List<MPlaceholder> consoles = models.findElements(perspective, CONSOLE, MPlaceholder.class, null);
            Object consoleParent = consoles.isEmpty() ? null : consoles.get(0).getParent();
            if (consoleParent instanceof MPartStack consoleStack && consoleStack != stack && consoleStack.isToBeRendered())
                relTo = consoleStack;

            MPartStack own = models.createModelElement(MPartStack.class);
            own.setElementId(STACK_ID);
            models.insert(own, relTo, EModelService.RIGHT_OF, RATIO);
            try {
                // Moves without closing the view (as dragging a tab does).
                models.move(view, (MElementContainer) own);
            } catch (LinkageError olderEclipse) {
                ((List) stack.getChildren()).remove(view);
                own.getChildren().add((MStackElement) view);
            }
            own.setSelectedElement((MStackElement) view);
            view.setToBeRendered(true);
            view.setVisible(true);
            Platform.getLog(ActivityViewPlacement.class).info("[Agentic Debugger] Agent Activity moved to an area of its own"
                    + " in perspective " + perspective.getElementId());
        } catch (Exception | LinkageError unexpected) {
            log(unexpected.toString());
        }
    }

    /**
     * Closes the Agent Activity view in the page's perspective when this plugin made it floating earlier (tag
     * {@link #AUTO_FLOATED}), so only the own window shows Agent Activity. Never throws.
     */
    static void hideAutoFloated(IWorkbenchWindow window, IWorkbenchPage page) {
        try {
            MWindow model = modelWindow(window);
            EModelService models = modelService(window, model);
            if (model == null || models == null) return;
            MPerspective perspective = models.getActivePerspective(model);
            if (perspective == null) return;
            for (MPlaceholder view : models.findElements(perspective, AgentActivityView.ID, MPlaceholder.class, null)) {
                if (!view.getTags().contains(AUTO_FLOATED)) continue;
                Object parent = view.getParent();
                if (!(parent instanceof MPartStack stack) || !(stack.getParent() instanceof MWindow)) continue;
                view.getTags().remove(AUTO_FLOATED);
                IViewPart part = page.findView(AgentActivityView.ID);
                if (part != null) page.hideView(part);
                Platform.getLog(ActivityViewPlacement.class).info("[Agentic Debugger] Closed the earlier floating Agent"
                        + " Activity view in perspective " + perspective.getElementId() + "; it now has its own window");
            }
        } catch (Exception | LinkageError unexpected) {
            Platform.getLog(ActivityViewPlacement.class).warn("[Agentic Debugger] Could not close the earlier floating"
                    + " Agent Activity view: " + unexpected);
        }
    }

    /** The window in the Eclipse 4 model: as a service, otherwise from the window itself. */
    private static MWindow modelWindow(IWorkbenchWindow window) {
        MWindow model = null;
        try {
            model = window.getService(MWindow.class);
        } catch (RuntimeException ignored) {
            // try the next way
        }
        if (model != null) return model;
        try {
            Object found = window.getClass().getMethod("getModel").invoke(window);
            return found instanceof MWindow m ? m : null;
        } catch (ReflectiveOperationException | RuntimeException none) {
            return null;
        }
    }

    /** The model service: from the window, the workbench, or the model window's own context. */
    private static EModelService modelService(IWorkbenchWindow window, MWindow model) {
        try {
            EModelService service = window.getService(EModelService.class);
            if (service != null) return service;
        } catch (RuntimeException ignored) {
            // try the next way
        }
        try {
            EModelService service = PlatformUI.getWorkbench().getService(EModelService.class);
            if (service != null) return service;
        } catch (RuntimeException ignored) {
            // try the next way
        }
        return model == null || model.getContext() == null ? null : model.getContext().get(EModelService.class);
    }

    private static void log(String reason) {
        Platform.getLog(ActivityViewPlacement.class).warn("[Agentic Debugger] Agent Activity stays where it is: " + reason
                + ". You can drag its tab to the right half of the Console area once; Eclipse remembers that place.");
    }

    private static int visibleChildren(MPartStack stack) {
        int count = 0;
        for (MStackElement child : stack.getChildren()) if (child.isToBeRendered()) count++;
        return count;
    }
}
