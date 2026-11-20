package org.learningfromexperience.agentdebug.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Lightweight detection checks runnable without installing Claude Code. */
public final class ClaudeCodeDetectionTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("agentdebug-detection-");
        try {
            Path installDir = Files.createDirectories(home.resolve(".local").resolve("bin"));
            Path cli = Files.createFile(installDir.resolve("claude.exe"));

            Path byFallback = ClaudeCodeConfigurator.locateClaude(Map.of(), "Windows 11", home);
            if (!cli.equals(byFallback)) throw new AssertionError("Failed to detect native Claude install");

            Path byPath = ClaudeCodeConfigurator.locateClaude(Map.of("Path", installDir.toString()), "Windows 11", home);
            if (!cli.equals(byPath)) throw new AssertionError("Failed case-insensitive PATH detection");

            System.out.println("ClaudeCodeDetectionTest passed");
        } finally {
            Files.deleteIfExists(home.resolve(".local/bin/claude.exe"));
            Files.deleteIfExists(home.resolve(".local/bin"));
            Files.deleteIfExists(home.resolve(".local"));
            Files.deleteIfExists(home);
        }
    }
}
