package org.learningfromexperience.eclipse.agentdebug.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for copying the bundled MCP server jar to its stable location
 * ({@link RuntimeJarInstaller}). Works in a JUnit temporary folder; no Eclipse needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class RuntimeJarInstallerTest {

    @TempDir
    Path root;

    private Path source;
    private Path target;

    private byte[] writeSource(byte value) throws Exception {
        byte[] content = new byte[1500];
        Arrays.fill(content, value);
        source = root.resolve("bundled.jar");
        target = root.resolve("installed").resolve("mcp.jar");
        Files.write(source, content);
        return content;
    }

    private boolean install() throws Exception {
        return RuntimeJarInstaller.installIfChanged(source.toUri().toURL(), target);
    }

    @Test
    @DisplayName("RJ-U1 the first install copies the jar")
    void rjU1() throws Exception {
        byte[] first = writeSource((byte) 7);
        assertTrue(install(), "first install");
        assertArrayEquals(first, Files.readAllBytes(target));
    }

    @Test
    @DisplayName("RJ-U2 an unchanged jar is skipped and keeps its timestamp")
    void rjU2() throws Exception {
        writeSource((byte) 7);
        install();
        FileTime original = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(target, original);
        assertFalse(install(), "unchanged skip");
        assertEquals(original, Files.getLastModifiedTime(target));
    }

    @Test
    @DisplayName("RJ-U3 a changed jar replaces the installed one")
    void rjU3() throws Exception {
        writeSource((byte) 7);
        install();
        byte[] second = writeSource((byte) 9);
        assertTrue(install(), "changed replace");
        assertArrayEquals(second, Files.readAllBytes(target));
    }

    @Test
    @DisplayName("RJ-U4 a deleted installed jar is restored")
    void rjU4() throws Exception {
        writeSource((byte) 7);
        install();
        Files.delete(target);
        assertTrue(install(), "missing restore");
    }
}
