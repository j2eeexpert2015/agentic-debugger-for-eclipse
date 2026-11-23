package org.learningfromexperience.agentdebug.setup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for where the setup utility installs the MCP server on each operating system
 * ({@link InstallPaths}). Pure path logic, no files written. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class InstallPathsTest {

    /**
     * The tests describe Windows, Linux and macOS paths but run on one machine, where a foreign path is made
     * absolute against the local drive or working folder (on Windows "/home/example" becomes "C:\home\example").
     * So the check is: with forward slashes and without a drive letter, the result ends with the expected path.
     */
    private static void assertPath(String expected, Path actual) {
        String want = withoutDrive(expected.replace('\\', '/'));
        String got = withoutDrive(actual.toString().replace('\\', '/'));
        assertTrue(got.endsWith(want), () -> "Expected a path ending in " + want + ", got " + actual);
    }

    private static String withoutDrive(String path) {
        return path.matches("^[A-Za-z]:/.*") ? path.substring(2) : path;
    }

    @Test
    @DisplayName("IP-U1 Windows: %LOCALAPPDATA% default under the user's home")
    void ipU1() {
        assertPath("C:/Users/example/AppData/Local/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Windows 11", "C:/Users/example", Map.of()));
    }

    @Test
    @DisplayName("IP-U2 Linux: ~/.local/share by default")
    void ipU2() {
        assertPath("/home/example/.local/share/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Linux", "/home/example", Map.of()));
    }

    @Test
    @DisplayName("IP-U3 Linux: an absolute XDG_DATA_HOME is respected")
    void ipU3() {
        assertPath("/opt/userdata/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Linux", "/home/example", Map.of("XDG_DATA_HOME", "/opt/userdata")));
    }

    @Test
    @DisplayName("IP-U4 macOS: ~/Library/Application Support")
    void ipU4() {
        assertPath("/Users/example/Library/Application Support/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Mac OS X", "/Users/example", Map.of()));
    }
}
