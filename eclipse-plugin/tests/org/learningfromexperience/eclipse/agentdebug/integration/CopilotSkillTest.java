package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for the eclipse-debugger skill installed for GitHub Copilot on the user's request
 * ({@link CopilotSkill}, OPEN-23). A temporary folder stands for the user's home. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class CopilotSkillTest {

    @TempDir Path home;

    private static Map<String, byte[]> skill(String version) {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("SKILL.md", ("# skill " + version).getBytes(StandardCharsets.UTF_8));
        files.put("references/junit-debugging.md", "junit".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    @Test
    @DisplayName("CS-U1 without a Claude skill, the skill goes to ~/.copilot/skills/eclipse-debugger")
    void csU1() throws Exception {
        String message = CopilotSkill.install(skill("v1"), home);
        Path folder = CopilotSkill.copilotFolder(home);
        assertTrue(message.startsWith("Skill installed for GitHub Copilot"), message);
        assertEquals("# skill v1", Files.readString(folder.resolve("SKILL.md")));
        assertTrue(Files.exists(folder.resolve("references/junit-debugging.md")));
        assertTrue(CopilotSkill.status(home).startsWith("Installed for GitHub Copilot"));
        assertTrue(CopilotSkill.install(skill("v1"), home).startsWith("Skill: already up to date"));
    }

    @Test
    @DisplayName("CS-U2 with the skill already in ~/.claude/skills, nothing is installed (Copilot reads it there)")
    void csU2() throws Exception {
        Path claude = CopilotSkill.claudeFolder(home);
        Files.createDirectories(claude);
        Files.writeString(claude.resolve("SKILL.md"), "# claude copy");
        String message = CopilotSkill.install(skill("v1"), home);
        assertTrue(message.contains("Copilot reads it from"), message);
        assertFalse(Files.exists(CopilotSkill.copilotFolder(home)), "no second copy");
        assertTrue(CopilotSkill.status(home).startsWith("Copilot reads the skill from"));
    }

    @Test
    @DisplayName("CS-U3 remove deletes the plugin's unchanged files and the empty folders; the state file goes too")
    void csU3() throws Exception {
        CopilotSkill.install(skill("v1"), home);
        String message = CopilotSkill.remove(home);
        assertTrue(message.startsWith("Skill removed for GitHub Copilot ("), message);
        assertFalse(Files.exists(CopilotSkill.copilotFolder(home)));
        assertFalse(Files.exists(CopilotSkill.state(home)));
        assertEquals("Not installed for GitHub Copilot.", CopilotSkill.status(home));
        assertTrue(CopilotSkill.remove(home).contains("nothing removed"));
    }

    @Test
    @DisplayName("CS-U4 remove keeps files the user changed or added")
    void csU4() throws Exception {
        CopilotSkill.install(skill("v1"), home);
        Path folder = CopilotSkill.copilotFolder(home);
        Files.writeString(folder.resolve("SKILL.md"), "# my edits");
        Files.writeString(folder.resolve("notes.md"), "mine");
        String message = CopilotSkill.remove(home);
        assertTrue(message.contains("SKILL.md"), message);
        assertEquals("# my edits", Files.readString(folder.resolve("SKILL.md")));
        assertTrue(Files.exists(folder.resolve("notes.md")));
        assertFalse(Files.exists(folder.resolve("references")), "the unchanged reference and its folder went");
    }
}
