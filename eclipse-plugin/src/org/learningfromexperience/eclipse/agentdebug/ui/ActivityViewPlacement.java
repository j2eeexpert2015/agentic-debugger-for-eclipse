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
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Puts the Agent Activity view where no other view hides it (OPEN-36): not Variables (brought to the front at every
 * first stop), not the Console (brought to the front whenever the program prints).
 *
 * <p><b>Floating window (default).</b> The first time in a perspective, the view is detached into a floating window at
 * the bottom right of the Eclipse window ({@link PerspectivePolicy#floatingPosition}); the user can drag it anywhere,
 * even to a second screen, and Eclipse keeps that place and size. A view that is floating is never moved. If the user
 * docks it again, it is not made floating again in that perspective, and the rule below applies. Preferences &gt;
 * Agentic Debugger turns the floating window off.
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
    /** Tag on the view once it was made floating automatically (kept in the workbench model across restarts). */
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
            Object holder = stack.getParent();
            if (holder instanceof MWindow) return;                       // floating: the user's place, left alone

            boolean floating = PerspectivePolicy.floatingActivityView(InstanceScope.INSTANCE
                    .getNode("org.learningfromexperience.eclipse.agentdebug").get(PerspectivePolicy.FLOATING_ACTIVITY_VIEW, null));
            if (floating && !view.getTags().contains(AUTO_FLOATED)) {
                // Once per perspective: if the user docks it again afterwards, that choice stays (handled below).
                view.getTags().add(AUTO_FLOATED);
                int[] at = PerspectivePolicy.floatingPosition(model.getX(), model.getY(), model.getWidth(), model.getHeight());
                models.detach(view, at[0], at[1], PerspectivePolicy.FLOATING_WIDTH, PerspectivePolicy.FLOATING_HEIGHT);
                Platform.getLog(ActivityViewPlacement.class).info("[Agentic Debugger] Agent Activity opened as a floating"
                        + " window in perspective " + perspective.getElementId());
                return;
            }
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
