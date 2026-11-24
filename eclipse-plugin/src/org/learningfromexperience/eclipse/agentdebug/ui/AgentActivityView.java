package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * Agent Activity as a docked Eclipse view (OPEN-36): Window &gt; Show View &gt; Agentic Debugger &gt; Agent Activity,
 * or opened by itself when Preferences &gt; Agentic Debugger &gt; "Show Agent Activity in its own window" is off. By
 * default Agent Activity opens in its own window instead ({@link ActivityWindow}), which no perspective change can
 * hide. Both show the same content ({@link ActivityPanel}).
 *
 * <p>The view never activates itself and never takes the keyboard focus on an update; it is opened by
 * {@link ActivityViewOpener} without activation, so typing in the terminal is not interrupted.
 *
 * @author Ayan Dutta
 */
public final class AgentActivityView extends ViewPart {

    /** View ID, as registered in plugin.xml. */
    public static final String ID = "org.learningfromexperience.eclipse.agentdebug.activityView";

    private ActivityPanel panel;

    @Override
    public void createPartControl(Composite parent) {
        panel = new ActivityPanel(parent, () -> getSite().getPage(), this::setContentDescription);
    }

    @Override
    public void setFocus() {
        if (panel != null) panel.setFocus();
    }

    @Override
    public void dispose() {
        if (panel != null) panel.dispose();
        super.dispose();
    }
}
