package org.learningfromexperience.eclipse.agentdebug.integration;

import java.util.ArrayList;
import java.util.List;

/**
 * OPEN-60: the tools of the "Agentic Debugger" custom agent for GitHub Copilot, read from the agent template's
 * {@code tools:} list ({@code copilot/agentic_debugger.agent.md}, the single place they are written down).
 * <p>Copilot names an MCP tool {@code <server>/<tool>}, where {@code <server>} is the plug-in's display name, ": " and
 * the server's key ({@link #SERVER_GROUP}); its own tools by their plain name ({@code read_file}). With a
 * {@code tools:} list the agent gets exactly these tools, whatever is switched on for Copilot's Agent mode, so the
 * plugin can switch our tools off for every other mode ({@link CopilotSettings}) without taking them from our agent.
 * The test {@code CopilotAgentToolsTest} keeps the list equal to the tools the MCP server registers.
 *
 * @author Ayan Dutta
 */
public final class CopilotAgentTools {

    /** The plug-in's display name (MANIFEST.MF Bundle-Name), which Copilot puts in front of the server key. */
    public static final String PLUGIN_DISPLAY_NAME = "Agentic Debugger for Eclipse IDE";

    /** Our server's key in the JSON offered to Copilot (the same as {@code ClaudeIntegration.SERVER}). */
    public static final String SERVER_KEY = "eclipse-debugger";

    /** How Copilot names our MCP server in its tool lists and settings. */
    public static final String SERVER_GROUP = PLUGIN_DISPLAY_NAME + ": " + SERVER_KEY;

    /** Copilot's read-only tools our agent may use to find and read source code. */
    public static final List<String> COPILOT_READ_TOOLS = List.of("read_file", "file_search", "grep_search", "list_dir");

    /** Front-matter line that keeps Copilot's other agents from starting ours (documented name). */
    public static final String NO_MODEL_INVOCATION = "disable-model-invocation: true";

    /** The same for Copilot for Eclipse 0.22 (language server 1.541), which reads only this key. */
    public static final String USER_ONLY_POLICY = "x-github-copilot-invoke-policy: [user]";

    private CopilotAgentTools() { }

    /** Every entry of the front matter's {@code tools:} list, in order; empty when there is none. */
    public static List<String> toolEntries(String template) {
        List<String> entries = new ArrayList<>();
        List<String> header = frontMatter(template);
        boolean inTools = false;
        for (String line : header) {
            if (line.equals("tools:")) { inTools = true; continue; }
            if (!inTools) continue;
            String trimmed = line.strip();
            if (!line.startsWith(" ") || !trimmed.startsWith("- ")) break;
            String value = trimmed.substring(2).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            entries.add(value);
        }
        return entries;
    }

    /** Our MCP tool names from the template's {@code tools:} list (without the server prefix). */
    public static List<String> mcpToolNames(String template) {
        List<String> names = new ArrayList<>();
        String prefix = SERVER_GROUP + "/";
        for (String entry : toolEntries(template)) if (entry.startsWith(prefix)) names.add(entry.substring(prefix.length()));
        return names;
    }

    /** True when the front matter has both lines that keep other agents from starting ours. */
    public static boolean userOnly(String agentText) {
        List<String> header = frontMatter(agentText);
        return header.contains(NO_MODEL_INVOCATION) && header.contains(USER_ONLY_POLICY);
    }

    /** The lines between the first two {@code ---} lines (no line ends); empty without front matter. */
    static List<String> frontMatter(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        String[] all = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (all.length == 0 || !all[0].strip().equals("---")) return lines;
        for (int i = 1; i < all.length; i++) {
            if (all[i].strip().equals("---")) return lines;
            lines.add(all[i].stripTrailing());
        }
        return new ArrayList<>();   // no closing line: Copilot reports the header as broken
    }
}
