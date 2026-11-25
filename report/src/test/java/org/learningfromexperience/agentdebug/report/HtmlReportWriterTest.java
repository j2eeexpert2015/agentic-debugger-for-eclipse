package org.learningfromexperience.agentdebug.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.learningfromexperience.agentdebug.report.ReportEvent.Outcome;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepKind;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepStatus;
import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * JUnit 5 unit tests for {@link HtmlReportWriter} (HW-U1 to HW-U5, OPEN-30 and OPEN-38 step 4): the debugging session
 * report as the same page as the Agent Activity view, one self-contained file per investigation.
 *
 * @author Ayan Dutta
 */
class HtmlReportWriterTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant T0 = Instant.parse("2026-11-25T05:00:00Z");

    @TempDir
    Path folder;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
        // In the MCP server stdout carries the MCP protocol: the writer must not write anything there.
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8), "the report writer wrote to stdout");
    }

    private HtmlReportWriter writer() throws IOException {
        return new HtmlReportWriter(new ReportSettings("html", folder, "Agentic Debugger for Eclipse"), ZONE);
    }

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    /** One tool call as the MCP server reports it: started, then its result. */
    private static void step(HtmlReportWriter w, String id, int second, StepKind kind, StepStatus status, String title,
                             String... details) throws Exception {
        w.stepStarted(new ReportEvent.StepStarted(id, at(second), kind, title));
        w.stepRecorded(new ReportEvent.StepRecorded(id, at(second + 1), kind, status, title, List.of(details), List.of(), 900));
    }

    /** The boot-quote investigation up to the finish: launch, breakpoint, stop with variables. */
    private static void bootQuote(HtmlReportWriter w, String id) throws Exception {
        w.investigationStarted(new ReportEvent.InvestigationStarted(id, at(0), "NORMAL", "claude-code",
                "POST /orders/quote returns 120.00 instead of 108.00 for 10% off. Find out why.", true));
        step(w, id, 1, StepKind.LAUNCH, StepStatus.OK, "Started application in debug mode: OrdersApplication",
                "Project spring-boot-debugging", ReportEvent.TECHNICAL_DETAILS, "Tool: start_java_application");
        step(w, id, 3, StepKind.BREAKPOINT, StepStatus.OK, "Set breakpoint: OrderService, line 24");
        step(w, id, 5, StepKind.STOP, StepStatus.OK, "Stopped at breakpoint: OrderService.quote, line 24",
                "Variables:", "  percent = 10 (int)", "  rate = 0 (int)");
    }

    @Test
    @DisplayName("HW-U1 a finished investigation becomes one self-contained file, the same page as the view")
    void hwU1() throws Exception {
        HtmlReportWriter w = writer();
        bootQuote(w, "inv-7f3a");
        w.stepStarted(new ReportEvent.StepStarted("inv-7f3a", at(9), StepKind.CLEANUP, "Finishing the investigation"));
        Path chosen = folder.resolve("OrdersApplication_2026-11-25_10-30-00.html");
        w.investigationFinished(new ReportEvent.InvestigationFinished("inv-7f3a", at(10), Outcome.FINISHED,
                "**Root cause:** `rate = percent / 100` is integer division, so **rate** is 0.",
                List.of("Removed 1 breakpoint", "JVM ended: true"), chosen));
        w.close();

        assertEquals(List.of(chosen.toAbsolutePath().normalize()), w.writtenReports(), "written where the MCP server chose");
        String html = Files.readString(chosen, StandardCharsets.UTF_8);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<title>Debugging OrdersApplication - debugging session report - 10:30:00</title>"), html);
        assertTrue(html.contains("<span class=\"rep\">Debugging session report") && html.contains("Agentic Debugger for Eclipse"),
                "what the page is");
        assertTrue(html.contains("<h1>Debugging OrdersApplication</h1>"));
        assertTrue(html.contains("returns 120.00 instead of 108.00"), "the request");
        assertTrue(html.contains("Set breakpoint: <a class=\"src\""), "the steps, source references as in the view");
        assertTrue(html.contains("rate = 0 (int)"), "what happened at a step");
        assertTrue(html.contains("<h2>From the agent</h2>") && html.contains("<strong>rate</strong>"),
                "the analysis, rendered from Markdown");
        assertTrue(html.contains("Finished investigation and cleaned up") && html.contains("Removed 1 breakpoint"),
                "the cleanup step with its lines");
        assertTrue(html.contains("banner done") && html.contains("<span id=\"timer\">10.0 s</span>"), "finished, time taken written in");
        assertTrue(html.contains("<details open data-i=\"1\""), "steps open without the script");
        assertTrue(html.contains("Expand All") && html.contains("Collapse All"));
        // One file: nothing loaded from elsewhere, no Eclipse-only parts.
        assertFalse(html.contains("<link") || html.contains(" src=\""), "nothing linked or loaded");
        assertFalse(html.contains("Open HTML Report"), "no button that needs Eclipse");
        assertFalse(html.contains("id=\"follow\""), "no Follow in a finished report");
        assertFalse(html.contains("Report: " + chosen), "the file does not name itself");
    }

    @Test
    @DisplayName("HW-U2 an investigation still open when the session ends is written at close, named by what was debugged")
    void hwU2() throws Exception {
        HtmlReportWriter w = writer();
        bootQuote(w, "inv-open");
        w.investigationStarted(new ReportEvent.InvestigationStarted("inv-nolaunch", at(20), "NORMAL", "claude-code"));
        w.close();

        List<String> names = w.writtenReports().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("OrdersApplication_2026-11-25_10-30-00.html", "Investigation_2026-11-25_10-30-20.html"), names);
        String html = Files.readString(w.writtenReports().get(0), StandardCharsets.UTF_8);
        assertTrue(html.contains("banner ended") && html.contains("Ended without the agent finishing it"), "how it ended");
        assertFalse(html.contains("class=\"live\""), "nothing is shown as still running");
        assertFalse(html.contains("<h2>From the agent</h2>"), "no analysis: the agent never finished");
    }

    @Test
    @DisplayName("HW-U3 text from the agent and the program is escaped")
    void hwU3() throws Exception {
        HtmlReportWriter w = writer();
        w.investigationStarted(new ReportEvent.InvestigationStarted("inv-x", at(0), "NORMAL", "claude-code",
                "Why <img src=x onerror=alert(1)>?", false));
        step(w, "inv-x", 1, StepKind.INSPECT, StepStatus.OK, "Evaluated expression: <b>name</b>", "value = </pre><script>alert(2)</script>");
        w.investigationFinished(new ReportEvent.InvestigationFinished("inv-x", at(5), Outcome.FINISHED,
                "<script>alert(3)</script>", List.of(), folder.resolve("x.html")));
        String html = Files.readString(folder.resolve("x.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("<img src=x"), "request escaped");
        assertFalse(html.contains("<b>name</b>"), "step title escaped");
        for (int n = 1; n <= 3; n++) assertFalse(html.contains("<script>alert(" + n + ")"), "script " + n + " escaped");
    }

    @Test
    @DisplayName("HW-U4 a cleanup the MCP server did by itself is shown as a step with its lines")
    void hwU4() throws Exception {
        HtmlReportWriter w = writer();
        bootQuote(w, "inv-lease");
        w.investigationFinished(new ReportEvent.InvestigationFinished("inv-lease", at(300), Outcome.ENDED_WITHOUT_FINISH,
                null, List.of("Idle lease expired", "Removed 1 breakpoint"), folder.resolve("lease.html")));
        String html = Files.readString(folder.resolve("lease.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("Cleaned up automatically") && html.contains("Idle lease expired"), html);
        assertTrue(html.contains("Ended without the agent finishing it"));
    }

    @Test
    @DisplayName("HW-U5 report and view use the same step kinds and separator as the events")
    void hwU5() {
        // ReportFormat names kinds and the separator itself (it predates the merge); they must match the events.
        assertEquals(ReportEvent.TECHNICAL_DETAILS, ReportFormat.TECHNICAL_DETAILS);
        assertEquals(Stream.of(StepKind.values()).map(Enum::name).toList(), ReportFormat.KINDS);
        for (StepKind kind : StepKind.values())
            assertTrue(ReportFormat.kindColour(kind.name()).startsWith("#"), "no colour for " + kind);
    }

    @Test
    @DisplayName("HW-U6 with events kept, the report is built again from them without Claude, identical (report-replay)")
    void hwU6() throws Exception {
        HtmlReportWriter w = new HtmlReportWriter(new ReportSettings("html", folder, "Agentic Debugger for Eclipse"),
                ZONE, true);
        bootQuote(w, "inv-7f3a");
        w.stepStarted(new ReportEvent.StepStarted("inv-7f3a", at(9), StepKind.CLEANUP, "Finishing the investigation"));
        Path chosen = folder.resolve("OrdersApplication_2026-11-25_10-30-00.html");
        w.investigationFinished(new ReportEvent.InvestigationFinished("inv-7f3a", at(10), Outcome.FINISHED,
                "## Root cause\nInteger division.", List.of("JVM ended: true"), chosen,
                "{\"title\":\"Integer division\",\"trackedValue\":{\"name\":\"rate\",\"expected\":\"0.1\",\"actual\":\"0\"}}"));
        w.close();
        Path events = folder.resolve("OrdersApplication_2026-11-25_10-30-00.events.jsonl");
        assertTrue(Files.isRegularFile(events), "the events next to the report");
        String replayed = ReportReplay.replay(Files.readAllLines(events, StandardCharsets.UTF_8), ZONE);
        assertEquals(Files.readString(chosen, StandardCharsets.UTF_8), replayed, "the same page, rebuilt");
        assertEquals("REPORT CHECK PASS", ReportReplay.check(replayed));
        assertTrue(ReportReplay.check("<html></html>").startsWith("REPORT CHECK FAIL  missing: request"));

        Path plain = folder.resolve("plain");
        HtmlReportWriter normal = new HtmlReportWriter(new ReportSettings("html", plain, "Agentic Debugger for Eclipse"), ZONE);
        bootQuote(normal, "inv-2");
        normal.investigationFinished(new ReportEvent.InvestigationFinished("inv-2", at(10), Outcome.FINISHED, "x",
                List.of(), plain.resolve("r.html")));
        try (var files = Files.list(plain)) {
            assertEquals(List.of("r.html"), files.map(p -> p.getFileName().toString()).toList(),
                    "without the setting (real users): no events file");
        }
    }
}
