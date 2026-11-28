package org.learningfromexperience.eclipse.agentdebug.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates;

/**
 * OPEN-70 (Ayan 28 Nov 13:08-13:27, mockup {@code post-install-welcome-mockup.html}): the dialog at the first Eclipse
 * start after installing. It says which agent was found and opens that agent's Preferences page ("Set up now"); it sets
 * nothing up itself, so it cannot fail. Eclipse's plain question dialog cannot show the green ✔ and the menu path in
 * bold, so this is a {@link MessageDialog} with a small custom area. What it shows is
 * {@link PreferenceStates#welcome} (test PS-U12).
 *
 * @author Ayan Dutta
 */
public final class WelcomeDialog extends MessageDialog {

    private final PreferenceStates.Welcome welcome;

    private WelcomeDialog(Shell shell, PreferenceStates.Welcome welcome) {
        super(shell, "Agentic Debugger for Eclipse IDE", null, welcome.title(), MessageDialog.NONE,
                welcome.buttons().toArray(new String[0]), welcome.buttons().size() - 1);
        this.welcome = welcome;
    }

    /** Shows the dialog on the UI thread; "Set up now" opens the page of the agent that was found. */
    public static void open(Shell shell) {
        PreferenceStates.Welcome welcome = PreferenceStates.welcome(ClaudeIntegration.claudeFound(),
                CopilotIntegration.copilotInstalled());
        int chosen = new WelcomeDialog(shell, welcome).open();
        if (welcome.open() == null || chosen != 1) return;   // Later, OK or closed
        String page = welcome.open() == PreferenceStates.WelcomePage.CLAUDE_CODE ? ClaudeCodePreferences.ID
                : CopilotPreferences.ID;
        PreferencesUtil.createPreferenceDialogOn(shell, page, null, null).open();
    }

    @Override
    protected Control createMessageArea(Composite composite) {
        Control area = super.createMessageArea(composite);
        if (messageLabel != null) messageLabel.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        return area;
    }

    @Override
    protected Control createCustomArea(Composite parent) {
        Composite box = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginLeft = 0;
        layout.verticalSpacing = 2;
        box.setLayout(layout);
        box.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        line(box, welcome.intro(), 2, false);
        for (PreferenceStates.AgentLine agent : welcome.agents()) {
            Label mark = new Label(box, SWT.NONE);
            mark.setText(agent.found() ? "  ✔" : "  –");
            mark.setForeground(PrefUi.colour(agent.found() ? PreferenceStates.Tone.OK : PreferenceStates.Tone.MUTED));
            Label text = new Label(box, SWT.NONE);
            text.setText(agent.text());
            if (!agent.found()) text.setForeground(PrefUi.colour(PreferenceStates.Tone.MUTED));
        }
        if (welcome.showLaterPath()) {
            Label gap = line(box, "", 2, false);
            ((GridData) gap.getLayoutData()).heightHint = 6;
            line(box, "You can also do this later in", 2, false);
            line(box, PreferenceStates.PREFERENCES_PATH + ".", 2, true);
        }
        return box;
    }

    private static Label line(Composite box, String text, int span, boolean bold) {
        Label label = new Label(box, SWT.WRAP);
        label.setText(text);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, span, 1);
        data.widthHint = 360;
        label.setLayoutData(data);
        if (bold) label.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        return label;
    }
}
