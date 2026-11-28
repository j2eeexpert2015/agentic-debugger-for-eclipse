package org.learningfromexperience.eclipse.agentdebug.integration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the official `claude mcp get` response without reading or editing Claude's JSON.
 *
 * @author Ayan Dutta
 */
final class ClaudeRegistration {
    private static final Pattern FIELD = Pattern.compile("(?m)^\\s*(Scope|Type|Command|Args|Status):\\s*(.*?)\\s*$");
    final String scope;
    final String type;
    final String command;
    final String args;
    /** OPEN-71: Claude Code's own health check ("✓ Connected", "✗ Failed to connect"), "" when not shown. */
    final String status;

    private ClaudeRegistration(String scope, String type, String command, String args, String status) {
        this.scope = scope; this.type = type; this.command = command; this.args = args; this.status = status;
    }

    static ClaudeRegistration parse(String text) {
        String scope = "", type = "", command = "", args = "", status = "";
        Matcher matcher = FIELD.matcher(text);
        while (matcher.find()) {
            switch (matcher.group(1)) {
                case "Scope" -> scope = matcher.group(2);
                case "Type" -> type = matcher.group(2);
                case "Command" -> command = matcher.group(2);
                case "Args" -> args = matcher.group(2);
                case "Status" -> status = matcher.group(2);
                default -> { }
            }
        }
        return new ClaudeRegistration(scope, type, command, args, status);
    }

    boolean userScopedStdio() {
        return scope.toLowerCase(Locale.ROOT).startsWith("user")
                && type.equalsIgnoreCase("stdio") && !command.isBlank();
    }

    String registeredJar() {
        if (!args.startsWith("-jar ")) return "";
        String value = args.substring(5).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        // The registration must consist of exactly one JAR argument.
        if (value.isBlank() || !value.toLowerCase(Locale.ROOT).endsWith(".jar") || value.contains("\" ")) return "";
        return value;
    }

    boolean exactJar(Path jar) {
        return userScopedStdio() && samePath(registeredJar(), jar.toString());
    }

    boolean legacyJar(Path home, boolean windows) {
        if (!userScopedStdio() || !windows) return false;
        Path expected = home.resolve("AppData").resolve("Local")
                .resolve("agentic-debugger-for-eclipse").resolve("mcp-server.jar");
        return samePath(registeredJar(), expected.toString());
    }

    static boolean samePath(String first, String second) {
        if (first.isBlank() || second.isBlank()) return false;
        String a = first.replace('/', '\\');
        String b = second.replace('/', '\\');
        return a.equalsIgnoreCase(b);
    }

    // ---- OPEN-71: the launcher form and what Claude Code can start ---------------------------------------------

    /** How our entry starts the server. */
    enum Form {
        /** {@code cmd /c <launcher>}: survives Eclipse and Java updates (Windows, since 28 Nov). */
        LAUNCHER,
        /** {@code <Java of Eclipse> -jar <our jar>}: breaks when Eclipse's Java changes (JustJ folder with a version). */
        DIRECT,
        /** The previous installer's jar. */
        LEGACY,
        /** Not ours. */
        FOREIGN
    }

    /** The command Claude Code is given on Windows: {@code cmd /c <launcher>} (Claude Code starts no .cmd by itself). */
    static final String LAUNCHER_COMMAND = "cmd";

    /** True for {@code cmd /c <launcher>}, user scope, stdio. */
    boolean launcher(Path launcher) {
        if (!userScopedStdio()) return false;
        String c = command.trim();
        if (!(c.equalsIgnoreCase("cmd") || c.equalsIgnoreCase("cmd.exe"))) return false;
        String a = args.trim();
        if (!a.regionMatches(true, 0, "/c ", 0, 3)) return false;
        String value = a.substring(3).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) value = value.substring(1, value.length() - 1);
        return samePath(value, launcher.toString());
    }

    /** Which of our forms this entry is. */
    Form form(Path jar, Path launcher, Path home, boolean windows) {
        if (launcher(launcher)) return Form.LAUNCHER;
        if (exactJar(jar)) return Form.DIRECT;
        if (legacyJar(home, windows)) return Form.LEGACY;
        return Form.FOREIGN;
    }

    /** True when Claude Code's own check says it could not start or reach the server. */
    boolean failedToConnect() {
        String s = status.toLowerCase(Locale.ROOT);
        return s.contains("failed") || s.contains("\u2717");
    }

    /**
     * OPEN-71 (incident 28 Nov 14:19: the registered Java folder was gone after an Eclipse update, while the setup check
     * said OK): whether our entry still works. Pure, so it is a unit test (CR-U8).
     *
     * @param form            {@link #form}
     * @param windows         Windows, where the current form is the launcher
     * @param commandExists   the registered command is a file that exists (for the direct form: the Java)
     * @return CURRENT when it works, OLDER when Update must rewrite it, FOREIGN when it is not ours
     */
    static PreferenceStates.Registration state(Form form, boolean windows, boolean commandExists, boolean failedToConnect) {
        return switch (form) {
            case LAUNCHER -> failedToConnect ? PreferenceStates.Registration.OLDER : PreferenceStates.Registration.CURRENT;
            case DIRECT -> !windows && commandExists && !failedToConnect
                    ? PreferenceStates.Registration.CURRENT : PreferenceStates.Registration.OLDER;
            case LEGACY -> PreferenceStates.Registration.OLDER;
            case FOREIGN -> PreferenceStates.Registration.FOREIGN;
        };
    }
}
