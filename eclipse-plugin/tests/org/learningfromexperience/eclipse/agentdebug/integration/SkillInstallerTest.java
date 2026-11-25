package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for {@link SkillInstaller}, the plugin keeping the Claude Code skill up to date (SI-U1 to
 * SI-U4, OPEN-32). Works in a JUnit temporary folder; no Eclipse needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class SkillInstallerTest {

    @TempDir
    Path root;

    private Path skill() {
        return root.resolve("skills").resolve("eclipse-debugger");
    }

    private Path state() {
        return root.resolve("agentdebug").resolve("skill-installed.properties");
    }

    private static Map<String, byte[]> bundle(String skill, String reference) {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("SKILL.md", skill.getBytes(StandardCharsets.UTF_8));
        files.put("references/java-debugging.md", reference.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private String read(String name) throws Exception {
        return Files.readString(skill().resolve(name), StandardCharsets.UTF_8);
    }

    private List<String> backups() throws Exception {
        try (Stream<Path> files = Files.list(skill())) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.contains(".bak-")).toList();
        }
    }

    @Test @DisplayName("SI-U1 first install writes every file; a second run with the same files changes nothing")
    void siU1() throws Exception {
        List<String> first = SkillInstaller.install(bundle("v1", "ref"), skill(), state());
        assertEquals(List.of("SKILL.md: installed", "references/java-debugging.md: installed"), first);
        assertEquals("v1", read("SKILL.md"));
        FileTime written = Files.getLastModifiedTime(skill().resolve("SKILL.md"));
        Thread.sleep(20);
        assertEquals(List.of(), SkillInstaller.install(bundle("v1", "ref"), skill(), state()), "already current");
        assertEquals(written, Files.getLastModifiedTime(skill().resolve("SKILL.md")), "not written again");
    }

    @Test @DisplayName("SI-U2 a newer plugin replaces what the plugin wrote before")
    void siU2() throws Exception {
        SkillInstaller.install(bundle("v1", "ref"), skill(), state());
        assertEquals(List.of("SKILL.md: updated"), SkillInstaller.install(bundle("v2", "ref"), skill(), state()));
        assertEquals("v2", read("SKILL.md"));
        assertEquals(List.of(), backups(), "no backup of the plugin's own earlier copy");
    }

    @Test @DisplayName("SI-U3 a copy made by hand before (unknown to the plugin) is kept as a backup, then replaced")
    void siU3() throws Exception {
        Files.createDirectories(skill());
        Files.writeString(skill().resolve("SKILL.md"), "copied by hand", StandardCharsets.UTF_8);
        Files.writeString(skill().resolve("notes.md"), "the user's own file", StandardCharsets.UTF_8);
        List<String> report = SkillInstaller.install(bundle("v2", "ref"), skill(), state());
        assertTrue(report.get(0).startsWith("SKILL.md: updated (the previous copy is kept as SKILL.md.bak-"), report.toString());
        assertEquals("v2", read("SKILL.md"));
        assertEquals(1, backups().size());
        assertEquals("copied by hand", Files.readString(skill().resolve(backups().get(0)), StandardCharsets.UTF_8));
        assertEquals("the user's own file", read("notes.md"), "files the plugin does not bundle are never touched");
    }

    @Test @DisplayName("SI-U4 the user's edits after the plugin wrote the file are kept; the new version goes next to it")
    void siU4() throws Exception {
        SkillInstaller.install(bundle("v1", "ref"), skill(), state());
        Files.writeString(skill().resolve("SKILL.md"), "v1 with my own rule", StandardCharsets.UTF_8);
        List<String> report = SkillInstaller.install(bundle("v2", "ref"), skill(), state());
        assertEquals(List.of("SKILL.md: kept your edited copy; the new version is next to it as SKILL.md.new"), report);
        assertEquals("v1 with my own rule", read("SKILL.md"));
        assertEquals("v2", read("SKILL.md.new"));
    }
}
