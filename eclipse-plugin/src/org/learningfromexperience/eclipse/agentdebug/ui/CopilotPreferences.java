package org.learningfromexperience.eclipse.agentdebug.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotAgentInstaller;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotAgentTools;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotSettings;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotSkill;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Action;

/**
 * Preferences &gt; Agentic Debugger &gt; GitHub Copilot, for GitHub Copilot only (OPEN-69 layout):
 * <ul>
 *   <li><b>Agentic Debugger agent</b> for all workspaces ({@code ~/.copilot/agents}, OPEN-23 d): Install, Update,
 *       Remove (asks when you changed the file), with a note when it is also in projects (Copilot lists every copy);</li>
 *   <li><b>Who can use our tools</b> (OPEN-60, {@link CopilotIntegration}): first whether our server is approved in
 *       Copilot (with "Open Copilot's MCP page"), then "Only the Agentic Debugger agent, without asking each time"
 *       (applied when Copilot starts, so a change shows "Restart Eclipse to apply"), then what is in effect now;</li>
 *   <li><b>Skill</b>: Copilot uses the Claude Code copy when there is one (managed on the Claude Code page), else its
 *       own ({@link CopilotSkill}): Install, Update, Remove (only the files the plugin wrote).</li>
 * </ul>
 * When Copilot is not installed, the page shows only one line saying so (OPEN-69). Every case, and which button is enabled
 * in it, is {@link PreferenceStates} (tests PS-U4, PS-U5). Same preference keys and actions as before.
 *
 * @author Ayan Dutta
 */
public final class CopilotPreferences extends PreferencePage implements IWorkbenchPreferencePage {

    /** This page's id in plugin.xml (unchanged). */
    public static final String ID = "org.learningfromexperience.eclipse.agentdebug.preferences.copilot";

    /** The "only our agent" setting as it was when this Eclipse first showed the page: applied when Copilot starts. */
    private static Boolean agentOnlyAtStart;

    private Composite root;
    private boolean copilot;
    private PrefUi.Section agent;
    private PrefUi.Section skill;
    private PrefUi.Section approval;
    private Button box;
    private Label toolsStatus;
    private Label duplicates;

    @Override public void init(IWorkbench workbench) { noDefaultAndApplyButton(); }

    @Override
    protected Control createContents(Composite parent) {
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        copilot = CopilotIntegration.copilotInstalled();
        if (!copilot) {
            // OPEN-69/OPEN-70 (Ayan 28 Nov 10:28, 13:15-13:25): without Copilot nothing on this page means anything:
            // two short lines, the menu path in bold; the sections appear once Copilot is installed and Eclipse restarted.
            Label missing = PrefUi.wrap(root, "GitHub Copilot isn't installed in this Eclipse.");
            missing.setFont(org.eclipse.jface.resource.JFaceResources.getFontRegistry()
                    .getBold(org.eclipse.jface.resource.JFaceResources.DIALOG_FONT));
            PrefUi.withBold(root, "Install it from ", "Help → Eclipse Marketplace", ", then restart Eclipse.");
            return root;
        }
        PrefUi.audience(root, "For GitHub Copilot only.",
                "Lets Copilot Chat in Eclipse use the debugger, through the Agentic Debugger agent.");

        Map<Action, String> agentButtons = new LinkedHashMap<>();
        agentButtons.put(Action.INSTALL, "Install");
        agentButtons.put(Action.UPDATE, "Update");
        agentButtons.put(Action.REMOVE, "Remove");
        Composite agentGroup = PrefUi.group(root, "Agentic Debugger agent", "The agent you pick in Copilot Chat's mode"
                + " list. Installed once for every workspace (~/.copilot/agents).");
        agent = new PrefUi.Section(agentGroup, agentButtons, this::agentAction);
        duplicates = PrefUi.wrap(agentGroup, "");

        agentOnly();

        Map<Action, String> skillButtons = new LinkedHashMap<>();
        skillButtons.put(Action.INSTALL, "Install");
        skillButtons.put(Action.UPDATE, "Update");
        skillButtons.put(Action.REMOVE, "Remove");
        skill = new PrefUi.Section(PrefUi.group(root, "Skill", "Tells the agent how to debug with these tools. Copilot"
                + " reads the Claude Code copy (~/.claude/skills) when there is one; otherwise it gets its own copy in"
                + " ~/.copilot/skills. Remove deletes only the files this plugin wrote and you did not change."),
                skillButtons, this::skillAction);
        draw();
        return root;
    }

    /**
     * OPEN-60, OPEN-69: first the approval step (Copilot's MCP page), then the box, then what is in effect now, read
     * from Copilot's settings each time the page is drawn (not the last apply).
     */
    private void agentOnly() {
        Composite group = PrefUi.group(root, "Who can use our tools", null);
        Map<Action, String> approvalButtons = new LinkedHashMap<>();
        approvalButtons.put(Action.OPEN, "Open Copilot's MCP page");
        approval = new PrefUi.Section(group, approvalButtons, action -> openCopilotMcpPage());
        boolean now = CopilotIntegration.agentOnly();
        if (agentOnlyAtStart == null) agentOnlyAtStart = now;
        box = PrefUi.check(group, "Only the Agentic Debugger agent, without asking each time",
                "Ticked: only the Agentic Debugger agent can use our tools, and Copilot runs them without asking you"
                        + " first. Unticked: every Copilot mode can use them, and Copilot asks you before each one.", now);
        box.setEnabled(PreferenceStates.agentOnlyEditable(copilot));
        toolsStatus = PrefUi.wrap(group, "");
        box.addListener(SWT.Selection, event -> {
            boolean on = box.getSelection();
            box.setEnabled(false);
            Job job = Job.create("Agentic Debugger: Copilot setting", monitor -> {
                CopilotIntegration.setAgentOnly(on);
                if (!root.isDisposed()) root.getDisplay().asyncExec(() -> {
                    if (root.isDisposed()) return;
                    box.setEnabled(PreferenceStates.agentOnlyEditable(copilot));
                    drawTools();
                });
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        });
    }

    /** The approval line and the live status, from Copilot's settings now. */
    private void drawTools() {
        if (root == null || root.isDisposed()) return;
        Boolean approved = null;
        boolean off = false;
        boolean autoApproved = false;
        if (copilot) {
            try {
                CopilotSettings.Store store = CopilotIntegration.copilotStore();
                approved = CopilotSettings.approval(store, CopilotIntegration.PLUGIN_ID).approved();
                off = CopilotSettings.agentModeToolsOff(store, CopilotAgentTools.SERVER_GROUP,
                        CopilotAgentTools.mcpToolNames(CopilotIntegration.template()));
                autoApproved = CopilotSettings.autoApproved(store, CopilotAgentTools.SERVER_GROUP);
            } catch (Exception | LinkageError unreadable) {
                // shown as not approved / not in effect: the setup check names the cause
            }
        }
        approval.show(PreferenceStates.approval(copilot, approved));
        PreferenceStates.View status = PreferenceStates.toolsStatus(copilot, box.getSelection(),
                PreferenceStates.agentOnlyNote(agentOnlyAtStart, box.getSelection()), off, autoApproved);
        toolsStatus.setText(status.status());
        toolsStatus.setForeground(PrefUi.colour(status.tone()));
        root.layout(true, true);
    }

    /** OPEN-69: back from Copilot's MCP page (or another page): draw the current state again. */
    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible && root != null && !root.isDisposed()) draw();
    }

    /** Opens Copilot's MCP page in this dialog, found by its label; else says where it is. */
    private void openCopilotMcpPage() {
        String id = copilotMcpPageId();
        if (id != null && getContainer() instanceof IWorkbenchPreferenceContainer container
                && container.openPage(id, null)) return;
        MessageDialog.openInformation(getShell(), "Agentic Debugger for GitHub Copilot",
                "Open Preferences > GitHub Copilot > Model Context Protocol (MCP). Under Edit MCP Servers from Plug-ins,"
                        + " approve 'Agentic Debugger for Eclipse IDE'.");
    }

    /** The id of Copilot's preference page whose label contains "Model Context Protocol", or null. */
    static String copilotMcpPageId() {
        try {
            for (Object element : PlatformUI.getWorkbench().getPreferenceManager().getElements(PreferenceManager.PRE_ORDER)) {
                IPreferenceNode node = (IPreferenceNode) element;
                String label = node.getLabelText();
                if (label != null && label.contains("Model Context Protocol")) return node.getId();
            }
        } catch (RuntimeException | LinkageError notFound) { /* fall back to the message */ }
        return null;
    }

    private void agentAction(Action action) {
        String message = null;
        try {
            switch (action) {
                case INSTALL, UPDATE -> {
                    CopilotAgentInstaller.Result r = CopilotAgentInstaller.installForUser(home(), CopilotIntegration.template());
                    if (r == CopilotAgentInstaller.Result.KEPT_EDITED)
                        message = "Your changed file was kept; this version is next to it (.new).";
                    // As before: the agent comes with the skill when there is none yet.
                    try { CopilotSkill.install(); } catch (Exception skillFailure) { /* optional */ }
                }
                case REMOVE -> {
                    CopilotAgentInstaller.Removal r = CopilotAgentInstaller.removeForUser(home(), false);
                    if (r == CopilotAgentInstaller.Removal.EDITED_NEEDS_CONFIRMATION) {
                        boolean confirmed = MessageDialog.openQuestion(getShell(), "Agentic Debugger for GitHub Copilot",
                                "You changed the agent file. Remove it anyway?");
                        if (confirmed) CopilotAgentInstaller.removeForUser(home(), true);
                    }
                }
                default -> { }
            }
        } catch (Exception failure) {
            message = "Could not change the agent: " + failure.getMessage();
        }
        draw();
        if (message != null) MessageDialog.openInformation(getShell(), "Agentic Debugger for GitHub Copilot", message);
    }

    private void skillAction(Action action) {
        String message = null;
        try {
            switch (action) {
                case INSTALL, UPDATE -> CopilotSkill.install();
                case REMOVE -> {
                    String said = CopilotSkill.remove();
                    if (said.contains("kept")) message = said;
                }
                default -> { }
            }
        } catch (Exception failure) {
            message = "The skill could not be changed: " + failure.getMessage();
        }
        draw();
        if (message != null) MessageDialog.openInformation(getShell(), "Skill for GitHub Copilot", message);
    }

    private void draw() {
        if (!copilot) return;
        Path file = CopilotAgentInstaller.userAgentFile(home());
        CopilotAgentInstaller.State state;
        try {
            state = CopilotAgentInstaller.state(file, CopilotIntegration.template());
        } catch (Exception unreadable) {
            state = CopilotAgentInstaller.State.EDITED;   // cannot read it: offer only what keeps the user's file
        }
        agent.show(PreferenceStates.copilotAgent(copilot, state));
        String dup = duplicates();
        duplicates.setText(dup);
        duplicates.setVisible(!dup.isEmpty());
        ((org.eclipse.swt.layout.GridData) duplicates.getLayoutData()).exclude = dup.isEmpty();
        duplicates.setForeground(PrefUi.colour(PreferenceStates.Tone.WARN));

        String bundled = ClaudeIntegration.bundledSkillVersion();
        Path claude = ClaudeIntegration.claudeSkillFolder();
        Path own = home().resolve(".copilot").resolve("skills").resolve("eclipse-debugger");
        PreferenceStates.CopilotSkillState s = PreferenceStates.copilotSkillState(claude, own, bundled);
        String installed = PreferenceStates.version((s == PreferenceStates.CopilotSkillState.USES_CLAUDE_COPY ? claude : own)
                .resolve("SKILL.md"));
        skill.show(PreferenceStates.copilotSkill(copilot, s, installed, bundled));
        drawTools();
    }

    /** A warning when the agent is in several places: Copilot lists each copy separately. */
    static String duplicates() {
        List<String> places = new ArrayList<>();
        if (Files.isRegularFile(CopilotAgentInstaller.userAgentFile(home()))) places.add("all workspaces");
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null && Files.isRegularFile(
                        project.getLocation().toFile().toPath().resolve(CopilotAgentInstaller.AGENT_FILE))) {
                    places.add("project " + project.getName());
                }
            }
        } catch (RuntimeException | LinkageError noWorkspace) { /* outside a workspace */ }
        if (places.size() <= 1) return "";
        return "Installed in " + places.size() + " places: " + String.join(", ", places)
                + ". Copilot lists each copy; keep one.";
    }

    /** A bold heading (used by older callers). */
    static void heading(Composite root, String text) {
        Label label = new Label(root, SWT.WRAP);
        label.setText("\n" + text);
        label.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
    }

    static Label wrap(Composite root, String text) {
        return PrefUi.wrap(root, text);
    }

    private static Path home() { return Path.of(System.getProperty("user.home")); }
}
