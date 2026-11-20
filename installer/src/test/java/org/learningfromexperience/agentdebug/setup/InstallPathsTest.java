package org.learningfromexperience.agentdebug.setup;

import java.nio.file.Path;
import java.util.Map;

/** Standalone sanity tests; can be run with javac/java without additional libraries. */
public final class InstallPathsTest {
    public static void main(String[] args) {
        assertPath("C:/Users/example/AppData/Local/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Windows 11", "C:/Users/example", Map.of()));
        assertPath("/home/example/.local/share/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Linux", "/home/example", Map.of()));
        assertPath("/opt/userdata/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Linux", "/home/example", Map.of("XDG_DATA_HOME", "/opt/userdata")));
        assertPath("/Users/example/Library/Application Support/agentic-debugger-for-eclipse",
                InstallPaths.appDirectory("Mac OS X", "/Users/example", Map.of()));
        System.out.println("InstallPathsTest passed");
    }

    private static void assertPath(String expected, Path actual) {
        // Windows-style paths are meaningful only on a Windows runner.
        if (expected.startsWith("C:") && !System.getProperty("os.name").toLowerCase().contains("win")) {
            if (!actual.toString().replace('\\', '/').endsWith(expected.replace('\\', '/'))) {
                throw new AssertionError("Unexpected Windows path: " + actual);
            }
        } else if (!actual.toString().replace('\\', '/').equals(expected.replace('\\', '/'))) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
