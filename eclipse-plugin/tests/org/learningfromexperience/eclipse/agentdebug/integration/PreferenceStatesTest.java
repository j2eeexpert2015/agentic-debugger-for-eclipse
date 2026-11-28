package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Action;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Done;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Registration;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.SkillState;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Tone;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.View;

/**
 * Unit tests (JUnit 5) for {@link PreferenceStates} (OPEN-69): one assertion block per row of the tables in
 * docs/design/preferences-pages.md, above all which buttons are enabled. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class PreferenceStatesTest {

    private static void buttons(View v, Action main, Action... enabled) {
        assertEquals(main, v.main(), "main button of " + v.status());
        Set<Action> want = enabled.length == 0 && main == null ? Set.of()
                : Set.copyOf(java.util.stream.Stream.concat(java.util.Arrays.stream(enabled),
                        main == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(main)).toList());
        assertEquals(want, v.enabled(), "enabled buttons of " + v.status());
    }

    @Test @DisplayName("PS-U1 Claude Code setup: every case, and which buttons are enabled")
    void psU1() {
        String usage = "Last debugging session: today 03:40, Debugging X (finished)";
        buttons(PreferenceStates.claudeSetup(Registration.CHECKING, null, Done.NOTHING, usage), null);
        buttons(PreferenceStates.claudeSetup(Registration.WORKING, null, Done.NOTHING, usage), null);

        View missing = PreferenceStates.claudeSetup(Registration.CLI_MISSING, null, Done.NOTHING, usage);
        buttons(missing, Action.CHECK);
        assertTrue(missing.note().contains("restart Eclipse"), "Eclipse reads the PATH when it starts");

        View failed = PreferenceStates.claudeSetup(Registration.FAILED, "Claude Code CLI timed out.", Done.NOTHING, usage);
        buttons(failed, Action.CHECK);
        assertEquals("Claude Code CLI timed out. Nothing was changed.", failed.note());

        View none = PreferenceStates.claudeSetup(Registration.NOT_SET_UP, null, Done.NOTHING, usage);
        buttons(none, Action.SET_UP, Action.CHECK);
        assertNull(none.note(), "no usage line when not set up");
        assertTrue(PreferenceStates.claudeSetup(Registration.NOT_SET_UP, null, Done.REMOVED, usage).note()
                .contains("already open keeps our tools"), "after Remove");

        View current = PreferenceStates.claudeSetup(Registration.CURRENT, null, Done.NOTHING, usage);
        buttons(current, null, Action.REMOVE, Action.CHECK);
        assertEquals(usage, current.note());
        assertEquals(Tone.OK, current.tone());
        assertTrue(PreferenceStates.claudeSetup(Registration.CURRENT, null, Done.SET_UP, usage).note()
                .startsWith("Start a new Claude Code session"), "just set up");

        buttons(PreferenceStates.claudeSetup(Registration.OLDER, null, Done.NOTHING, usage), Action.UPDATE, Action.CHECK);
        buttons(PreferenceStates.claudeSetup(Registration.FOREIGN, null, Done.NOTHING, usage), null, Action.CHECK);
        for (Registration r : Registration.values()) {
            View v = PreferenceStates.claudeSetup(r, null, Done.NOTHING, usage);
            if (r != Registration.CURRENT) assertTrue(!v.can(Action.REMOVE), "Remove only for our current entry: " + r);
            if (r == Registration.FOREIGN) assertEquals(Set.of(Action.CHECK), v.enabled(), "never touch a foreign entry");
        }
    }

    @Test @DisplayName("PS-U2 Claude Code usage line: debugging now, last session with its outcome, none recorded")
    void psU2() {
        assertEquals("● Debugging now: Debugging OrdersTest, started 03:40",
                PreferenceStates.usage("Debugging OrdersTest", "03:40", "old", "today 01:00", "FINISHED"));
        assertEquals("Last debugging session: today 03:40, Debugging OrdersTest (stopped by you)",
                PreferenceStates.usage(null, null, "Debugging OrdersTest", "today 03:40", "STOPPED"));
        assertTrue(PreferenceStates.usage(null, null, "X", "27 Nov 22:10", "EXPIRED").endsWith("(ended, no activity)"));
        assertTrue(PreferenceStates.usage(null, null, "X", "27 Nov 22:10", "ENDED_WITHOUT_FINISH")
                .endsWith("(ended without finishing)"));
        assertTrue(PreferenceStates.usage(null, null, null, null, null).startsWith("No debugging session recorded yet"),
                "never claims 'not used': sessions before this version were not recorded");
    }

    @Test @DisplayName("PS-U3 Claude Code skill: missing, current, older, changed by the user, working")
    void psU3() {
        buttons(PreferenceStates.claudeSkill(SkillState.MISSING, null, "0.1.7"), Action.INSTALL);
        View current = PreferenceStates.claudeSkill(SkillState.CURRENT, "0.1.7", "0.1.7");
        buttons(current, null);
        assertEquals("✔ eclipse-debugger 0.1.7, up to date", current.status());
        View older = PreferenceStates.claudeSkill(SkillState.OLDER, "0.1.6", "0.1.7");
        buttons(older, Action.UPDATE);
        assertEquals("⚠ 0.1.6 installed, 0.1.7 available", older.status());
        buttons(PreferenceStates.claudeSkill(SkillState.EDITED, "", "0.1.7"), null);
        buttons(PreferenceStates.claudeSkill(SkillState.WORKING, null, null), null);
    }

    @Test @DisplayName("PS-U4 Copilot skill: Claude Code copy is not removed here; own copy; Copilot missing disables all")
    void psU4() {
        buttons(PreferenceStates.copilotSkill(true, PreferenceStates.CopilotSkillState.USES_CLAUDE_COPY, "0.1.7", "0.1.7"), null);
        buttons(PreferenceStates.copilotSkill(true, PreferenceStates.CopilotSkillState.MISSING, null, "0.1.7"), Action.INSTALL);
        buttons(PreferenceStates.copilotSkill(true, PreferenceStates.CopilotSkillState.CURRENT, "0.1.7", "0.1.7"), null,
                Action.REMOVE);
        buttons(PreferenceStates.copilotSkill(true, PreferenceStates.CopilotSkillState.OLDER, "0.1.6", "0.1.7"), Action.UPDATE,
                Action.REMOVE);
        buttons(PreferenceStates.copilotSkill(true, PreferenceStates.CopilotSkillState.WORKING, null, null), null);
        for (PreferenceStates.CopilotSkillState s : PreferenceStates.CopilotSkillState.values()) {
            View v = PreferenceStates.copilotSkill(false, s, "0.1.6", "0.1.7");
            assertEquals(Set.of(), v.enabled(), "Copilot not installed: " + s);
            assertEquals(Tone.MUTED, v.tone());
        }
    }

    @Test @DisplayName("PS-U5 Copilot agent and the agent-only box: each file state; Copilot missing; restart note only after a change")
    void psU5() {
        buttons(PreferenceStates.copilotAgent(true, CopilotAgentInstaller.State.MISSING), Action.INSTALL);
        buttons(PreferenceStates.copilotAgent(true, CopilotAgentInstaller.State.CURRENT), null, Action.REMOVE);
        buttons(PreferenceStates.copilotAgent(true, CopilotAgentInstaller.State.OLDER), Action.UPDATE, Action.REMOVE);
        buttons(PreferenceStates.copilotAgent(true, CopilotAgentInstaller.State.EDITED), null, Action.UPDATE, Action.REMOVE);
        buttons(PreferenceStates.copilotAgent(true, null), null);
        for (CopilotAgentInstaller.State s : CopilotAgentInstaller.State.values())
            assertEquals(Set.of(), PreferenceStates.copilotAgent(false, s).enabled(), "Copilot not installed: " + s);
        assertTrue(PreferenceStates.agentOnlyEditable(true));
        assertTrue(!PreferenceStates.agentOnlyEditable(false));
        assertNull(PreferenceStates.agentOnlyNote(true, true));
        assertEquals("Restart Eclipse to apply this change.", PreferenceStates.agentOnlyNote(true, false));
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path home;

    private void write(java.nio.file.Path file, String text) throws Exception {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, text);
    }

    @Test @DisplayName("PS-U6 skill states read from the folders: missing, current, older, kept user copy (.new); Copilot's view of them")
    void psU6() throws Exception {
        java.nio.file.Path claude = home.resolve(".claude/skills/eclipse-debugger");
        java.nio.file.Path copilot = home.resolve(".copilot/skills/eclipse-debugger");
        assertEquals(PreferenceStates.SkillState.MISSING, PreferenceStates.skillState(claude, "0.1.7"));
        assertEquals(PreferenceStates.CopilotSkillState.MISSING, PreferenceStates.copilotSkillState(claude, copilot, "0.1.7"));
        write(copilot.resolve("SKILL.md"), "# Eclipse debugger — skill v0.1.6");
        assertEquals(PreferenceStates.CopilotSkillState.OLDER, PreferenceStates.copilotSkillState(claude, copilot, "0.1.7"));
        write(copilot.resolve("SKILL.md"), "# Eclipse debugger — skill v0.1.7");
        assertEquals(PreferenceStates.CopilotSkillState.CURRENT, PreferenceStates.copilotSkillState(claude, copilot, "0.1.7"));
        write(claude.resolve("SKILL.md"), "# Eclipse debugger — skill v0.1.6 (candidate)");
        assertEquals("0.1.6", PreferenceStates.version(claude.resolve("SKILL.md")));
        assertEquals(PreferenceStates.SkillState.OLDER, PreferenceStates.skillState(claude, "0.1.7"));
        assertEquals(PreferenceStates.CopilotSkillState.USES_CLAUDE_COPY,
                PreferenceStates.copilotSkillState(claude, copilot, "0.1.7"), "Copilot reads the Claude Code copy first");
        write(claude.resolve("SKILL.md"), "# Eclipse debugger — skill v0.1.7");
        assertEquals(PreferenceStates.SkillState.CURRENT, PreferenceStates.skillState(claude, "0.1.7"));
        assertEquals(PreferenceStates.SkillState.CURRENT, PreferenceStates.skillState(claude, null), "bundled unknown");
        write(claude.resolve("references/java-debugging.md.new"), "ours");
        assertEquals(PreferenceStates.SkillState.EDITED, PreferenceStates.skillState(claude, "0.1.7"),
                "a kept user copy: nothing that writes is offered");
    }

    @Test @DisplayName("PS-U7 setup check summary: problems first and numbered, failures before warnings, then the OK checks by group")
    void psU7() {
        java.util.List<SetupCheck.Row> rows = java.util.List.of(
                new SetupCheck.Row("Eclipse", SetupCheck.Level.OK, "Bridge running on port 8765.", ""),
                new SetupCheck.Row("MCP server", SetupCheck.Level.OK, "Server jar staged.", ""),
                new SetupCheck.Row("GitHub Copilot", SetupCheck.Level.OK, "Copilot 0.22.0 (tested).", ""),
                new SetupCheck.Row("GitHub Copilot", SetupCheck.Level.WARN, "Server not approved (Denied) in Copilot.", "Approve it."),
                new SetupCheck.Row("GitHub Copilot", SetupCheck.Level.INFO, "No Agentic Debugger agent installed.", "Install it."),
                new SetupCheck.Row("Claude Code", SetupCheck.Level.FAIL, "Not set up.", "Set it up."),
                new SetupCheck.Row("Claude Code", SetupCheck.Level.OK, "Skill 0.1.7.", ""));
        String s = SetupCheck.summary(rows);
        assertEquals("⚠ 2 things to fix\n"
                + " 1. ✖ Claude Code: Not set up.\n      → Set it up.\n"
                + " 2. ⚠ GitHub Copilot: Server not approved (Denied) in Copilot.\n      → Approve it.\n\n"
                + SetupCheck.HINT + "\n\n"
                + "Everything else is fine (4 checks)\n"
                + "  Eclipse:  ✔ 2 OK\n  Claude Code:  ✔ 1 OK\n  GitHub Copilot:  ✔ 1 OK\n"
                + "  ℹ GitHub Copilot: No Agentic Debugger agent installed.\n      → Install it.", s);
        assertTrue(SetupCheck.summary(rows.subList(3, 4)).startsWith("⚠ 1 thing to fix\n 1. ⚠ GitHub Copilot"));
        assertTrue(SetupCheck.summary(rows.subList(3, 4)).endsWith(SetupCheck.HINT), "nothing fine to list");
        String all = SetupCheck.summary(rows.subList(0, 3));
        assertEquals("✔ All 3 checks OK\n  Eclipse:  ✔ 2 OK\n  GitHub Copilot:  ✔ 1 OK", all);
        assertTrue(SetupCheck.summary(java.util.List.of()).startsWith("Nothing to check yet"));
    }

    @Test @DisplayName("PS-U8 when: today, yesterday, a date; unreadable is null")
    void psU8() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.parse("2026-11-28T04:30:00+05:30[Asia/Kolkata]");
        assertEquals("today 03:40", PreferenceStates.when("2026-11-27T22:10:00Z", now));
        assertEquals("yesterday 22:10", PreferenceStates.when("2026-11-27T16:40:00Z", now));
        assertEquals("25 Nov 09:05", PreferenceStates.when("2026-11-25T03:35:00Z", now));
        assertNull(PreferenceStates.when("not a time", now));
    }

    @Test @DisplayName("PS-U9 setup check colours: headline and counts green or amber, numbered problems by level, notes blue, what to do plain")
    void psU9() {
        String text = "⚠ 2 things to fix\n 1. ✖ Claude Code: Not set up.\n      → Set it up.\n"
                + " 2. ⚠ GitHub Copilot: Not approved.\n      → Approve it.\n\n" + SetupCheck.HINT + "\n\n"
                + "Everything else is fine (3 checks)\n  Eclipse:  ✔ 2 OK\n  ℹ GitHub Copilot: No agent.";
        java.util.List<SetupCheck.Span> spans = SetupCheck.spans(text);
        java.util.function.Function<SetupCheck.Span, String> part = sp -> text.substring(sp.start(), sp.start() + sp.length());
        java.util.List<String> got = spans.stream().map(sp -> sp.style() + ":" + part.apply(sp)).toList();
        assertEquals(java.util.List.of("WARN:⚠ 2 things to fix", "FAIL: 1. ✖ Claude Code: Not set up.",
                "WARN: 2. ⚠ GitHub Copilot: Not approved.", "OK:Everything else is fine (3 checks)", "BOLD:Eclipse:",
                "OK:✔ 2 OK", "INFO:  ℹ GitHub Copilot: No agent."), got);
        String all = "✔ All 3 checks OK";
        assertEquals(java.util.List.of("OK:✔ All 3 checks OK"),
                SetupCheck.spans(all).stream().map(sp -> sp.style() + ":" + all.substring(sp.start(), sp.start() + sp.length())).toList());
    }

    @Test @DisplayName("PS-U10 approval line: approved has no button; denied or not offered opens Copilot's MCP page; no Copilot greys it")
    void psU10() {
        PreferenceStates.View yes = PreferenceStates.approval(true, true);
        assertEquals("Server approved in Copilot: ✔ Approved", yes.status());
        assertTrue(yes.enabled().isEmpty() && yes.main() == null);
        for (Boolean answer : new Boolean[] {false, null}) {
            PreferenceStates.View no = PreferenceStates.approval(true, answer);
            assertEquals("Server approved in Copilot: ✖ Not yet", no.status());
            assertEquals(PreferenceStates.Action.OPEN, no.main());
            assertEquals(java.util.Set.of(PreferenceStates.Action.OPEN), no.enabled());
        }
        assertTrue(PreferenceStates.approval(false, null).enabled().isEmpty());
    }

    @Test @DisplayName("PS-U11 live status under the box: in effect, restart needed, box off, the restart note first")
    void psU11() {
        assertEquals("✔ In effect: only the Agentic Debugger agent uses our tools, without asking each time",
                PreferenceStates.toolsStatus(true, true, null, true, true).status());
        assertEquals("⚠ Not in effect yet: restart Eclipse. Copilot's Agent mode can still use our tools.",
                PreferenceStates.toolsStatus(true, true, null, false, true).status());
        assertEquals("⚠ Not in effect yet: restart Eclipse. Copilot's Agent mode can still use our tools.",
                PreferenceStates.toolsStatus(true, true, null, false, false).status());
        assertEquals("⚠ Not in effect yet: restart Eclipse. Copilot asks before each of our tools.",
                PreferenceStates.toolsStatus(true, true, null, true, false).status());
        assertEquals("Every Copilot mode can use our tools; Copilot asks before each one.",
                PreferenceStates.toolsStatus(true, false, null, false, false).status());
        assertEquals("Restart Eclipse to apply this change.",
                PreferenceStates.toolsStatus(true, true, "Restart Eclipse to apply this change.", true, true).status());
        assertTrue(PreferenceStates.toolsStatus(false, true, null, true, true).enabled().isEmpty());
    }

    @Test @DisplayName("PS-U12 welcome after installing: a line per agent, Set up now opens the found agent's page, no agent only OK")
    void psU12() {
        PreferenceStates.Welcome both = PreferenceStates.welcome(true, true);
        assertEquals("Agentic Debugger is installed.", both.title());
        assertEquals("Connect it to your AI agent:", both.intro());
        assertEquals(java.util.List.of(new PreferenceStates.AgentLine("Claude Code found", true),
                new PreferenceStates.AgentLine("GitHub Copilot found", true)), both.agents());
        assertEquals(PreferenceStates.WelcomePage.CLAUDE_CODE, both.open(), "Claude Code first when both are found");
        assertEquals(java.util.List.of("Later", "Set up now"), both.buttons());
        assertTrue(both.showLaterPath());

        PreferenceStates.Welcome claude = PreferenceStates.welcome(true, false);
        assertEquals(new PreferenceStates.AgentLine("GitHub Copilot not installed", false), claude.agents().get(1));
        assertEquals(PreferenceStates.WelcomePage.CLAUDE_CODE, claude.open());

        PreferenceStates.Welcome copilot = PreferenceStates.welcome(false, true);
        assertEquals(new PreferenceStates.AgentLine("Claude Code not installed", false), copilot.agents().get(0));
        assertEquals(PreferenceStates.WelcomePage.COPILOT, copilot.open());
        assertEquals(java.util.List.of("Later", "Set up now"), copilot.buttons());

        PreferenceStates.Welcome none = PreferenceStates.welcome(false, false);
        assertEquals("It works with Claude Code or GitHub Copilot for Eclipse.\nInstall one, then restart Eclipse.",
                none.intro());
        assertTrue(none.agents().isEmpty());
        assertNull(none.open());
        assertEquals(java.util.List.of("OK"), none.buttons());
        assertTrue(!none.showLaterPath(), "nothing to set up: no 'later' hint");
        assertEquals("Window → Preferences → Agentic Debugger", PreferenceStates.PREFERENCES_PATH);
    }
}
