package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * OPEN-23 step 2: writes the "Agentic Debugger" custom agent for GitHub Copilot into a project, as
 * {@code .github/agents/agentic_debugger.agent.md} (the folder Copilot for Eclipse reads custom agents from). The user
 * then chooses "Agentic Debugger" in Copilot Chat's mode list, and Copilot debugs with the eclipse-debugger tools
 * instead of handing off to its built-in Debugger mode and {@code java_debugger} (seen 26 Nov 2026).
 * <p>Only on the user's request (project menu). <b>Never loses the user's edits:</b> the written file ends with a
 * comment that holds the checksum of the text the plugin wrote, so no extra file is put into the project.
 * <ul>
 *   <li>missing: written ({@link Result#CREATED});</li>
 *   <li>the same as the current template: left alone ({@link Result#UNCHANGED});</li>
 *   <li>still exactly what the plugin wrote (an older template): replaced ({@link Result#UPDATED});</li>
 *   <li>changed by the user, or not written by the plugin: left as it is, the new version is written next to it as
 *       {@code agentic_debugger.agent.md.new}, which Copilot does not load ({@link Result#KEPT_EDITED}).</li>
 * </ul>
 * Line endings do not count as edits (an editor or Git may turn them into CRLF). Pure file code, no Eclipse needed
 * (unit tests CA-U1..U5).
 *
 * @author Ayan Dutta
 */
public final class CopilotAgentInstaller {

    /** Where Copilot for Eclipse reads custom agents, relative to the project folder. */
    public static final String AGENT_FILE = ".github/agents/agentic_debugger.agent.md";

    static final String MARK = "<!-- Written by Agentic Debugger for Eclipse. Template checksum: ";
    private static final String MARK_END = " -->";

    /** What {@link #install} did. */
    public enum Result { CREATED, UPDATED, UNCHANGED, KEPT_EDITED }

    private CopilotAgentInstaller() { }

    /**
     * @param project  the project folder
     * @param template the bundled agent text (without the checksum comment)
     */
    public static Result install(Path project, String template) throws IOException {
        Path file = project.resolve(AGENT_FILE);
        String content = withMark(template);
        if (!Files.exists(file)) {
            write(file, content);
            return Result.CREATED;
        }
        String current = Files.readString(file, StandardCharsets.UTF_8);
        if (normalize(current).equals(normalize(content))) return Result.UNCHANGED;
        if (writtenByPluginUnedited(current)) {
            write(file, content);
            return Result.UPDATED;
        }
        write(file.resolveSibling(file.getFileName() + ".new"), content);
        return Result.KEPT_EDITED;
    }

    /** The template followed by the checksum comment, as written into the project. */
    static String withMark(String template) {
        String body = normalize(template).stripTrailing();
        return body + "\n\n" + MARK + sha(body) + MARK_END + "\n";
    }

    /** True when the text ends with our checksum comment and the text before it still has that checksum. */
    static boolean writtenByPluginUnedited(String text) {
        String normalized = normalize(text);
        int at = normalized.lastIndexOf(MARK);
        if (at < 0) return false;
        int end = normalized.indexOf(MARK_END, at);
        if (end < 0) return false;
        String recorded = normalized.substring(at + MARK.length(), end).trim();
        String body = normalized.substring(0, at).stripTrailing();
        return recorded.equals(sha(body));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
