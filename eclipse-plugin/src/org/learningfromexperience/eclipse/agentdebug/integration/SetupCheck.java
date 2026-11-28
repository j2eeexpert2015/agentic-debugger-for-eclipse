package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPEN-61: checks whether the plugin's setup for GitHub Copilot and Claude Code is complete, so a user (or a bug
 * report) can see in one table what works and what to do. Two uses:
 * <ul>
 *   <li>a quiet check about 30 seconds after Eclipse starts, only for the integrations in use, without starting any
 *       process ({@code probe} null); problems go to the Error Log and the status bar;</li>
 *   <li>"Check setup" on the preferences page: the same rows plus a real start of the MCP server ({@link #probeServer}),
 *       whose tool list must match the agent's, and Claude Code's own answer.</li>
 * </ul>
 * It checks the setup, not what a model will do in a chat: the per-version Copilot check in docs/release-checks.md
 * stays. Pure Java over a {@link Snapshot}: unit tests SC-U1..U6 in {@code SetupCheckTest}.
 *
 * @author Ayan Dutta
 */
public final class SetupCheck {

    public enum Level { OK, INFO, WARN, FAIL }

    /** One line of the table: area ("Eclipse", "MCP server", "GitHub Copilot", "Claude Code"), result, what to do. */
    public record Row(String area, Level level, String text, String fix) { }

    /** The result of starting the MCP server once: its tool names, or why it failed. */
    public record Probe(List<String> tools, String error) { }

    /**
     * Everything the check looks at, gathered by the Eclipse side.
     *
     * @param agentFiles     label (project name, or "all workspaces") to agent file path
     * @param launcher       the launcher script Copilot should run (Windows), else null
     * @param claudeSkill    the version in ~/.claude/skills/eclipse-debugger/SKILL.md ("0.1.7"), "" when the file has
     *                       no version line, null when the skill is missing
     * @param bundledSkill   the version of the skill packed into this plugin, null when unknown
     * @param claudeStatus   Claude Code's answer ("Configured (user scope)", ...), null when not asked
     * @param probe          a real start of the MCP server, null in the quiet start-up check
     * @param isolatedAtStart true when this Eclipse start switched our tools off for Copilot's Agent mode
     *                       ({@link CopilotIntegration#isolatedThisSession()}); a later "on" then comes from an approval
     *                       in this session and a restart fixes it
     */
    public record Snapshot(int port, boolean bridgeListening, boolean jarStaged, String template,
                           boolean copilotInstalled, String copilotVersion, boolean agentOnly,
                           CopilotSettings.Store copilot, String pluginId, Path launcher,
                           Map<String, Path> agentFiles, boolean claudeConfigured, String claudeSkill,
                           String bundledSkill, String claudeStatus, Probe probe,
                           boolean isolatedAtStart) { }

    /** Copilot versions the isolation was checked with (docs/release-checks.md). */
    static final List<String> TESTED_COPILOT = List.of("0.22.");

    private SetupCheck() { }

    public static List<Row> rows(Snapshot s) {
        List<Row> rows = new ArrayList<>();
        List<String> agentTools = CopilotAgentTools.mcpToolNames(s.template());
        boolean copilotInUse = s.copilotInstalled() && (anyAgent(s.agentFiles()) || approval(s).approved() != null);
        boolean quiet = s.probe() == null && s.claudeStatus() == null;
        // The quiet start-up check says nothing to users who use neither integration.
        if (quiet && !copilotInUse && !s.claudeConfigured()) return rows;
        rows.add(s.bridgeListening()
                ? new Row("Eclipse", Level.OK, "Bridge running on port " + s.port() + ".", "")
                : new Row("Eclipse", Level.FAIL, "Bridge not running (port " + s.port() + ").",
                        "Restart Eclipse; if it stays, see the Error Log (another program may use the port)."));
        rows.add(s.jarStaged()
                ? new Row("MCP server", Level.OK, "Server jar staged.", "")
                : new Row("MCP server", Level.WARN, "Server jar not staged yet.",
                        "It is staged when Claude Code or GitHub Copilot is set up (their pages under Agentic Debugger), or at the next Eclipse start."));
        if (s.probe() != null) {
            if (s.probe().error() != null) {
                rows.add(new Row("MCP server", Level.FAIL, "Could not start the server: " + s.probe().error(),
                        "Copy the report and open an issue; the Eclipse console may say more."));
            } else {
                TreeSet<String> missing = new TreeSet<>(agentTools);
                missing.removeAll(s.probe().tools());
                TreeSet<String> extra = new TreeSet<>(s.probe().tools());
                extra.removeAll(agentTools);
                if (missing.isEmpty() && extra.isEmpty()) {
                    rows.add(new Row("MCP server", Level.OK, "Server started; its " + s.probe().tools().size()
                            + " tools match the Copilot agent's list.", ""));
                } else {
                    rows.add(new Row("MCP server", Level.FAIL, "Server started, but its tools differ from the agent's"
                            + " list" + (missing.isEmpty() ? "" : "; not offered: " + missing)
                            + (extra.isEmpty() ? "" : "; not in the agent: " + extra) + ".",
                            "Plugin and agent template do not match: reinstall the plugin, then update the agent."));
                }
            }
        }
        if (s.copilotInstalled() && (copilotInUse || s.probe() != null)) copilotRows(s, agentTools, rows);
        if (s.claudeConfigured() || s.claudeStatus() != null) claudeRows(s, rows);
        return rows;
    }

    private static void copilotRows(Snapshot s, List<String> agentTools, List<Row> rows) {
        String version = s.copilotVersion() == null ? "unknown" : s.copilotVersion();
        boolean tested = s.copilotVersion() != null && TESTED_COPILOT.stream().anyMatch(s.copilotVersion()::startsWith);
        rows.add(tested
                ? new Row("GitHub Copilot", Level.OK, "Copilot " + version + " (tested).", "")
                : new Row("GitHub Copilot", Level.WARN, "Copilot " + version + " has not been verified with this plugin.",
                        "Run the short Copilot check in docs/release-checks.md (Agent mode must not use our tools)."));
        CopilotSettings.Approval approval = approval(s);
        if (Boolean.TRUE.equals(approval.approved())) {
            rows.add(new Row("GitHub Copilot", Level.OK, "Server approved in Copilot.", ""));
        } else {
            rows.add(new Row("GitHub Copilot", Level.WARN, approval.approved() == null
                    ? "Copilot has not offered our server for approval yet." : "Server not approved (Denied) in Copilot.",
                    "Preferences > GitHub Copilot > Model Context Protocol (MCP) > Edit MCP Servers from Plug-ins:"
                            + " approve 'Agentic Debugger for Eclipse IDE'."));
        }
        if (s.launcher() != null) {
            boolean present = Files.isRegularFile(s.launcher());
            boolean same = approval.command() == null || samePath(approval.command(), s.launcher().toString());
            if (present && same) rows.add(new Row("GitHub Copilot", Level.OK, "Launcher " + s.launcher() + " in place.", ""));
            else rows.add(new Row("GitHub Copilot", Level.WARN, present
                    ? "Copilot approved a different command (" + approval.command() + ") than " + s.launcher() + "."
                    : "Launcher " + s.launcher() + " missing.",
                    "Restart Eclipse; Copilot then asks once to approve the current launcher."));
        }
        int agents = 0;
        for (Map.Entry<String, Path> e : s.agentFiles().entrySet()) {
            CopilotAgentInstaller.State state;
            String text;
            try {
                state = CopilotAgentInstaller.state(e.getValue(), s.template());
                text = state == CopilotAgentInstaller.State.MISSING ? null : Files.readString(e.getValue());
            } catch (IOException unreadable) {
                rows.add(new Row("GitHub Copilot", Level.WARN, "Agent file not readable: " + e.getValue(), ""));
                continue;
            }
            if (state == CopilotAgentInstaller.State.MISSING) continue;
            agents++;
            String where = "Agent (" + e.getKey() + ")";
            switch (state) {
                case CURRENT -> rows.add(new Row("GitHub Copilot", Level.OK, where + " up to date.", ""));
                case OLDER -> rows.add(new Row("GitHub Copilot", Level.WARN, where + " is an older version.",
                        "Agentic Debugger > GitHub Copilot page: Update."));
                default -> {
                    boolean ok = CopilotAgentTools.userOnly(text)
                            && CopilotAgentTools.mcpToolNames(text).containsAll(agentTools);
                    rows.add(new Row("GitHub Copilot", ok ? Level.INFO : Level.WARN, where + " changed by you"
                            + (ok ? "; it still has our tool list and the user-only lines."
                                    : "; it lacks our tool list or the user-only lines, so Agent mode may start it or"
                                            + " it may lose our tools."),
                            ok ? "" : "Compare with agentic_debugger.agent.md.new next to it, or remove and add it again."));
                }
            }
        }
        if (agents == 0) {
            rows.add(new Row("GitHub Copilot", Level.INFO, "No Agentic Debugger agent installed.",
                    "Agentic Debugger > GitHub Copilot page: Install; or project menu > Agentic Debugger > Add."));
        } else if (agents > 1) {
            rows.add(new Row("GitHub Copilot", Level.WARN, "The agent is installed " + agents
                    + " times; Copilot lists each copy separately.",
                    "Keep one: the all-workspaces copy, or one per project."));
        }
        boolean off = s.copilot() != null
                && CopilotSettings.agentModeToolsOff(s.copilot(), CopilotAgentTools.SERVER_GROUP, agentTools);
        boolean autoApproved = s.copilot() != null
                && CopilotSettings.autoApproved(s.copilot(), CopilotAgentTools.SERVER_GROUP);
        if (s.agentOnly()) {
            if (off) {
                rows.add(new Row("GitHub Copilot", Level.OK,
                        "Our tools are off for Agent mode and Copilot's other agents; only our agent uses them.", ""));
                rows.add(autoApproved ? new Row("GitHub Copilot", Level.OK, "Our tools run without asking each time.", "")
                        : new Row("GitHub Copilot", Level.WARN, "Copilot asks before each of our tools.",
                                "Restart Eclipse, or tick our server in Copilot's Tool Auto Approve list."));
            } else if (s.isolatedAtStart()) {
                // Approving the server in this session made Copilot switch our tools on for Agent mode; the next
                // start switches them off again. One calm row instead of an alarm.
                rows.add(new Row("GitHub Copilot", Level.WARN, "Restart Eclipse to finish the Copilot setup.",
                        "Copilot turned our tools on for its Agent mode when you approved the server; the restart"
                                + " turns them off again." + (autoApproved ? ""
                                        : " Until then Copilot asks before each of our tools.")
                                + " If this is still here after the restart, send us the report."));
            } else {
                rows.add(new Row("GitHub Copilot", Level.WARN, "Our tools are still on for Copilot's Agent mode.",
                        "Restart Eclipse (applied when Copilot starts). If it stays, untick our tools for mode"
                                + " 'Agent' on Copilot's Tools page and send us the report."));
                rows.add(autoApproved ? new Row("GitHub Copilot", Level.FAIL,
                                "Our tools are auto-approved while Agent mode can still use them.",
                                "Restart Eclipse; until then, remove our server from Copilot's Tool Auto Approve list.")
                        : new Row("GitHub Copilot", Level.WARN, "Copilot asks before each of our tools.",
                                "Restart Eclipse, or tick our server in Copilot's Tool Auto Approve list."));
            }
        } else {
            rows.add(new Row("GitHub Copilot", Level.INFO, "Our tools may be used in every Copilot mode"
                    + " (preference off).", ""));
            if (autoApproved) rows.add(new Row("GitHub Copilot", Level.WARN,
                    "Our tools are auto-approved in every mode, so Agent mode runs them without asking.",
                    "Turn the preference on, or remove our server from Copilot's Tool Auto Approve list."));
        }
    }

    private static void claudeRows(Snapshot s, List<Row> rows) {
        if (s.claudeStatus() != null) {
            boolean ok = s.claudeStatus().startsWith("Configured");
            rows.add(new Row("Claude Code", ok ? Level.OK : Level.WARN, s.claudeStatus(),
                    ok ? "" : "Agentic Debugger > Claude Code page: Set up or Update."));
        } else {
            rows.add(new Row("Claude Code", Level.OK, "Set up by this plugin (registration recorded).", ""));
        }
        String installed = s.claudeSkill();
        String bundled = s.bundledSkill();
        if (installed == null) {
            rows.add(new Row("Claude Code", Level.WARN, "eclipse-debugger skill missing in ~/.claude/skills.",
                    "Agentic Debugger > Claude Code page: Skill, Install."));
        } else if (bundled == null || installed.equals(bundled)) {
            rows.add(new Row("Claude Code", Level.OK, "eclipse-debugger skill "
                    + (installed.isEmpty() ? "" : installed + " ") + "installed.", ""));
        } else {
            // The plugin keeps a copy the user edited and writes its own next to it (SkillInstaller).
            rows.add(new Row("Claude Code", Level.WARN, "eclipse-debugger skill " + (installed.isEmpty() ? "(no version)"
                    : installed) + " installed; this plugin has " + bundled + ".",
                    "Your copy was kept because it was edited (this version is next to it as .new files), or the"
                    + " skill is older: Agentic Debugger > Claude Code page, Skill."));
        }
    }

    /** The skill version from a SKILL.md heading ("# Eclipse debugger — skill v0.1.7 (candidate)"), "" when none. */
    public static String skillVersion(String skillMd) {
        if (skillMd == null) return null;
        Matcher m = Pattern.compile("skill v(\\d+(?:\\.\\d+)*)").matcher(skillMd);
        return m.find() ? m.group(1) : "";
    }

    /** The groups of the setup check on the preferences page, in this order, and the areas each covers. */
    static final List<List<String>> GROUPS = List.of(List.of("Eclipse", "Eclipse", "MCP server"),
            List.of("Claude Code", "Claude Code"), List.of("GitHub Copilot", "GitHub Copilot"));

    /** The hint under the numbered problems. */
    public static final String HINT = "After you fix something, come back to this page or click Check again.";

    /**
     * OPEN-69 (Ayan 28 Nov: "issues at top, one issue, two issue; the checks users are not interested in below"):
     * the rows for the preferences page.
     * <pre>
     * ⚠ 2 things to fix
     *  1. ✖ GitHub Copilot: ...
     *       → what to do
     *  2. ⚠ Claude Code: ...
     *       → what to do
     *
     * After you fix something, come back to this page or click Check again.
     *
     * Everything else is fine (7 checks)
     *   Eclipse:  ✔ 3 OK
     *   GitHub Copilot:  ✔ 4 OK
     *   ℹ GitHub Copilot: a note
     * </pre>
     * With no problems the headline is "✔ All N checks OK" and the group lines follow. Failures are numbered before
     * warnings, each in the order of {@link #GROUPS}. "Copy report" still copies {@link #report}.
     */
    public static String summary(List<Row> rows) {
        if (rows.isEmpty()) return "Nothing to check yet: neither Claude Code nor GitHub Copilot is set up.";
        List<Row> ordered = new ArrayList<>();
        for (List<String> group : GROUPS)
            for (Row r : rows) if (group.subList(1, group.size()).contains(r.area())) ordered.add(r);
        for (Row r : rows) if (!ordered.contains(r)) ordered.add(r);
        List<Row> problems = new ArrayList<>();
        for (Level level : List.of(Level.FAIL, Level.WARN))
            for (Row r : ordered) if (r.level() == level) problems.add(r);
        long ok = rows.stream().filter(r -> r.level() == Level.OK).count();
        boolean notes = rows.stream().anyMatch(r -> r.level() == Level.INFO);
        StringBuilder out = new StringBuilder();
        if (problems.isEmpty()) {
            out.append("✔ All ").append(ok).append(ok == 1 ? " check OK" : " checks OK");
        } else {
            out.append("⚠ ").append(problems.size()).append(problems.size() == 1 ? " thing to fix" : " things to fix");
            int n = 0;
            for (Row r : problems) {
                out.append("\n ").append(++n).append(". ").append(r.level() == Level.FAIL ? "✖ " : "⚠ ")
                        .append(r.area()).append(": ").append(r.text());
                if (!r.fix().isEmpty()) out.append("\n      → ").append(r.fix());
            }
            out.append("\n\n").append(HINT);
            if (ok == 0 && !notes) return out.toString();
            out.append("\n\n").append(ok == 0 ? "Notes"
                    : "Everything else is fine (" + ok + (ok == 1 ? " check)" : " checks)"));
        }
        for (List<String> group : GROUPS) {
            long good = rows.stream().filter(r -> r.level() == Level.OK && group.subList(1, group.size()).contains(r.area()))
                    .count();
            if (good > 0) out.append("\n  ").append(group.get(0)).append(":  ✔ ").append(good).append(" OK");
        }
        for (Row r : ordered) {
            if (r.level() != Level.INFO) continue;
            out.append("\n  ℹ ").append(r.area()).append(": ").append(r.text());
            if (!r.fix().isEmpty()) out.append("\n      → ").append(r.fix());
        }
        return out.toString();
    }

    /** OPEN-69: how a part of {@link #summary} is drawn. */
    public enum Style { OK, WARN, FAIL, INFO, BOLD }

    /** A part of the summary text to draw in a style: {@code start} and {@code length} in characters. */
    public record Span(int start, int length, Style style) { }

    private static final Pattern NUMBERED = Pattern.compile("^ \\d+\\. ([⚠✖]) ");
    private static final Pattern COUNTS = Pattern.compile(
            "⚠ \\d+ things? to fix|✔ All \\d+ checks? OK|Everything else is fine \\(\\d+ checks?\\)|✔ \\d+ OK");

    /**
     * OPEN-69 (Ayan 28 Nov: "can the icons be colourful?"): the coloured parts of a {@link #summary}: the headline and
     * "✔ N OK" green or amber, a numbered warning amber, a numbered failure red, a note blue, a group name bold. What
     * to do ("→ ...") and the hint stay plain. Pure, so it is a unit test; the page only applies the spans.
     */
    public static List<Span> spans(String summary) {
        List<Span> out = new ArrayList<>();
        int at = 0;
        for (String line : summary.split("\n", -1)) {
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.find()) {
                out.add(new Span(at, line.length(), numbered.group(1).equals("✖") ? Style.FAIL : Style.WARN));
            } else if (line.startsWith("  ℹ ")) {
                out.add(new Span(at, line.length(), Style.INFO));
            } else if (!line.stripLeading().startsWith("→")) {
                int colon = line.indexOf(":  ");
                if (colon > 2 && line.startsWith("  ")) out.add(new Span(at + 2, colon - 1, Style.BOLD));
                Matcher m = COUNTS.matcher(line);
                while (m.find()) out.add(new Span(at + m.start(), m.end() - m.start(),
                        m.group().startsWith("⚠") ? Style.WARN : Style.OK));
            }
            at += line.length() + 1;
        }
        return out;
    }

    /** The worst level in the rows (OK when empty). */
    public static Level worst(List<Row> rows) {
        Level worst = Level.OK;
        for (Row row : rows) if (row.level().ordinal() > worst.ordinal() && row.level() != Level.INFO) worst = row.level();
        return worst;
    }

    /** The rows as plain text, for "Copy report" and the Error Log. */
    public static String report(List<Row> rows, String header) {
        StringBuilder out = new StringBuilder(header).append('\n');
        for (Row row : rows) {
            out.append(row.level()).append("  ").append(row.area()).append(": ").append(row.text());
            if (!row.fix().isEmpty()) out.append("  -> ").append(row.fix());
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Starts the MCP server once with {@code command}, asks for its tools ({@code initialize}, {@code tools/list}) and
     * stops it. Needs no Eclipse bridge (listing tools does not call Eclipse). Never throws.
     */
    public static Probe probeServer(List<String> command, Map<String, String> env, Duration timeout) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(env);
            process = builder.start();
            OutputStream in = process.getOutputStream();
            String requests = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"agentic-debugger-setup-check\","
                    + "\"version\":\"1\"}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n";
            in.write(requests.getBytes(StandardCharsets.UTF_8));
            in.flush();
            long deadline = System.nanoTime() + timeout.toNanos();
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Process running = process;
            Thread watchdog = new Thread(() -> {
                try {
                    if (!running.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) running.destroyForcibly();
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                }
            }, "setup-check-probe");
            watchdog.setDaemon(true);
            watchdog.start();
            String line;
            while ((line = out.readLine()) != null) {
                if (line.contains("\"id\":2") && line.contains("\"tools\"")) return new Probe(toolNames(line), null);
                if (System.nanoTime() > deadline) break;
            }
            return new Probe(List.of(), "no tool list within " + timeout.toSeconds() + " s");
        } catch (Exception | LinkageError failure) {
            return new Probe(List.of(), failure.toString());
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    /** The tool names of a {@code tools/list} reply. */
    static List<String> toolNames(String reply) {
        List<String> names = new ArrayList<>();
        int tools = reply.indexOf("\"tools\"");
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").matcher(reply);
        int depthStart = reply.indexOf('[', tools);
        if (depthStart < 0) return names;
        // Tool objects sit directly in the array; nested "name" keys (schemas) are skipped by tracking braces.
        int depth = 0;
        for (int i = depthStart + 1; i < reply.length(); i++) {
            char c = reply.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (end < reply.length() && reply.charAt(end) != '"') end += reply.charAt(end) == '\\' ? 2 : 1;
                if (depth == 1 && m.find(i) && m.start() == i) names.add(m.group(1));
                i = end;
            } else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (depth == 0) break;
                depth--;
            }
        }
        return names;
    }

    private static CopilotSettings.Approval approval(Snapshot s) {
        return s.copilot() == null ? new CopilotSettings.Approval(null, null)
                : CopilotSettings.approval(s.copilot(), s.pluginId());
    }

    private static boolean anyAgent(Map<String, Path> files) {
        for (Path file : files.values()) if (Files.isRegularFile(file)) return true;
        return false;
    }

    private static boolean samePath(String a, String b) {
        return a.replace('\\', '/').equalsIgnoreCase(b.replace('\\', '/'));
    }
}
