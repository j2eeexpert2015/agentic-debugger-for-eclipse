package org.learningfromexperience.eclipse.agentdebug.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.learningfromexperience.eclipse.agentdebug.Activator;
import org.learningfromexperience.eclipse.agentdebug.integration.ClaudeIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotAgentInstaller;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotIntegration;
import org.learningfromexperience.eclipse.agentdebug.integration.CopilotRegistration;
import org.learningfromexperience.eclipse.agentdebug.integration.SetupCheck;

/**
 * OPEN-61: gathers what {@link SetupCheck} looks at from this Eclipse, runs it, and keeps the last result for the status
 * bar ({@link AgentStatusControl}). The quiet start-up check ({@link #scheduleQuietCheck}) only reads files and
 * settings; the preferences page (opened, or "Check again") ({@link #run(boolean)} with {@code deep}) also starts the MCP server
 * once and asks Claude Code.
 *
 * @author Ayan Dutta
 */
public final class SetupCheckRunner {

    private static volatile List<SetupCheck.Row> lastRows = List.of();
    /** OPEN-69: the last full check (from the preferences page) and when it ran, shown at once when the page opens. */
    private static volatile List<SetupCheck.Row> lastDeepRows;
    private static volatile java.time.LocalTime lastDeepAt;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private SetupCheckRunner() { }

    /** OPEN-69: how many problems (WARN, FAIL) the last check found; 0 when none or not checked yet. */
    public static int problemCount() {
        return (int) lastRows.stream().filter(r -> r.level() == SetupCheck.Level.WARN || r.level() == SetupCheck.Level.FAIL)
                .count();
    }

    /** OPEN-69: the last check's problems, failures first, one line each ("GitHub Copilot: ..."), for a tooltip. */
    public static List<String> problemLines() {
        List<String> out = new ArrayList<>();
        for (SetupCheck.Level level : List.of(SetupCheck.Level.FAIL, SetupCheck.Level.WARN))
            for (SetupCheck.Row row : lastRows) if (row.level() == level) out.add(row.area() + ": " + row.text());
        return out;
    }

    /** The last check's problems in one line for the status bar, or null when none (or not checked yet). */
    public static String problemLine() {
        List<SetupCheck.Row> rows = lastRows;
        SetupCheck.Level worst = SetupCheck.worst(rows);
        if (worst == SetupCheck.Level.OK) return null;
        for (SetupCheck.Row row : rows) if (row.level() == worst) return row.area() + ": " + row.text();
        return null;
    }

    /** OPEN-69: the last full check's rows, or null when none ran in this session. */
    public static List<SetupCheck.Row> lastDeepRows() { return lastDeepRows; }

    /** OPEN-69: when the last full check ran ("09:49"), or null. */
    public static String lastDeepAt() {
        java.time.LocalTime at = lastDeepAt;
        return at == null ? null : at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    static void addListener(Runnable listener) { LISTENERS.add(listener); }

    static void removeListener(Runnable listener) { LISTENERS.remove(listener); }

    /** Runs the check; {@code deep} also starts the MCP server and asks Claude Code (a few seconds). Never throws. */
    public static List<SetupCheck.Row> run(boolean deep) {
        List<SetupCheck.Row> rows;
        try {
            rows = SetupCheck.rows(snapshot(deep));
        } catch (Exception | LinkageError failure) {
            rows = List.of(new SetupCheck.Row("Eclipse", SetupCheck.Level.FAIL,
                    "The setup check itself failed: " + failure, "Copy this report into an issue."));
        }
        lastRows = rows;
        if (deep) {
            lastDeepRows = rows;
            lastDeepAt = java.time.LocalTime.now();
        }
        for (Runnable listener : LISTENERS) {
            try { listener.run(); } catch (RuntimeException ignored) { /* a closed status bar */ }
        }
        if (deep && SetupCheck.worst(rows) != SetupCheck.Level.OK) recheck(EVERY_MINUTE);
        return rows;
    }

    /** The report text with a header naming the plugin, Eclipse and Java. */
    public static String report(List<SetupCheck.Row> rows) {
        String plugin = Platform.getBundle(CopilotIntegration.PLUGIN_ID) == null ? "?"
                : Platform.getBundle(CopilotIntegration.PLUGIN_ID).getVersion().toString();
        return SetupCheck.report(rows, "Agentic Debugger " + plugin + " setup check, Java "
                + System.getProperty("java.version") + ", " + System.getProperty("os.name"));
    }

    /** OPEN-69: the quiet re-check, one job reused so that several triggers in a row run it once. */
    private static final Job RECHECK = recheckJob();
    private static final long EVERY_MINUTE = 60_000;
    private static volatile boolean watching;

    private static Job recheckJob() {
        Job job = Job.create("Agentic Debugger: setup check", monitor -> {
            List<SetupCheck.Row> rows = run(false);
            // While something is wrong, look again every minute: it only reads files and settings.
            if (SetupCheck.worst(rows) != SetupCheck.Level.OK) recheck(EVERY_MINUTE);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        return job;
    }

    /** Runs the quiet check again in {@code delay} ms (a later call replaces an earlier one that has not run yet). */
    static void recheck(long delay) {
        RECHECK.cancel();
        RECHECK.schedule(delay);
    }

    /**
     * OPEN-69 (Ayan 28 Nov 10:27: "the warning stays although the issue is resolved"): the status bar follows a fix
     * made anywhere: a change to Copilot's settings (approval, tools, auto-approve) or to ours runs the quiet check
     * again two seconds later; while a problem is shown it also runs every minute (Claude Code's registration and the
     * agent files are not settings). Installed once.
     */
    private static void watchSettings() {
        if (watching) return;
        watching = true;
        for (String node : List.of(CopilotIntegration.COPILOT_UI, CopilotIntegration.PLUGIN_ID)) {
            try {
                InstanceScope.INSTANCE.getNode(node).addPreferenceChangeListener(event -> recheck(2_000));
            } catch (RuntimeException | LinkageError notAvailable) {
                // the minute re-check still runs
            }
        }
    }

    /**
     * About 30 seconds after start-up, a quiet check of the integrations in use: nothing is started, nothing shown
     * unless something needs attention (then a warning in the Error Log and a note in the status bar).
     */
    public static void scheduleQuietCheck() {
        Job job = Job.create("Agentic Debugger: setup check", monitor -> {
            List<SetupCheck.Row> rows = run(false);
            SetupCheck.Level worst = SetupCheck.worst(rows);
            if (worst != SetupCheck.Level.OK) {
                ILog.of(SetupCheckRunner.class).log(new Status(worst == SetupCheck.Level.FAIL ? IStatus.ERROR
                        : IStatus.WARNING, CopilotIntegration.PLUGIN_ID, "Agentic Debugger setup needs attention"
                        + " (Window > Preferences > Agentic Debugger, setup check):\n" + report(rows)));
            }
            System.out.println("[Eclipse Agentic Debugger] Setup check: " + worst);
            if (worst != SetupCheck.Level.OK) recheck(EVERY_MINUTE);
            watchSettings();
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        job.schedule(30_000);
    }

    private static SetupCheck.Snapshot snapshot(boolean deep) throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        String portText = System.getenv("AGENTDEBUG_PORT");
        int port = portText == null || portText.isBlank() ? 8765 : Integer.parseInt(portText.trim());
        Path folder = CopilotRegistration.runtimeFolder(home, portText);
        Path jar = folder.resolve("agentic-debugger-mcp.jar");
        Path launcher = CopilotRegistration.windows() ? folder.resolve("start-mcp-server.cmd") : null;
        String template = CopilotIntegration.template();
        boolean copilot = CopilotIntegration.copilotInstalled();
        Map<String, Path> agents = new LinkedHashMap<>();
        agents.put("all workspaces", CopilotAgentInstaller.userAgentFile(home));
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen() && project.getLocation() != null) {
                agents.put(project.getName(), project.getLocation().toFile().toPath().resolve(CopilotAgentInstaller.AGENT_FILE));
            }
        }
        Path claudeDir = home.resolve(".eclipse-agentdebug");
        boolean claudeConfigured = Files.isRegularFile(claudeDir.resolve("claude-registration.properties"));
        Path skillFile = home.resolve(".claude").resolve("skills").resolve("eclipse-debugger").resolve("SKILL.md");
        String claudeSkill = Files.isRegularFile(skillFile) ? SetupCheck.skillVersion(Files.readString(skillFile)) : null;
        String claudeStatus = null;
        SetupCheck.Probe probe = null;
        if (deep) {
            try {
                claudeStatus = new ClaudeIntegration().status();
            } catch (Exception notFound) {
                claudeStatus = claudeConfigured ? "could not ask Claude Code (" + notFound.getMessage() + ")" : null;
            }
            List<String> command = new ArrayList<>();
            if (Files.isRegularFile(jar)) {
                command.add(ClaudeIntegration.javaExecutable().toString());
                command.add("-jar");
                command.add(jar.toString());
                probe = SetupCheck.probeServer(command, Map.of("AGENTDEBUG_PORT", String.valueOf(port)),
                        Duration.ofSeconds(20));
            } else {
                probe = new SetupCheck.Probe(List.of(), "the server jar is not staged (" + jar + ")");
            }
        }
        return new SetupCheck.Snapshot(port, Activator.bridgeRunning(), Files.isRegularFile(jar), template, copilot,
                CopilotIntegration.copilotVersion(), CopilotIntegration.agentOnly(),
                copilot ? CopilotIntegration.copilotStore() : null, CopilotIntegration.PLUGIN_ID, copilot ? launcher : null,
                agents, claudeConfigured, claudeSkill, ClaudeIntegration.bundledSkillVersion(), claudeStatus, probe,
                CopilotIntegration.isolatedThisSession());
    }
}
