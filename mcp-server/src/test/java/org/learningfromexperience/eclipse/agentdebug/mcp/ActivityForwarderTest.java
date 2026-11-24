package org.learningfromexperience.eclipse.agentdebug.mcp;

import org.learningfromexperience.agentdebug.report.format.Json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.agentdebug.report.ReportEvent;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepKind;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepStatus;

/**
 * Unit tests (JUnit 5) for {@link ActivityForwarder} (AF-U1 to AF-U4, OPEN-36): the events for the Agent Activity view
 * become one authenticated bridge command each, as JSON; an older plugin or an unreachable Eclipse never breaks
 * anything. No Eclipse needed; run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ActivityForwarderTest {

    private final List<String> sent = new ArrayList<>();
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);

    private static Map<String, Object> decode(String command) {
        assertTrue(command.startsWith("ACTIVITY_EVENT "), command);
        String json = new String(Base64.getUrlDecoder().decode(command.substring("ACTIVITY_EVENT ".length())),
                StandardCharsets.UTF_8);
        return Json.obj(Json.parse(json));
    }

    @Test
    @DisplayName("AF-U1 every event becomes one ACTIVITY_EVENT command with its fields as JSON")
    void afU1() throws Exception {
        ActivityForwarder forwarder = new ActivityForwarder(command -> { sent.add(command); return "{\"status\":\"success\"}"; }, log);
        Instant at = Instant.parse("2026-11-24T10:00:00Z");
        forwarder.investigationStarted(new ReportEvent.InvestigationStarted("inv-1", at, "NORMAL", "claude-code",
                "Why is the total \"120\"?\nPlease check.", true));
        forwarder.stepStarted(new ReportEvent.StepStarted("inv-1", at, StepKind.STOP, "Waiting for the program to stop"));
        forwarder.stepRecorded(new ReportEvent.StepRecorded("inv-1", at, StepKind.STOP, StepStatus.OK,
                "Stopped at breakpoint: OrderService.quote, line 24",
                List.of("rate        int     0", ReportEvent.TECHNICAL_DETAILS, "Tool: wait_for_stop"), List.of(), 37));
        forwarder.investigationFinished(new ReportEvent.InvestigationFinished("inv-1", at, ReportEvent.Outcome.FINISHED,
                "## Root cause\n`percent / 100`", List.of("JVM ended: true"), Path.of("reports", "Orders.html")));

        assertEquals(4, sent.size());
        Map<String, Object> started = decode(sent.get(0));
        assertEquals("investigationStarted", started.get("type"));
        assertEquals("Why is the total \"120\"?\nPlease check.", started.get("request"));
        assertEquals(Boolean.TRUE, started.get("requestExact"));
        assertEquals("2026-11-24T10:00:00Z", started.get("at"));
        Map<String, Object> running = decode(sent.get(1));
        assertEquals("stepStarted", running.get("type"));
        assertEquals("STOP", running.get("kind"));
        Map<String, Object> step = decode(sent.get(2));
        assertEquals("OK", step.get("status"));
        assertEquals(List.of("rate        int     0", ReportEvent.TECHNICAL_DETAILS, "Tool: wait_for_stop"),
                Json.arr(step.get("details")));
        assertEquals("37", Json.str(step.get("durationMs")));
        Map<String, Object> finished = decode(sent.get(3));
        assertEquals("FINISHED", finished.get("outcome"));
        assertEquals("## Root cause\n`percent / 100`", finished.get("conclusion"));
        assertEquals(Path.of("reports", "Orders.html").toString(), finished.get("reportFile"));
        assertEquals("", logBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("AF-U2 an older plugin (Unknown command) stops the forwarding for the session, said once")
    void afU2() {
        ActivityForwarder forwarder = new ActivityForwarder(command -> {
            sent.add(command);
            return "{\"status\":\"error\",\"message\":\"Unknown command\"}";
        }, log);
        for (int i = 0; i < 3; i++)
            forwarder.stepStarted(new ReportEvent.StepStarted("inv-1", Instant.now(), StepKind.OTHER, "step " + i));
        assertEquals(1, sent.size(), "nothing more is sent after Unknown command");
        assertTrue(forwarder.unsupported());
        String text = logBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, text.split("no Agent Activity view yet", -1).length - 1, text);
    }

    @Test
    @DisplayName("AF-U3 Eclipse not reachable: the event is dropped without an exception, said once")
    void afU3() {
        ActivityForwarder forwarder = new ActivityForwarder(command -> { throw new IOException("Connection refused"); }, log);
        for (int i = 0; i < 3; i++)
            forwarder.stepStarted(new ReportEvent.StepStarted("inv-1", Instant.now(), StepKind.OTHER, "step " + i));
        assertFalse(forwarder.unsupported(), "an unreachable Eclipse is not an old plugin");
        String text = logBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, text.split("Eclipse not reachable", -1).length - 1, text);
    }

    @Test
    @DisplayName("AF-U4 an oversized event is not sent")
    void afU4() {
        ActivityForwarder forwarder = new ActivityForwarder(command -> { sent.add(command); return "{\"status\":\"success\"}"; }, log);
        forwarder.stepRecorded(new ReportEvent.StepRecorded("inv-1", Instant.now(), StepKind.INSPECT, StepStatus.OK,
                "Read program output", List.of("x".repeat(ActivityForwarder.MAX_JSON_CHARS)), List.of(), 1));
        assertTrue(sent.isEmpty());
    }
}
