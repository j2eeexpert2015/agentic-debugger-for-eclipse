package org.learningfromexperience.eclipse.agentdebug.integration;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * OPEN-60: applies the preference "GitHub Copilot: eclipse-debugger tools only in the Agentic Debugger agent, without
 * confirmations" (on by default) to Copilot's settings of this workspace ({@link CopilotSettings}).
 * <p>Called by {@link CopilotMcpProvider} when Copilot asks for our server (at every Eclipse start, before Copilot starts
 * the server and reads its tool settings), and when the user changes the preference. On: our tools off for Copilot's
 * Agent mode, then, only if that worked, our server auto-approved. Off: both undone, but only what the plugin itself
 * did (remembered in our own preferences). Without Copilot installed nothing happens.
 *
 * @author Ayan Dutta
 */
public final class CopilotIntegration {

    public static final String PLUGIN_ID = "org.learningfromexperience.eclipse.agentdebug";
    static final String COPILOT_UI = "com.microsoft.copilot.eclipse.ui";
    static final String TEMPLATE = "copilot/agentic_debugger.agent.md";

    /** Our preference: on (default) = our tools only in the Agentic Debugger agent, without confirmations. */
    public static final String AGENT_ONLY = "copilotAgentOnly";
    /** Our record that the plugin switched our tools off in Agent mode / added the auto-approve entry. */
    static final String ISOLATED_BY_US = "copilotIsolatedByPlugin";
    static final String APPROVED_BY_US = "copilotAutoApproveAddedByPlugin";

    private static volatile String lastResult = "Not applied yet.";
    /** OPEN-69: true when this Eclipse start switched our tools off for Copilot's Agent mode (or found them off). */
    private static volatile boolean isolatedThisSession;
    /** OPEN-69: set while {@link #apply} writes, so the watcher ignores our own changes. */
    private static volatile boolean applying;
    private static volatile IEclipsePreferences.IPreferenceChangeListener watcher;

    private CopilotIntegration() { }

    /** True when GitHub Copilot for Eclipse is installed. */
    public static boolean copilotInstalled() { return Platform.getBundle(COPILOT_UI) != null; }

    /** Copilot's version ("0.22.0.2026..."), or null when it is not installed. */
    public static String copilotVersion() {
        Bundle bundle = Platform.getBundle(COPILOT_UI);
        return bundle == null ? null : bundle.getVersion().toString();
    }

    public static boolean agentOnly() { return ours().getBoolean(AGENT_ONLY, true); }

    /** Saves the preference and applies it at once (Copilot may still need a restart for its Agent mode). */
    public static String setAgentOnly(boolean on) {
        IEclipsePreferences prefs = ours();
        prefs.putBoolean(AGENT_ONLY, on);
        flush(prefs);
        // Unchecking the box asks for confirmations, so our server leaves auto-approve even if the plugin did not add it.
        return apply(!on);
    }

    /**
     * OPEN-69: true when this Eclipse start switched our tools off for Copilot's Agent mode. If they are on again later,
     * Copilot switched them on (it does when the user approves our server during the session) and a restart fixes it.
     */
    public static boolean isolatedThisSession() { return isolatedThisSession; }

    /** What the last {@link #apply} did, for the preferences page and the setup check. */
    public static String lastResult() { return lastResult; }

    /** The bundled agent template; the single list of our agent's tools. */
    public static String template() throws Exception {
        Bundle bundle = FrameworkUtil.getBundle(CopilotIntegration.class);
        URL entry = bundle == null ? null : bundle.getEntry(TEMPLATE);
        if (entry == null) throw new IllegalStateException(TEMPLATE + " is missing from the plugin");
        try (InputStream in = entry.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copilot's settings of this workspace, as a {@link CopilotSettings.Store}. */
    public static CopilotSettings.Store copilotStore() {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(COPILOT_UI);
        return new CopilotSettings.Store() {
            @Override public String get(String key) { return node.get(key, null); }
            @Override public void put(String key, String value) { node.put(key, value); }
        };
    }

    /** Applies the preference to Copilot's settings; never throws. Returns one line for the user. */
    public static String apply() { return apply(false); }

    /** @param userTurnedOff the user just unchecked the box: remove our auto-approve entry whoever added it. */
    private static synchronized String apply(boolean userTurnedOff) {
        try {
            if (!copilotInstalled()) return lastResult = "GitHub Copilot is not installed.";
            List<String> tools = CopilotAgentTools.mcpToolNames(template());
            IEclipsePreferences prefs = ours();
            CopilotSettings.Store copilot = copilotStore();
            String server = CopilotAgentTools.SERVER_GROUP;
            StringBuilder said = new StringBuilder();
            applying = true;
            if (agentOnly()) {
                CopilotSettings.Result isolation = CopilotSettings.agentModeTools(copilot, server, tools, false);
                said.append(isolation.message());
                isolatedThisSession = isolation.ok();
                if (isolation.ok()) {
                    watch(tools);
                    if (isolation.outcome() == CopilotSettings.Outcome.CHANGED) prefs.putBoolean(ISOLATED_BY_US, true);
                    CopilotSettings.Result approval = CopilotSettings.autoApprove(copilot, server);
                    if (approval.outcome() == CopilotSettings.Outcome.CHANGED) prefs.putBoolean(APPROVED_BY_US, true);
                    said.append('\n').append(approval.message());
                } else {
                    // Never auto-approve without isolation: our tools would then run unasked in Agent mode too.
                    said.append('\n').append(withdrawOwnApproval(copilot, prefs, server));
                }
            } else {
                isolatedThisSession = false;
                if (prefs.getBoolean(ISOLATED_BY_US, false)) {
                    CopilotSettings.Result back = CopilotSettings.agentModeTools(copilot, server, tools, true);
                    said.append(back.message());
                    if (back.ok()) prefs.remove(ISOLATED_BY_US);
                } else {
                    said.append("All Copilot modes may use our tools.");
                }
                said.append('\n').append(userTurnedOff ? withdrawAnyApproval(copilot, prefs, server)
                        : withdrawOwnApproval(copilot, prefs, server));
            }
            flush(InstanceScope.INSTANCE.getNode(COPILOT_UI));
            flush(prefs);
            lastResult = said.toString().strip();
        } catch (Exception | LinkageError failure) {
            lastResult = "Not applied: " + failure.getMessage();
        } finally {
            applying = false;
        }
        System.out.println("[Eclipse Agentic Debugger] Copilot: " + lastResult.replace('\n', ' '));
        return lastResult;
    }

    /**
     * OPEN-69 (Ayan's video, 28 Nov): when the user approves our server during a session, Copilot switches our tools on
     * for its Agent mode again, while our auto-approval stays: Agent mode could then run them unasked until the next
     * start, which switches them off again ({@link #apply}). This listener on Copilot's settings takes back the
     * auto-approval the plugin added as soon as that happens; the next start adds it back. Installed once.
     */
    private static void watch(List<String> tools) {
        if (watcher != null) return;
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(COPILOT_UI);
        IEclipsePreferences.IPreferenceChangeListener listener = event -> {
            String key = event.getKey();
            if (applying || !(CopilotSettings.MODE_STATUS.equals(key) || CopilotSettings.LEGACY_STATUS.equals(key))) return;
            try {
                CopilotSettings.Store copilot = copilotStore();
                IEclipsePreferences prefs = ours();
                String server = CopilotAgentTools.SERVER_GROUP;
                if (!agentOnly() || !prefs.getBoolean(APPROVED_BY_US, false)
                        || CopilotSettings.agentModeToolsOff(copilot, server, tools)) return;
                String said = withdrawOwnApproval(copilot, prefs, server);
                flush(node);
                flush(prefs);
                lastResult = "Copilot turned our tools on for its Agent mode; our auto-approval is withdrawn until"
                        + " Eclipse restarts. " + said;
                System.out.println("[Eclipse Agentic Debugger] Copilot: " + lastResult);
            } catch (Exception | LinkageError failure) {
                System.err.println("[Eclipse Agentic Debugger] Copilot watcher: " + failure.getMessage());
            }
        };
        node.addPreferenceChangeListener(listener);
        watcher = listener;
    }

    private static String withdrawOwnApproval(CopilotSettings.Store copilot, IEclipsePreferences prefs, String server) {
        if (!prefs.getBoolean(APPROVED_BY_US, false)) return "";
        CopilotSettings.Result result = CopilotSettings.withdrawAutoApprove(copilot, server);
        if (result.ok()) prefs.remove(APPROVED_BY_US);
        return result.message();
    }

    private static String withdrawAnyApproval(CopilotSettings.Store copilot, IEclipsePreferences prefs, String server) {
        CopilotSettings.Result result = CopilotSettings.withdrawAutoApprove(copilot, server);
        if (result.ok()) prefs.remove(APPROVED_BY_US);
        return result.message();
    }

    private static IEclipsePreferences ours() { return InstanceScope.INSTANCE.getNode(PLUGIN_ID); }

    private static void flush(IEclipsePreferences prefs) {
        try {
            prefs.flush();
        } catch (Exception notSaved) {
            System.err.println("[Eclipse Agentic Debugger] Could not save preferences: " + notSaved.getMessage());
        }
    }
}
