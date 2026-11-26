package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * Carries the open terminal views into the perspective Eclipse switches to for an agent investigation (OPEN-36, user
 * request 25 Nov 02:57): the Claude terminal no longer disappears when Eclipse switches to the Debug perspective.
 * GitHub Copilot's Chat view is carried the same way (OPEN-59, 27 Nov): the same view with the same conversation,
 * nothing restarted; a new or reset Debug perspective has a place for it to the right of the editors (plugin.xml).
 *
 * <p><b>Nothing is touched inside the terminal.</b> All terminal tabs live in Eclipse's Terminal view, and Eclipse
 * shares a view's content between perspectives: showing the same view (same ID and secondary ID) in the Debug
 * perspective shows the same running sessions, the Claude session included, with the same tab selected. No terminal
 * is started, closed or chosen; which one runs Claude is never guessed: every terminal view open in the perspective
 * being left is carried along ({@link PerspectivePolicy#terminalView}). When Claude runs outside Eclipse, there is no
 * terminal view and nothing happens.
 *
 * <p><b>Keyboard.</b> The views are shown without activation; only the terminal view that had the keyboard before the
 * switch gets it back, so typing in the Claude terminal continues.
 *
 * <p><b>Place.</b> A new or reset Debug perspective has a place for it below the Debug view (plugin.xml). In an existing
 * one, a terminal that lands in a tab group with Console or Variables is moved once into a group of its own
 * ({@link ActivityViewPlacement#giveTerminalItsArea}); afterwards Eclipse keeps it where the user puts it.
 *
 * <p>Never throws: a convenience that must never affect debugging.
 *
 * @author Ayan Dutta
 */
final class TerminalCarrier {

    /** A terminal view open in a perspective; {@code active}: it had the keyboard. */
    record Open(String id, String secondaryId, boolean active) {
    }

    private TerminalCarrier() {
    }

    /** The terminal views open in the page's current perspective. */
    static List<Open> open(IWorkbenchPage page) {
        List<Open> terminals = new ArrayList<>();
        try {
            IWorkbenchPart active = page.getActivePart();
            for (IViewReference reference : page.getViewReferences()) {
                if (!PerspectivePolicy.carriedView(reference.getId())) continue;
                IViewPart part = reference.getView(false);
                terminals.add(new Open(reference.getId(), reference.getSecondaryId(), part != null && part == active));
            }
        } catch (Exception | LinkageError unavailable) {
            // Nothing to carry.
        }
        return terminals;
    }

    /** Switches the page to the perspective and carries the open terminal views along. */
    static void switchPerspective(IWorkbenchWindow window, IWorkbenchPage page, IPerspectiveDescriptor perspective) {
        List<Open> terminals = open(page);
        page.setPerspective(perspective);      // the page only: the Eclipse window is not brought to the front
        carry(window, page, terminals);
    }

    /** Shows the terminal views in the page's (new) current perspective; gives the keyboard back to the one that had it. */
    static void carry(IWorkbenchWindow window, IWorkbenchPage page, List<Open> terminals) {
        for (Open terminal : terminals) {
            try {
                page.showView(terminal.id(), terminal.secondaryId(), IWorkbenchPage.VIEW_CREATE);
                if (PerspectivePolicy.terminalView(terminal.id()))
                    ActivityViewPlacement.giveTerminalItsArea(window, terminal.id(), terminal.secondaryId());
                IViewPart shown = page.showView(terminal.id(), terminal.secondaryId(), IWorkbenchPage.VIEW_VISIBLE);
                if (terminal.active() && shown != null) page.activate(shown);
            } catch (Exception | LinkageError failed) {
                Platform.getLog(TerminalCarrier.class).warn("[Agentic Debugger] Could not show view "
                        + terminal.id() + " in the new perspective: " + failed);
            }
        }
    }
}
