package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for recognizing and migrating the Claude Code registration of the MCP server
 * ({@link ClaudeRegistration}). Parses fixed {@code claude mcp get} output; no Claude CLI and no Eclipse needed.
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
}
