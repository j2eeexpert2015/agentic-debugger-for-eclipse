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
    private static final Pattern FIELD = Pattern.compile("(?m)^\\s*(Scope|Type|Command|Args):\\s*(.*?)\\s*$");
    final String scope;
    final String type;
    final String command;
    final String args;

    private ClaudeRegistration(String scope, String type, String command, String args) {
        this.scope = scope; this.type = type; this.command = command; this.args = args;
    }

    static ClaudeRegistration parse(String text) {
        String scope = "", type = "", command = "", args = "";
        Matcher matcher = FIELD.matcher(text);
        while (matcher.find()) {
            switch (matcher.group(1)) {
                case "Scope" -> scope = matcher.group(2);
                case "Type" -> type = matcher.group(2);
                case "Command" -> command = matcher.group(2);
                case "Args" -> args = matcher.group(2);
                default -> { }
            }
        }
        return new ClaudeRegistration(scope, type, command, args);
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
        return userScopedStdio() && samePath(registeredJar(), jar.toAbsolutePath().toString());
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
}
