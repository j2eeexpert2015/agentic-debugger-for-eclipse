package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates.Registration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for recognizing and migrating the Claude Code registration of the MCP server
 * ({@link ClaudeRegistration}). Parses fixed {@code claude mcp get} output; no Claude CLI and no Eclipse needed.
 * CR-U7 (OPEN-09) reads the source of {@link ClaudeIntegration}: the user's other Claude MCP servers can never be
 * changed, because every Claude command the plugin runs names its own entry and nothing writes Claude's files.
 * Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ClaudeRegistrationTest {

    private static final Path HOME = Path.of("C:\\Users\\vmadmin");
    private static final String LEGACY = "eclipse-debugger:\n"
            + "  Scope: User config (available in all your projects)\n"
            + "  Status: \u2713 Connected\n"
            + "  Type: stdio\n"
            + "  Command: C:\\Java\\bin\\java.exe\n"
            + "  Args: -jar C:\\Users\\vmadmin\\AppData\\Local\\agentic-debugger-for-eclipse\\mcp-server.jar\n";

    @Test
    @DisplayName("CR-U1 the previous installer's registration is recognized and is not the current jar")
    void crU1() {
        ClaudeRegistration registration = ClaudeRegistration.parse(LEGACY);
        assertTrue(registration.legacyJar(HOME, true), "previous installer recognized");
        assertFalse(registration.exactJar(HOME.resolve(".eclipse-agentdebug/agentic-debugger-mcp.jar")), "not the same jar");
    }

    @Test
    @DisplayName("CR-U2 another product's registration is left unchanged")
    void crU2() {
        assertFalse(ClaudeRegistration.parse(LEGACY.replace("agentic-debugger-for-eclipse", "foreign-product"))
                .legacyJar(HOME, true));
    }

    @Test
    @DisplayName("CR-U3 a project or local scope registration is left unchanged")
    void crU3() {
        assertFalse(ClaudeRegistration.parse(LEGACY.replace("Scope: User", "Scope: Local")).legacyJar(HOME, true));
    }

    @Test
    @DisplayName("CR-U4 an http registration is left unchanged")
    void crU4() {
        assertFalse(ClaudeRegistration.parse(LEGACY.replace("Type: stdio", "Type: http")).legacyJar(HOME, true));
    }

    @Test
    @DisplayName("CR-U5 the current managed jar path is recognized")
    void crU5() {
        String current = "C:\\Users\\vmadmin\\.eclipse-agentdebug\\agentic-debugger-mcp.jar";
        String newer = LEGACY.replace(
                "C:\\Users\\vmadmin\\AppData\\Local\\agentic-debugger-for-eclipse\\mcp-server.jar", current);
        assertTrue(ClaudeRegistration.samePath(ClaudeRegistration.parse(newer).registeredJar(), current));
    }

    @Test
    @DisplayName("CR-U6 a registration with extra arguments is never migrated")
    void crU6() {
        assertFalse(ClaudeRegistration.parse(LEGACY.replace("mcp-server.jar", "mcp-server.jar -Xfoo"))
                .legacyJar(HOME, true));
    }

    /**
     * OPEN-09: the plugin changes Claude's MCP configuration only through {@code claude mcp get|add|remove
     * eclipse-debugger}, so Claude itself edits only that one entry and the user's other servers (GitHub, a database,
     * Slack, ...) stay as they are. Fails when a new Claude command does not name {@code SERVER}, uses another
     * subcommand (for example {@code add-json} or a bulk removal), or when the code writes Claude's configuration
     * files ({@code .claude.json}, {@code .mcp.json}) itself.
     */
    @Test
    @DisplayName("CR-U7 every Claude command the plugin runs touches only its own MCP entry")
    void crU7() throws Exception {
        Path source = Path.of("src/org/learningfromexperience/eclipse/agentdebug/integration/ClaudeIntegration.java");
        assertTrue(Files.isRegularFile(source), "source found at " + source.toAbsolutePath());
        String code = Files.readString(source, StandardCharsets.UTF_8);
        Matcher call = Pattern.compile("invoke\\(cli,\\s*\"mcp\",\\s*\"([a-z-]+)\"([^;]*);", Pattern.DOTALL).matcher(code);
        List<String> wrong = new ArrayList<>();
        int calls = 0;
        while (call.find()) {
            calls++;
            boolean known = List.of("get", "add", "remove").contains(call.group(1));
            if (!known || !call.group(2).matches("(?s).*\\bSERVER\\b.*")) wrong.add("mcp " + call.group(1) + call.group(2));
        }
        assertTrue(calls >= 5, "found the Claude commands (" + calls + ")");
        assertTrue(wrong.isEmpty(), "Claude commands that may touch other MCP entries: " + wrong);
        assertTrue(!Pattern.compile("invoke\\(cli,\\s*\"(?!mcp\")").matcher(code).find(), "only 'claude mcp' commands are run");
        String withoutComments = code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertFalse(withoutComments.contains(".claude.json") || withoutComments.contains(".mcp.json"),
                "the plugin never reads or writes Claude's configuration files itself");
        assertTrue(code.contains("public static final String SERVER = \"eclipse-debugger\";"), "own entry name");
    }

    private static final Path JAR = HOME.resolve(".eclipse-agentdebug").resolve("agentic-debugger-mcp.jar");
    private static final Path LAUNCHER = HOME.resolve(".eclipse-agentdebug").resolve("start-mcp-server.cmd");
    private static final String DIRECT = LEGACY
            .replace("C:\\Java\\bin\\java.exe",
                    "C:\\eclipse\\plugins\\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_25.0.5.v20261030-0959\\jre\\bin\\java.exe")
            .replace("C:\\Users\\vmadmin\\AppData\\Local\\agentic-debugger-for-eclipse\\mcp-server.jar", JAR.toString());
    private static final String VIA_LAUNCHER = LEGACY.replace("Command: C:\\Java\\bin\\java.exe", "Command: cmd")
            .replace("Args: -jar C:\\Users\\vmadmin\\AppData\\Local\\agentic-debugger-for-eclipse\\mcp-server.jar",
                    "Args: /c " + LAUNCHER);

    @Test
    @DisplayName("CR-U8 the forms of our entry: launcher (cmd /c), direct Java, the previous installer, someone else's")
    void crU8() {
        assertEquals(ClaudeRegistration.Form.LAUNCHER, ClaudeRegistration.parse(VIA_LAUNCHER).form(JAR, LAUNCHER, HOME, true));
        assertEquals(ClaudeRegistration.Form.LAUNCHER, ClaudeRegistration.parse(VIA_LAUNCHER.replace("Command: cmd", "Command: cmd.exe")
                .replace("/c " + LAUNCHER, "/C \"" + LAUNCHER + "\"")).form(JAR, LAUNCHER, HOME, true), "cmd.exe, /C, quotes");
        assertEquals(ClaudeRegistration.Form.DIRECT, ClaudeRegistration.parse(DIRECT).form(JAR, LAUNCHER, HOME, true));
        assertEquals(ClaudeRegistration.Form.LEGACY, ClaudeRegistration.parse(LEGACY).form(JAR, LAUNCHER, HOME, true));
        assertEquals(ClaudeRegistration.Form.FOREIGN, ClaudeRegistration.parse(VIA_LAUNCHER.replace("start-mcp-server.cmd", "other.cmd"))
                .form(JAR, LAUNCHER, HOME, true));
        assertEquals(ClaudeRegistration.Form.FOREIGN, ClaudeRegistration.parse(VIA_LAUNCHER.replace("Scope: User", "Scope: Local"))
                .form(JAR, LAUNCHER, HOME, true), "only the user-scope entry is ours");
        assertFalse(ClaudeRegistration.parse(LEGACY).failedToConnect());
        assertTrue(ClaudeRegistration.parse(DIRECT.replace("\u2713 Connected", "\u2717 Failed to connect")).failedToConnect());
    }

    @Test
    @DisplayName("CR-U9 incident 28 Nov: a direct-Java entry is 'Needs an update' on Windows, and wherever its Java is gone or Claude can't connect")
    void crU9() {
        Registration current = Registration.CURRENT, older = Registration.OLDER;
        assertEquals(current, ClaudeRegistration.state(ClaudeRegistration.Form.LAUNCHER, true, true, false));
        assertEquals(older, ClaudeRegistration.state(ClaudeRegistration.Form.LAUNCHER, true, true, true), "launcher, Claude can't connect");
        assertEquals(older, ClaudeRegistration.state(ClaudeRegistration.Form.DIRECT, true, true, false), "Windows: moved to the launcher");
        assertEquals(older, ClaudeRegistration.state(ClaudeRegistration.Form.DIRECT, true, false, true), "the incident: Java folder gone");
        assertEquals(current, ClaudeRegistration.state(ClaudeRegistration.Form.DIRECT, false, true, false), "Linux/macOS keep Java directly");
        assertEquals(older, ClaudeRegistration.state(ClaudeRegistration.Form.DIRECT, false, false, false), "Linux/macOS: Java gone");
        assertEquals(older, ClaudeRegistration.state(ClaudeRegistration.Form.DIRECT, false, true, true), "Linux/macOS: can't connect");
        assertEquals(older, ClaudeRegistration.state(ClaudeRegistration.Form.LEGACY, true, true, false));
        assertEquals(Registration.FOREIGN, ClaudeRegistration.state(ClaudeRegistration.Form.FOREIGN, true, true, true));
    }
}
