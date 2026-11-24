package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.io.IOException;
import java.io.PrintStream;

import org.learningfromexperience.agentdebug.report.ActivityEvents;
import org.learningfromexperience.agentdebug.report.DebugReportListener;
import org.learningfromexperience.agentdebug.report.ReportEvent;

/**
 * Sends the investigation events to the Eclipse plugin for the live <b>Agent Activity</b> view (OPEN-36).
 *
 * <p><b>Decoupled like the report.</b> It is a {@link DebugReportListener} next to the HTML report writer, fed with the
 * same events by its own {@code AsyncReportRecorder}: its own queue and thread, so a slow or absent Eclipse view never
 * delays or changes a tool result, and it knows nothing of the report (ADR-009). Switched off with
 * {@code AGENTDEBUG_LIVE=off}, independently of {@code AGENTDEBUG_REPORT}.
 *
 * <p><b>Wire format.</b> One bridge command per event: {@code ACTIVITY_EVENT <base64url(JSON)>}, authenticated like
 * every bridge command; the JSON is {@link ActivityEvents}, the same the HTML report is built from.
 *
 * <p><b>Older plugins.</b> A plugin without the Agent Activity view answers "Unknown command": the forwarder then
 * stops sending for the rest of the session and says so once on stderr. When Eclipse cannot be reached the event is
 * dropped (said once); debugging itself reports that problem.
 *
 * @author Ayan Dutta
 */
final class ActivityForwarder implements DebugReportListener {

    /** Sends one bridge command and returns Eclipse's one-line reply. */
    @FunctionalInterface
    interface Bridge {
        String send(String command) throws IOException;
    }

    static final String COMMAND = "ACTIVITY_EVENT";
    /** Events larger than this are not sent (a bridge line must stay small); the view then misses one step. */
    static final int MAX_JSON_CHARS = 64_000;

    private final Bridge bridge;
    private final PrintStream log;
    private boolean unsupported;
    private boolean unreachableSaid;

    ActivityForwarder(Bridge bridge, PrintStream log) {
        this.bridge = bridge;
        this.log = log;
    }

    @Override
    public void investigationStarted(ReportEvent.InvestigationStarted e) {
        send(ActivityEvents.json(e));
    }

    @Override
    public void stepStarted(ReportEvent.StepStarted e) {
        send(ActivityEvents.json(e));
    }

    @Override
    public void stepRecorded(ReportEvent.StepRecorded e) {
        send(ActivityEvents.json(e));
    }

    @Override
    public void investigationFinished(ReportEvent.InvestigationFinished e) {
        send(ActivityEvents.json(e));
    }

    @Override
    public void eventsDropped(long count) {
        send(ActivityEvents.dropped(count));
    }

    /** True once Eclipse answered that it has no Agent Activity view (for tests and diagnostics). */
    boolean unsupported() {
        return unsupported;
    }

    private void send(String text) {
        if (unsupported) return;
        if (text.length() > MAX_JSON_CHARS) return;
        String command = COMMAND + " " + ActivityEvents.encode(text);
        try {
            String reply = bridge.send(command);
            if (reply != null && reply.contains("Unknown command")) {
                unsupported = true;
                log.println("[Eclipse Agentic Debugger] The Eclipse plugin has no Agent Activity view yet; "
                        + "live events are off for this session (update the plugin)");
            }
        } catch (IOException unreachable) {
            if (!unreachableSaid) {
                unreachableSaid = true;
                log.println("[Eclipse Agentic Debugger] Agent Activity: Eclipse not reachable, event dropped ("
                        + unreachable.getMessage() + ")");
            }
        }
    }
}
