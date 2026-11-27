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
     * @param claudeStatus   Claude Code's answer ("Configured (user scope)", ...), null when not asked
     * @param probe          a real start of the MCP server, null in the quiet start-up check
     */
    public record Snapshot(int port, boolean bridgeListening, boolean jarStaged, String template,
                           boolean copilotInstalled, String copilotVersion, boolean agentOnly,
                           CopilotSettings.Store copilot, String pluginId, Path launcher,
                           Map<String, Path> agentFiles, boolean claudeConfigured, boolean claudeSkill,
                           String claudeStatus, Probe probe) { }

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
                        "It is staged when Copilot or Claude Code is set up; use Configure Claude Code or restart."));
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
                        "Add the agent again (project menu or preferences page) to update it."));
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
                    "Preferences page: Install for all workspaces, or project menu > Agentic Debugger > Add."));
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
            rows.add(off ? new Row("GitHub Copilot", Level.OK,
                            "Our tools are off for Agent mode and Copilot's other agents; only our agent uses them.", "")
                    : new Row("GitHub Copilot", Level.WARN, "Our tools are still on for Copilot's Agent mode.",
                            "Restart Eclipse (applied when Copilot starts). If it stays, untick our tools for mode"
                                    + " 'Agent' on Copilot's Tools page and send us the report."));
            if (autoApproved && !off) {
                rows.add(new Row("GitHub Copilot", Level.FAIL,
                        "Our tools are auto-approved while Agent mode can still use them.",
                        "Restart Eclipse; until then, remove our server from Copilot's Tool Auto Approve list."));
            } else {
                rows.add(autoApproved ? new Row("GitHub Copilot", Level.OK, "Our tools run without confirmations.", "")
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
            rows.add(new Row("Claude Code", ok ? Level.OK : Level.WARN, "Claude Code: " + s.claudeStatus(),
                    ok ? "" : "Click Configure Claude Code."));
        } else {
            rows.add(new Row("Claude Code", Level.OK, "Set up by this plugin (registration recorded).", ""));
        }
        rows.add(s.claudeSkill() ? new Row("Claude Code", Level.OK, "eclipse-debugger skill installed.", "")
                : new Row("Claude Code", Level.WARN, "eclipse-debugger skill missing in ~/.claude/skills.",
                        "Click Configure Claude Code, or restart Eclipse."));
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
