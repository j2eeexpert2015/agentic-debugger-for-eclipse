package org.learningfromexperience.eclipse.agentdebug.integration;

import java.util.EnumSet;
import java.util.Set;

/**
 * OPEN-69 (Ayan, 28 Nov 04:21: "cover all the cases; never enable what must not be enabled"): what each section of the
 * preference pages shows in each case, and which buttons are enabled. Pure, no SWT: every case is a unit test
 * (PS-U1..U5), and the pages only draw what this class says. Design: docs/design/preferences-pages.md.
 *
 * <p>Rules that hold in every section: while a check or an action runs, nothing is enabled (one action at a time); an
 * entry or a file that is not ours is never changed; a button is enabled only when its action can work.
 *
 * @author Ayan Dutta
 */
public final class PreferenceStates {

    private PreferenceStates() { }

    /** How a status line is coloured. */
    public enum Tone { OK, WARN, BAD, MUTED }

    /** The buttons of a section; each page shows only the ones it has. */
    public enum Action { SET_UP, UPDATE, REMOVE, CHECK, INSTALL, OPEN }

    /**
     * What a section shows.
     *
     * @param status  the status line ("✔ Set up")
     * @param note    a second line, or null
     * @param enabled the buttons that may be clicked now
     * @param main    the button that fixes the state (drawn as the default button), or null
     */
    public record View(String status, Tone tone, String note, Set<Action> enabled, Action main) {
        public boolean can(Action action) {
            return enabled.contains(action);
        }
    }

    private static View view(String status, Tone tone, String note, Action main, Action... enabled) {
        Set<Action> set = EnumSet.noneOf(Action.class);
        for (Action a : enabled) set.add(a);
        if (main != null) set.add(main);
        return new View(status, tone, note, Set.copyOf(set), main);
    }

    // ---- Claude Code: setup ---------------------------------------------------------------------------------------

    /** Claude Code's entry for our server, from {@code claude mcp get eclipse-debugger}. */
    public enum Registration {
        /** The check is running. */
        CHECKING,
        /** Set up, Update or Remove is running. */
        WORKING,
        /** No {@code claude} command found on Eclipse's PATH (or in ~/.local/bin). */
        CLI_MISSING,
        /** The check failed (time-out, error); nothing was changed. */
        FAILED,
        /** No entry. */
        NOT_SET_UP,
        /** Our entry, pointing to this plugin's server. */
        CURRENT,
        /** Our entry from an older version of the plugin. */
        OLDER,
        /** An entry with our name that is not ours. */
        FOREIGN
    }

    /** What the Setup section says after a click, so the user knows what follows from it. */
    public enum Done { NOTHING, SET_UP, REMOVED }

    /**
     * The Setup section of the Claude Code page.
     *
     * @param detail what failed (FAILED), else ignored
     * @param done   the last action of this page, for its note
     * @param usage  what Eclipse knows about Claude Code's debugging ({@link #usage}), shown only when set up
     */
    public static View claudeSetup(Registration r, String detail, Done done, String usage) {
        return switch (r) {
            case CHECKING -> view("Checking…", Tone.MUTED, null, null);
            case WORKING -> view("Working…", Tone.MUTED, null, null);
            case CLI_MISSING -> view("✖ Claude Code not found", Tone.BAD,
                    "Install Claude Code, then restart Eclipse (Eclipse reads the PATH when it starts).", Action.CHECK);
            case FAILED -> view("⚠ Couldn't check", Tone.WARN,
                    (detail == null || detail.isBlank() ? "Claude Code did not answer." : detail)
                            + " Nothing was changed.", Action.CHECK);
            case NOT_SET_UP -> view("✖ Not set up", Tone.BAD, done == Done.REMOVED
                    ? "Removed. A Claude Code session that is already open keeps our tools until it ends." : null,
                    Action.SET_UP, Action.CHECK);
            case CURRENT -> view("✔ Set up", Tone.OK, done == Done.SET_UP
                    ? "Start a new Claude Code session to use it; sessions already open don't see the change." : usage,
                    null, Action.REMOVE, Action.CHECK);
            // ClaudeIntegration.remove() removes only an entry that points to the current server, so no Remove here.
            case OLDER -> view("⚠ Set up by an older version of this plugin", Tone.WARN,
                    "Claude Code would start the old server. Update points it to this version.", Action.UPDATE,
                    Action.CHECK);
            case FOREIGN -> view("⚠ Another 'eclipse-debugger' entry exists", Tone.WARN,
                    "It isn't ours, so it was left unchanged. See it with: claude mcp get eclipse-debugger",
                    null, Action.CHECK);
        };
    }

    /**
     * The second line of the Setup section when Claude Code is set up.
     *
     * @param nowTitle    the title of Claude Code's investigation running now, or null
     * @param nowStarted  when it started ("03:40")
     * @param lastTitle   the title of Claude Code's last finished investigation, or null when none is recorded
     * @param lastWhen    when it finished ("today 03:40", "27 Nov 22:10")
     * @param lastOutcome its outcome (ActivityStore: FINISHED, STOPPED, EXPIRED, ENDED_WITHOUT_FINISH)
     */
    public static String usage(String nowTitle, String nowStarted, String lastTitle, String lastWhen, String lastOutcome) {
        if (nowTitle != null) return "● Debugging now: " + nowTitle + (nowStarted == null ? "" : ", started " + nowStarted);
        if (lastTitle != null) return "Last debugging session: " + lastWhen + ", " + lastTitle + " (" + outcome(lastOutcome) + ")";
        return "No debugging session recorded yet. Start Claude Code in a terminal and ask it to debug something.";
    }

    /** "today 03:40", "yesterday 22:10", "27 Nov 22:10" for an ISO instant; null when it cannot be read. */
    public static String when(String isoInstant, java.time.ZonedDateTime now) {
        try {
            java.time.ZonedDateTime at = java.time.Instant.parse(isoInstant).atZone(now.getZone());
            String time = String.format(java.util.Locale.ROOT, "%02d:%02d", at.getHour(), at.getMinute());
            java.time.LocalDate day = at.toLocalDate();
            if (day.equals(now.toLocalDate())) return "today " + time;
            if (day.equals(now.toLocalDate().minusDays(1))) return "yesterday " + time;
            return day.getDayOfMonth() + " " + java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.ENGLISH)
                    .format(day) + " " + time;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    static String outcome(String outcome) {
        if (outcome == null) return "ended";
        return switch (outcome) {
            case "FINISHED" -> "finished";
            case "STOPPED" -> "stopped by you";
            case "EXPIRED" -> "ended, no activity";
            default -> "ended without finishing";
        };
    }

    // ---- skill ----------------------------------------------------------------------------------------------------

    /** A skill folder: missing, ours and current, ours and older, or changed by the user (the plugin keeps it). */
    public enum SkillState { WORKING, MISSING, CURRENT, OLDER, EDITED }

    /**
     * The Skill section of the Claude Code page ({@code ~/.claude/skills/eclipse-debugger}). The plugin also refreshes
     * it at every Eclipse start once Claude Code is set up; a copy the user changed is kept (SkillInstaller).
     */
    public static View claudeSkill(SkillState s, String installed, String bundled) {
        return switch (s) {
            case WORKING -> view("Working…", Tone.MUTED, null, null);
            case MISSING -> view("✖ Not installed", Tone.BAD, null, Action.INSTALL);
            case CURRENT -> view("✔ eclipse-debugger " + v(installed) + ", up to date", Tone.OK,
                    "Updated by itself when Eclipse starts.", null);
            case OLDER -> view("⚠ " + v(installed) + " installed, " + v(bundled) + " available", Tone.WARN, null,
                    Action.UPDATE);
            case EDITED -> view("⚠ Changed by you; kept", Tone.WARN,
                    "This plugin's version is next to it as .new files; compare and merge by hand.", null);
        };
    }

    /** Where Copilot finds the skill. */
    public enum CopilotSkillState { WORKING, USES_CLAUDE_COPY, MISSING, CURRENT, OLDER }

    /** The Skill section of the Copilot page ({@code ~/.copilot/skills}, or the Claude Code copy). */
    public static View copilotSkill(boolean copilotInstalled, CopilotSkillState s, String installed, String bundled) {
        View v = switch (s) {
            case WORKING -> view("Working…", Tone.MUTED, null, null);
            // Not ours to remove from here: it is Claude Code's copy (Claude Code page).
            case USES_CLAUDE_COPY -> view("✔ Uses the Claude Code copy (~/.claude/skills)", Tone.OK,
                    "Managed on the Claude Code page.", null);
            case MISSING -> view("✖ Not installed", Tone.BAD, null, Action.INSTALL);
            case CURRENT -> view("✔ eclipse-debugger " + v(installed) + ", up to date (~/.copilot/skills)", Tone.OK,
                    null, null, Action.REMOVE);
            case OLDER -> view("⚠ " + v(installed) + " installed, " + v(bundled) + " available", Tone.WARN, null,
                    Action.UPDATE, Action.REMOVE);
        };
        return copilotInstalled ? v : off(v);
    }

    /**
     * The state of the Claude Code skill folder: no SKILL.md is MISSING; a {@code .new} file anywhere in it means the
     * plugin kept the user's changed copy (SkillInstaller) and is EDITED; else CURRENT or OLDER by the version in
     * SKILL.md compared with the plugin's ({@code bundled}, null when unknown: CURRENT).
     */
    public static SkillState skillState(java.nio.file.Path folder, String bundled) {
        java.nio.file.Path skill = folder.resolve("SKILL.md");
        if (!java.nio.file.Files.isRegularFile(skill)) return SkillState.MISSING;
        try (var files = java.nio.file.Files.walk(folder)) {
            if (files.anyMatch(f -> f.getFileName().toString().endsWith(".new"))) return SkillState.EDITED;
        } catch (java.io.IOException | RuntimeException unreadable) {
            return SkillState.EDITED;   // cannot tell: offer nothing that would write
        }
        String installed = version(skill);
        return bundled == null || bundled.equals(installed) ? SkillState.CURRENT : SkillState.OLDER;
    }

    /**
     * Where Copilot finds the skill: the Claude Code copy when there is one (Copilot reads ~/.claude/skills), else its
     * own copy in ~/.copilot/skills (CopilotSkill), else none.
     */
    public static CopilotSkillState copilotSkillState(java.nio.file.Path claudeFolder, java.nio.file.Path copilotFolder,
            String bundled) {
        if (java.nio.file.Files.isRegularFile(claudeFolder.resolve("SKILL.md"))) return CopilotSkillState.USES_CLAUDE_COPY;
        java.nio.file.Path skill = copilotFolder.resolve("SKILL.md");
        if (!java.nio.file.Files.isRegularFile(skill)) return CopilotSkillState.MISSING;
        String installed = version(skill);
        return bundled == null || bundled.equals(installed) ? CopilotSkillState.CURRENT : CopilotSkillState.OLDER;
    }

    /** The version in a SKILL.md ("0.1.7"), "" when it has none or cannot be read. */
    public static String version(java.nio.file.Path skillMd) {
        try {
            String v = SetupCheck.skillVersion(java.nio.file.Files.readString(skillMd));
            return v == null ? "" : v;
        } catch (java.io.IOException | RuntimeException unreadable) {
            return "";
        }
    }

    // ---- GitHub Copilot ---------------------------------------------------------------------------------------------

    /**
     * The agent section of the Copilot page ({@code ~/.copilot/agents/agentic_debugger.agent.md}).
     *
     * @param state the agent file's state; null while an action runs
     */
    public static View copilotAgent(boolean copilotInstalled, CopilotAgentInstaller.State state) {
        View v = state == null ? view("Working…", Tone.MUTED, null, null) : switch (state) {
            case MISSING -> view("✖ Not installed", Tone.BAD, null, Action.INSTALL);
            case CURRENT -> view("✔ Installed for all workspaces", Tone.OK,
                    "In Copilot Chat, pick \"Agentic Debugger\" in the mode list.", null, Action.REMOVE);
            case OLDER -> view("⚠ Older version installed", Tone.WARN, null, Action.UPDATE, Action.REMOVE);
            // Update keeps the user's file and writes ours next to it (.new); Remove asks first.
            case EDITED -> view("⚠ Installed, changed by you", Tone.WARN,
                    "Update keeps your file and puts this version next to it (.new).", null, Action.UPDATE,
                    Action.REMOVE);
        };
        return copilotInstalled ? v : off(v);
    }

    /** Whether the "Only the Agentic Debugger agent" box may be changed. */
    public static boolean agentOnlyEditable(boolean copilotInstalled) {
        return copilotInstalled;
    }

    /** The note under that box: only after a change in this Eclipse session (applied when Copilot starts). */
    public static String agentOnlyNote(boolean atStart, boolean now) {
        return atStart == now ? null : "Restart Eclipse to apply this change.";
    }

    /**
     * OPEN-69: the first line of "Who can use our tools": whether the user approved our server in Copilot (the step
     * that comes first). Not approved: OPEN (Copilot's MCP page) is the main button; approved: no button.
     *
     * @param approved Copilot's answer: true approved, false denied, null not offered yet
     */
    public static View approval(boolean copilotInstalled, Boolean approved) {
        if (!copilotInstalled) return off(view("Server approved in Copilot: Copilot is not installed", Tone.MUTED, null, null));
        if (Boolean.TRUE.equals(approved)) return view("Server approved in Copilot: ✔ Approved", Tone.OK, null, null);
        return view("Server approved in Copilot: ✖ Not yet", Tone.BAD, null, Action.OPEN);
    }

    /**
     * OPEN-69: the live line under the agent-only box, from Copilot's settings now (not from the last apply).
     *
     * @param boxOn        the box as saved
     * @param restartNote  {@link #agentOnlyNote}: after a change in this session it takes priority
     * @param off          our tools are off for Copilot's Agent mode
     * @param autoApproved our server is in Copilot's auto-approve list
     */
    public static View toolsStatus(boolean copilotInstalled, boolean boxOn, String restartNote, boolean off,
                                   boolean autoApproved) {
        if (!copilotInstalled) return off(view("", Tone.MUTED, null, null));
        if (restartNote != null) return view(restartNote, Tone.WARN, null, null);
        if (!boxOn) return view("Every Copilot mode can use our tools; Copilot asks before each one.", Tone.MUTED, null, null);
        if (off && autoApproved)
            return view("✔ In effect: only the Agentic Debugger agent uses our tools, without asking each time", Tone.OK,
                    null, null);
        if (off) return view("⚠ Not in effect yet: restart Eclipse. Copilot asks before each of our tools.", Tone.WARN,
                null, null);
        return view("⚠ Not in effect yet: restart Eclipse. Copilot's Agent mode can still use our tools.", Tone.WARN,
                null, null);
    }

    /** Copilot is not installed: the same status, greyed, nothing enabled. */
    private static View off(View v) {
        return new View(v.status(), Tone.MUTED, v.note(), Set.of(), null);
    }

    private static String v(String version) {
        return version == null || version.isEmpty() ? "(no version)" : version;
    }
}
