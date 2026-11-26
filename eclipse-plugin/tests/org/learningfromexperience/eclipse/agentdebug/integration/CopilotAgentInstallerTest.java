package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for the GitHub Copilot custom agent written into a project ({@link CopilotAgentInstaller},
 * OPEN-23 step 2). No Eclipse and no Copilot needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class CopilotAgentInstallerTest {

    private static final String V1 = "---\nname: Agentic Debugger\n---\nUse the eclipse-debugger tools.\n";
    private static final String V2 = "---\nname: Agentic Debugger\n---\nUse the eclipse-debugger tools. Observe first.\n";

    @TempDir Path project;

    private Path file() { return project.resolve(CopilotAgentInstaller.AGENT_FILE); }

    private String read(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8); }

    @Test
    @DisplayName("CA-U1 a missing agent is written to .github/agents/agentic_debugger.agent.md with the checksum comment")
    void caU1() throws Exception {
        assertEquals(CopilotAgentInstaller.Result.CREATED, CopilotAgentInstaller.install(project, V1));
        String written = read(file());
        assertTrue(written.startsWith(V1.stripTrailing()), "the template text comes first");
        assertTrue(written.contains(CopilotAgentInstaller.MARK), "the checksum comment follows");
        assertTrue(CopilotAgentInstaller.writtenByPluginUnedited(written));
    }

    @Test
    @DisplayName("CA-U2 the same template again leaves the file alone")
    void caU2() throws Exception {
        CopilotAgentInstaller.install(project, V1);
        var time = Files.getLastModifiedTime(file());
        assertEquals(CopilotAgentInstaller.Result.UNCHANGED, CopilotAgentInstaller.install(project, V1));
        assertEquals(time, Files.getLastModifiedTime(file()));
    }

    @Test
    @DisplayName("CA-U3 an unedited older version is replaced by the new template")
    void caU3() throws Exception {
        CopilotAgentInstaller.install(project, V1);
        assertEquals(CopilotAgentInstaller.Result.UPDATED, CopilotAgentInstaller.install(project, V2));
        assertTrue(read(file()).contains("Observe first."));
        assertFalse(Files.exists(file().resolveSibling("agentic_debugger.agent.md.new")));
    }

    @Test
    @DisplayName("CA-U4 a file the user edited (or wrote) is kept; the new version goes next to it as .new")
    void caU4() throws Exception {
        CopilotAgentInstaller.install(project, V1);
        String edited = read(file()).replace("Use the eclipse-debugger tools.", "Use the eclipse-debugger tools. Mine.");
        Files.writeString(file(), edited, StandardCharsets.UTF_8);
        assertEquals(CopilotAgentInstaller.Result.KEPT_EDITED, CopilotAgentInstaller.install(project, V2));
        assertEquals(edited, read(file()), "the user's file is untouched");
        assertTrue(read(file().resolveSibling("agentic_debugger.agent.md.new")).contains("Observe first."));

        Files.writeString(file(), "---\nname: My own agent\n---\n", StandardCharsets.UTF_8);
        assertEquals(CopilotAgentInstaller.Result.KEPT_EDITED, CopilotAgentInstaller.install(project, V2),
                "a file without our checksum comment is the user's");
    }

    @Test
    @DisplayName("CA-U6 remove: an unedited agent and its .new copy go; empty .github folders go, other files stay")
    void caU6() throws Exception {
        CopilotAgentInstaller.install(project, V1);
        Files.writeString(file().resolveSibling("agentic_debugger.agent.md.new"), "x", StandardCharsets.UTF_8);
        assertEquals(CopilotAgentInstaller.Removal.REMOVED, CopilotAgentInstaller.remove(project, false));
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(project.resolve(".github")), "both folders were empty, so both are gone");

        CopilotAgentInstaller.install(project, V1);
        Path other = project.resolve(".github/agents/my-own.agent.md");
        Path workflow = project.resolve(".github/workflows/build.yml");
        Files.createDirectories(workflow.getParent());
        Files.writeString(other, "mine", StandardCharsets.UTF_8);
        Files.writeString(workflow, "on: push", StandardCharsets.UTF_8);
        assertEquals(CopilotAgentInstaller.Removal.REMOVED, CopilotAgentInstaller.remove(project, false));
        assertFalse(Files.exists(file()));
        assertTrue(Files.exists(other), "another agent stays");
        assertTrue(Files.exists(workflow), "other .github content stays");
    }

    @Test
    @DisplayName("CA-U7 remove: an edited agent needs the user's confirmation; without it nothing is touched")
    void caU7() throws Exception {
        CopilotAgentInstaller.install(project, V1);
        String edited = read(file()) + "\nMy own line.\n";
        Files.writeString(file(), edited, StandardCharsets.UTF_8);
        assertEquals(CopilotAgentInstaller.Removal.EDITED_NEEDS_CONFIRMATION, CopilotAgentInstaller.remove(project, false));
        assertEquals(edited, read(file()), "untouched without confirmation");
        assertEquals(CopilotAgentInstaller.Removal.REMOVED, CopilotAgentInstaller.remove(project, true));
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("CA-U8 remove: no agent in the project changes nothing")
    void caU8() throws Exception {
        assertEquals(CopilotAgentInstaller.Removal.NOT_PRESENT, CopilotAgentInstaller.remove(project, false));
        assertFalse(Files.exists(project.resolve(".github")));
    }

    @Test
    @DisplayName("CA-U5 CRLF line endings (editor, Git) do not count as an edit")
    void caU5() throws Exception {
        CopilotAgentInstaller.install(project, V1);
        Files.writeString(file(), read(file()).replace("\n", "\r\n"), StandardCharsets.UTF_8);
        assertEquals(CopilotAgentInstaller.Result.UNCHANGED, CopilotAgentInstaller.install(project, V1));
        assertEquals(CopilotAgentInstaller.Result.UPDATED, CopilotAgentInstaller.install(project, V2));
    }
}
