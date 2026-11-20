package org.learningfromexperience.agentdebug.setup;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Resolves user-local storage without assuming the user's name or a drive letter. */
final class InstallPaths {
    private InstallPaths() { }

    static Path appDirectory(String osName, String home, Map<String, String> env) {
        String system = osName.toLowerCase(Locale.ROOT);
        Path base;
        if (system.contains("win")) {
            String localAppData = env.get("LOCALAPPDATA");
            base = defined(localAppData) ? Path.of(localAppData) : Path.of(home, "AppData", "Local");
        } else if (system.contains("mac")) {
            base = Path.of(home, "Library", "Application Support");
        } else {
            String xdg = env.get("XDG_DATA_HOME");
            base = defined(xdg) && Path.of(xdg).isAbsolute()
                    ? Path.of(xdg) : Path.of(home, ".local", "share");
        }
        return base.resolve("agentic-debugger-for-eclipse").toAbsolutePath().normalize();
    }

    static Path javaExecutable(String javaHome, String osName) {
        return Path.of(javaHome, "bin", osName.toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
    }

    private static boolean defined(String value) {
        return value != null && !value.isBlank();
    }
}
