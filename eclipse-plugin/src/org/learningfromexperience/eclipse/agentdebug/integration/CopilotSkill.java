package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * OPEN-23: the {@code eclipse-debugger} skill for GitHub Copilot users, installed and removed only on the user's request
 * (the "Add Agentic Debugger Agent" action and the preferences page), never automatically: a skill steers how Copilot
 * debugs, so it is the user's choice.
 * <p>Copilot reads user skills from {@code ~/.copilot/skills/}, {@code ~/.claude/skills/} and
 * {@code ~/.agents/skills/}. When the skill is already in {@code ~/.claude/skills/eclipse-debugger} (installed for
 * Claude Code), Copilot reads it there and nothing is installed, so Copilot never sees the skill twice. Otherwise it is
 * written to {@code ~/.copilot/skills/eclipse-debugger} with {@link SkillInstaller}, which keeps the user's edits.
 * Removing deletes only the files the plugin wrote and the user did not change. Pure file code, unit tests CS-U1..U4.
 *
 * @author Ayan Dutta
 */
public final class CopilotSkill {

    private CopilotSkill() { }

    /** {@code ~/.copilot/skills/eclipse-debugger}. */
    static Path copilotFolder(Path home) { return home.resolve(".copilot").resolve("skills").resolve("eclipse-debugger"); }

    /** {@code ~/.claude/skills/eclipse-debugger}; Copilot reads it too. */
    static Path claudeFolder(Path home) { return home.resolve(".claude").resolve("skills").resolve("eclipse-debugger"); }

    /** What the plugin last wrote into the Copilot skill folder (SHA-256 per file). */
    static Path state(Path home) { return home.resolve(".eclipse-agentdebug").resolve("copilot-skill-installed.properties"); }

    /** Installs or updates the skill for Copilot from the plugin's bundled copy; one line for the user. */
    public static String install() throws IOException {
        return install(ClaudeIntegration.bundledSkillFiles(), home());
    }

    /** Removes the skill the plugin installed for Copilot; one line for the user. */
    public static String remove() throws IOException { return remove(home()); }

    /** Where the skill is for Copilot, in one line. */
    public static String status() { return status(home()); }

    static String install(Map<String, byte[]> bundled, Path home) throws IOException {
        if (Files.isRegularFile(claudeFolder(home).resolve("SKILL.md"))) {
            return "Skill: Copilot reads it from the Claude Code folder (~/.claude/skills).";
        }
        if (bundled.isEmpty()) return "Skill: not found in the plugin; nothing installed.";
        List<String> done = SkillInstaller.install(bundled, copilotFolder(home), state(home));
        if (done.isEmpty()) return "Skill: already up to date.";
        return "Skill installed for GitHub Copilot (~/.copilot/skills).";
    }

    static String remove(Path home) throws IOException {
        Path folder = copilotFolder(home);
        Path state = state(home);
        if (!Files.isDirectory(folder)) {
            Files.deleteIfExists(state);
            return "Skill: not installed for GitHub Copilot; nothing removed.";
        }
        Properties written = new Properties();
        if (Files.isRegularFile(state)) try (var in = Files.newInputStream(state)) {
            written.load(in);
        }
        List<String> kept = new ArrayList<>();
        for (String name : written.stringPropertyNames()) {
            Path file = folder.resolve(name).normalize();
            if (!file.startsWith(folder) || !Files.isRegularFile(file)) continue;
            if (written.getProperty(name).equals(SkillInstaller.sha(Files.readAllBytes(file)))) Files.delete(file);
            else kept.add(name);
        }
        deleteEmptyFolders(folder);
        Files.deleteIfExists(state);
        if (Files.isDirectory(folder)) {
            return "Skill removed; files you changed or added were kept"
                    + (kept.isEmpty() ? "." : ": " + String.join(", ", kept) + ".");
        }
        return "Skill removed for GitHub Copilot.";
    }

    static String status(Path home) {
        if (Files.isRegularFile(claudeFolder(home).resolve("SKILL.md"))) {
            return "Copilot reads the skill from the Claude Code folder (~/.claude/skills).";
        }
        if (Files.isRegularFile(copilotFolder(home).resolve("SKILL.md"))) {
            return "Installed for GitHub Copilot (~/.copilot/skills).";
        }
        return "Not installed for GitHub Copilot.";
    }

    /** Deletes the folder and its subfolders bottom-up while they are empty; files are never deleted here. */
    private static void deleteEmptyFolders(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) if (Files.isDirectory(child)) deleteEmptyFolders(child);
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            if (children.iterator().hasNext()) return;
        }
        Files.delete(folder);
    }

    private static Path home() { return Path.of(System.getProperty("user.home")); }
}
