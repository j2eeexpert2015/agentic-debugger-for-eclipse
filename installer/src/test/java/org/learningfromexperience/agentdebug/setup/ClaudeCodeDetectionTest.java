package org.learningfromexperience.agentdebug.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for finding the Claude Code CLI ({@link ClaudeCodeConfigurator#locateClaude}).
 * Uses an empty placeholder {@code claude.exe} in a JUnit temporary folder; Claude Code is not needed and
 * never started. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ClaudeCodeDetectionTest {

    @TempDir
    Path home;

    private Path installDir;
    private Path cli;

    @BeforeEach
    void createPlaceholderCli() throws Exception {
        installDir = Files.createDirectories(home.resolve(".local").resolve("bin"));
        cli = Files.createFile(installDir.resolve("claude.exe"));
    }

    @Test
    @DisplayName("CD-U1 the native installer location ~/.local/bin is found when PATH has no claude")
    void cdU1() {
        assertEquals(cli, ClaudeCodeConfigurator.locateClaude(Map.of(), "Windows 11", home));
    }

    @Test
    @DisplayName("CD-U2 the Windows 'Path' variable is read case-insensitively")
    void cdU2() {
        assertEquals(cli, ClaudeCodeConfigurator.locateClaude(Map.of("Path", installDir.toString()), "Windows 11", home));
    }

    @Test
    @DisplayName("CD-U3 a missing CLI gives a clear error instead of a wrong path")
    void cdU3() throws Exception {
        Files.delete(cli);
        assertThrows(IllegalStateException.class, () -> ClaudeCodeConfigurator.locateClaude(Map.of(), "Windows 11", home));
    }
}
