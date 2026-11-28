package org.learningfromexperience.eclipse.agentdebug.uitests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.matchers.WidgetMatcherFactory;
import org.eclipse.swtbot.swt.finder.results.Result;
import org.eclipse.swtbot.swt.finder.results.VoidResult;
import org.eclipse.swtbot.swt.finder.utils.SWTBotPreferences;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * OPEN-72 (Ayan 28 Nov 15:57: "can the click-through be automated?"): the first SWTBot UI tests. A real Eclipse with
 * the plugin starts in a fresh workspace (Tycho's UI test runtime, profile {@code ui-tests}); the tests do what a user
 * does by hand in release checks F12-F14 and read what is shown. GitHub Copilot is not in this Eclipse, so its page
 * shows the not-installed case. Whether Claude Code is found depends on the machine, so the welcome test accepts both.
 * <p>Labels are found by their exact text, as the user reads it: a changed text fails here on purpose.
 * <p>JUnit 5, like the rest of the project (28 Nov: first JUnit 4 to prove the setup, then this); the tests run in
 * name order (ui01..ui05). The manifest allows only JUnit 5 ([5,6)): with JUnit 6 also allowed, Tycho found no tests.
 *
 * @author Ayan Dutta
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PreferencesUiTest {

    private static final String PLUGIN_TITLE = "Agentic Debugger for Eclipse IDE";
    private static final String MAIN_PAGE = "org.learningfromexperience.eclipse.agentdebug.preferences";
    private static SWTWorkbenchBot bot;

    @BeforeAll
    public static void start() {
        SWTBotPreferences.TIMEOUT = 60_000;
        SWTBotPreferences.PLAYBACK_DELAY = 20;
        bot = new SWTWorkbenchBot();
    }

    /** UI-01 welcome at the first start: title, a line per agent or the no-agent text, the path, Later closes it. */
    @Test
    public void ui01Welcome() {
        SWTBotShell shell = bot.shell(PLUGIN_TITLE);
        shell.activate();
        SWTBot dialog = shell.bot();
        dialog.label("Agentic Debugger is installed.");
        boolean agentFound = has(dialog, "Connect it to your AI agent:");
        if (agentFound) {
            assertTrue(has(dialog, "Claude Code found") || has(dialog, "Claude Code not installed"), "a Claude Code line");
            // GitHub Copilot is not part of this test Eclipse
            dialog.label("GitHub Copilot not installed");
            dialog.label("You can also do this later in");
            dialog.label("Window → Preferences → Agentic Debugger.");
            dialog.button("Set up now");
            dialog.button("Later").click();
        } else {
            dialog.label("It works with Claude Code or GitHub Copilot for Eclipse.\nInstall one, then restart Eclipse.");
            dialog.button("OK").click();
        }
        bot.waitUntil(closed(shell));
        bot.sleep(2_000);
        assertEquals(0L, java.util.Arrays.stream(bot.shells()).filter(s -> s.isOpen() && PLUGIN_TITLE.equals(s.getText()))
                .count(), "no second popup after closing the welcome");
    }

    /** UI-02 main page: for every agent, the setup check runs by itself and shows its time, Check again. */
    @Test
    public void ui02MainPage() {
        SWTBotShell prefs = openPreferences();
        try {
            SWTBot page = prefs.bot();
            page.label("For every agent.");
            page.button("Check again");
            page.button("Copy report");
            bot.waitUntil(labelMatching(page, Pattern.compile("Checked at \\d{2}:\\d{2}")), 120_000);
        } finally {
            prefs.bot().button("Cancel").click();
        }
    }

    /** UI-03 Claude Code page: for Claude Code only, a Setup section with a state. */
    @Test
    public void ui03ClaudeCodePage() {
        SWTBotShell prefs = openPreferences();
        try {
            SWTBot page = prefs.bot();
            page.tree().getTreeItem("Agentic Debugger").expand().getNode("Claude Code").select();
            page.label("For Claude Code only.");
            bot.waitUntil(labelMatching(page, Pattern.compile(
                    "✔ Set up|✖ Not set up|⚠ Needs an update|✖ Claude Code not found|⚠ Couldn't check"
                            + "|⚠ Another 'eclipse-debugger' entry exists")), 60_000);
        } finally {
            prefs.bot().button("Cancel").click();
        }
    }

    /** UI-05 incident 28 Nov: a Claude Code entry whose Java is gone shows "Needs an update"; Update makes it work. */
    @Test
    public void ui05ClaudeCodeUpdate() throws Exception {
        String claude = findClaude();
        Assumptions.assumeTrue(claude != null, "Claude Code is not installed on this machine");
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        assertTrue(configDir != null && !configDir.isBlank(), "CLAUDE_CONFIG_DIR is not set (pom.xml); nothing was changed");
        java.nio.file.Files.createDirectories(Path.of(configDir));
        // Safety: this test may only ever see its own, empty Claude Code settings (CLAUDE_CONFIG_DIR in the pom)
        Run before = claude(claude, "mcp", "get", "eclipse-debugger");
        assertTrue(before.exit() != 0, "Claude Code's settings are not the test's own (an eclipse-debugger entry exists"
                + " already); nothing was changed. Output: " + before.output());
        try {
            Path jar = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "agentic-debugger-mcp.jar")
                    .toAbsolutePath();
            Path goneJava = Path.of(System.getProperty("user.home"), "eclipse", "plugins",
                    "org.eclipse.justj.openjdk.hotspot.jre.full.gone", "jre", "bin", windows() ? "java.exe" : "java");
            Run old = claude(claude, "mcp", "add", "--transport", "stdio", "--scope", "user", "eclipse-debugger", "--",
                    goneJava.toString(), "-jar", jar.toString());
            assertEquals(0, old.exit(), "could not write the old-style entry: " + old.output());
            // Safety: the entry must have gone into the test's own folder, not the user's settings
            try (var files = java.nio.file.Files.walk(Path.of(configDir))) {
                boolean ours = files.filter(java.nio.file.Files::isRegularFile).anyMatch(f -> {
                    try { return java.nio.file.Files.readString(f).contains("jre.full.gone"); }
                    catch (Exception unreadable) { return false; }
                });
                assertTrue(ours, "Claude Code did not write to " + configDir + " (CLAUDE_CONFIG_DIR not honoured); the"
                        + " test entry is removed again and nothing else was changed");
            }

            UIThreadRunnable.asyncExec((VoidResult) () ->
                    PreferencesUtil.createPreferenceDialogOn(null, CLAUDE_PAGE, null, null).open());
            SWTBotShell prefs = bot.shell("Preferences");
            prefs.activate();
            try {
                SWTBot page = prefs.bot();
                bot.waitUntil(labelMatching(page, Pattern.compile("^⚠ Needs an update$")), 90_000);
                page.button("Update").click();
                bot.waitUntil(labelMatching(page, Pattern.compile("^✔ Set up$")), 120_000);
            } finally {
                prefs.bot().button("Cancel").click();
            }

            Run after = claude(claude, "mcp", "get", "eclipse-debugger");
            assertEquals(0, after.exit(), after.output());
            if (windows()) {
                assertTrue(after.output().contains("start-mcp-server.cmd"), "the launcher is registered: " + after.output());
            }
            assertTrue(after.output().contains("Connected") && !after.output().contains("Failed"),
                    "Claude Code can start the server now: " + after.output());
        } finally {
            claude(claude, "mcp", "remove", "eclipse-debugger", "--scope", "user");
        }
    }

    /** UI-04 GitHub Copilot page without Copilot: the two lines only, the menu path in bold text. */
    @Test
    public void ui04CopilotPageWithoutCopilot() {
        SWTBotShell prefs = openPreferences();
        try {
            SWTBot page = prefs.bot();
            page.tree().getTreeItem("Agentic Debugger").expand().getNode("GitHub Copilot").select();
            page.label("GitHub Copilot isn't installed in this Eclipse.");
            assertEquals("Install it from Help → Eclipse Marketplace, then restart Eclipse.", page.styledText().getText());
            assertFalse(has(page, "For GitHub Copilot only."), "no audience line without Copilot");
        } finally {
            prefs.bot().button("Cancel").click();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static final String CLAUDE_PAGE = "org.learningfromexperience.eclipse.agentdebug.preferences.claude";

    private record Run(int exit, String output) { }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** The {@code claude} command on the PATH, or null. */
    private static String findClaude() throws Exception {
        Run found = run(windows() ? List.of("where", "claude") : List.of("sh", "-c", "command -v claude"));
        return found.exit() == 0 ? "claude" : null;
    }

    /** Runs Claude Code with this test Eclipse's environment (so with its own CLAUDE_CONFIG_DIR). */
    private static Run claude(String claude, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(windows() ? List.of("cmd", "/c", claude) : List.of(claude));
        command.addAll(List.of(args));
        return run(command);
    }

    private static Run run(List<String> command) throws Exception {
        Path out = java.nio.file.Files.createTempFile("ui-test-", ".txt");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            process.getOutputStream().close();
            boolean done = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            String output = java.nio.file.Files.readString(out, java.nio.charset.StandardCharsets.UTF_8);
            return new Run(done ? process.exitValue() : -1, done ? output : output + " (timed out)");
        } finally {
            java.nio.file.Files.deleteIfExists(out);
        }
    }

    private static SWTBotShell openPreferences() {
        UIThreadRunnable.asyncExec((VoidResult) () -> PreferencesUtil.createPreferenceDialogOn(null, MAIN_PAGE, null, null).open());
        SWTBotShell shell = bot.shell("Preferences");
        shell.activate();
        return shell;
    }

    private static boolean has(SWTBot bot, String label) {
        return !bot.getFinder().findControls(WidgetMatcherFactory.withText(label)).isEmpty();
    }

    private static DefaultCondition closed(SWTBotShell shell) {
        return new DefaultCondition() {
            @Override public boolean test() { return !shell.isOpen(); }
            @Override public String getFailureMessage() { return "the dialog did not close"; }
        };
    }

    private static DefaultCondition labelMatching(SWTBot in, Pattern pattern) {
        return new DefaultCondition() {
            @Override
            public boolean test() {
                List<? extends Widget> labels = in.getFinder().findControls(WidgetMatcherFactory.widgetOfType(Label.class));
                for (Widget widget : labels) {
                    String text = UIThreadRunnable.syncExec(
                            (Result<String>) () -> widget.isDisposed() ? "" : ((Label) widget).getText());
                    if (pattern.matcher(text).find()) return true;
                }
                return false;
            }

            @Override public String getFailureMessage() { return "no label matching " + pattern; }
        };
    }
}
