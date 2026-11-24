package org.learningfromexperience.agentdebug.report.chaintest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.agentdebug.report.ReportEvent;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepKind;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepStatus;
import org.learningfromexperience.agentdebug.report.ReportRecorder;
import org.learningfromexperience.agentdebug.report.ReportRecorders;
import org.learningfromexperience.agentdebug.report.ReportSettings;

/**
 * OPEN-30 step R1a: ChainTest prototype. Drives ChainTest's core engine through our own report layer, with no TestNG
 * or JUnit integration of ChainTest, for a scripted investigation modelled on the {@code boot-quote} scenario.
 * No Eclipse and no Claude needed; run by {@code mvn clean package}.
 *
 * <p>The reports are kept for viewing: open {@code report-chaintest/target/report-prototype/boot-quote/index.html}
 * (and {@code .../abandoned/index.html}) in a browser after the build.
 *
 * @author Ayan Dutta
 */
class ChainTestReportWriterTest {

    private static final Path PROTOTYPE = Path.of("target", "report-prototype");

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private PrintStream originalOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
        // In the MCP server stdout carries the MCP protocol: ChainTest must not write anything there.
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8), "ChainTest wrote to stdout");
    }

    private static Path fresh(String name) throws IOException {
        Path dir = PROTOTYPE.resolve(name);
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        return dir;
    }

    /** A stand-in for the Eclipse screenshot the plugin will take at a stop (R3). */
    private static Path screenshot(Path dir) throws IOException {
        Files.createDirectories(dir);
        BufferedImage image = new BufferedImage(640, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 640, 220);
        g.setColor(new Color(60, 90, 140));
        g.fillRect(0, 96, 640, 24);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        g.drawString("22      int percent = request.discountPercent();", 12, 88);
        g.drawString("24      int rate = percent / 100;      // suspended here", 12, 113);
        g.drawString("25      BigDecimal discount = subtotal.multiply(BigDecimal.valueOf(rate));", 12, 138);
        g.drawString("Placeholder for the Eclipse screenshot at the stop (OPEN-30 R3)", 12, 200);
        g.dispose();
        Path file = dir.resolve("stop-screenshot.png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static ReportEvent.StepRecorded step(StepKind kind, StepStatus status, String title, long ms, String... details) {
        return new ReportEvent.StepRecorded("inv-7f3a", Instant.now(), kind, status, title, List.of(details), List.of(), ms);
    }

    @Test
    @DisplayName("RC-U1 a scripted boot-quote investigation becomes an offline ChainTest report with steps, code, values and a screenshot")
    void rcU1() throws Exception {
        Path dir = fresh("boot-quote");
        Path shot = screenshot(PROTOTYPE.resolve("screenshots"));
        ChainTestReportWriter writer = new ChainTestReportWriter(new ReportSettings("chaintest", dir, "Agentic Debugger prototype"));

        writer.investigationStarted(new ReportEvent.InvestigationStarted("inv-7f3a", Instant.now(), "NORMAL", "Claude Code"));
        writer.stepRecorded(step(StepKind.LAUNCH, StepStatus.OK, "start_java_application OrdersApplication", 4200,
                "Project: spring-boot-debugging", "Main class: org.learningfromexperience.bootfixture.orders.OrdersApplication",
                "Launch: Agentic Debugger - spring-boot-debugging - OrdersApplication", "Server port: 53737"));
        // R1 will take the source lines from Eclipse; here they are scripted to show the intended look.
        writer.stepRecorded(step(StepKind.BREAKPOINT, StepStatus.OK, "set_breakpoint OrderService.java:24", 35,
                "File: src/main/java/org/learningfromexperience/bootfixture/orders/OrderService.java",
                "Owner: agent   Condition: none",
                "",
                "  22 |     int percent = request.discountPercent();",
                "  23 |     BigDecimal subtotal = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));",
                "> 24 |     int rate = percent / 100;",
                "  25 |     BigDecimal discount = subtotal.multiply(BigDecimal.valueOf(rate));",
                "  26 |     return new Quote(subtotal.subtract(discount));"));
        writer.stepRecorded(step(StepKind.HTTP, StepStatus.OK, "start_fixture_http_request scenario=discount", 20,
                "POST /orders/quote {\"quantity\":2,\"unitPrice\":60,\"discountPercent\":10}", "Request id: r1 (pending)"));
        writer.stepRecorded(new ReportEvent.StepRecorded("inv-7f3a", Instant.now(), StepKind.STOP, StepStatus.OK,
                "wait_for_stop: BREAKPOINT at OrderService.quote:24", List.of(
                        "Thread: http-nio-exec-1 (suspended)",
                        "Frames: OrderService.quote:24  <  OrderController.quote:31  <  (58 more)",
                        "",
                        "Variable    Type          Value",
                        "--------    ----------    ------",
                        "percent     int           10",
                        "rate        int           0",
                        "subtotal    BigDecimal    120.00",
                        "",
                        "Generic check that HTML is escaped: List<String> & \"quotes\" & café"),
                List.of(shot), 640));
        writer.stepRecorded(step(StepKind.STEP, StepStatus.OK, "control_debug_session RESUME thread 3", 12));
        writer.stepRecorded(step(StepKind.HTTP, StepStatus.OK, "get_fixture_http_request r1", 8,
                "HTTP 200", "Body: {\"total\":120.00}", "Expected 108.00"));
        writer.stepRecorded(step(StepKind.INSPECT, StepStatus.FAILED, "evaluate_expression percent / 100.0", 15,
                "Rejected: evaluation is disabled for NORMAL investigations"));
        writer.investigationFinished(new ReportEvent.InvestigationFinished("inv-7f3a", Instant.now(), ReportEvent.Outcome.FINISHED,
                "In OrderService.quote, percent / 100 is integer division, so rate = 0 and the 10% discount is lost: "
                        + "HTTP 200 total 120.00 instead of 108.00.",
                List.of("Removed breakpoint OrderService.java:24 (owner=agent)",
                        "Terminated launch OrdersApplication; JVM exit confirmed")));
        writer.close();

        Path report = dir.resolve("index.html");
        assertTrue(Files.isRegularFile(report), "report missing: " + report.toAbsolutePath());
        assertTrue(Files.isRegularFile(dir.resolve("resources/chaintest-pkg.css")), "offline CSS missing");
        assertTrue(Files.isRegularFile(dir.resolve("resources/chaintest-pkg.js")), "offline script missing");
        try (Stream<Path> files = Files.list(dir.resolve("resources"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".png")), "screenshot was not embedded");
        }
        String html = Files.readString(report, StandardCharsets.ISO_8859_1);
        assertTrue(html.contains("BREAKPOINT - set_breakpoint OrderService.java:24"), "breakpoint step missing");
        assertTrue(html.contains("rate        int           0"), "variable value missing");
        assertTrue(html.contains("&gt; 24 |     int rate = percent / 100;"), "source line missing");
        assertTrue(html.contains("Investigation inv-7f3a - OrdersApplication"), "investigation not named after its launch");
        assertTrue(html.contains("data-bs-theme=\"dark\""), "dark theme not set");
        assertTrue(html.contains("<title>Agentic Debugger - Debugging Session Report</title>"), "page title missing");
        assertTrue(html.contains("Debugging Session Report  |"), "header missing");
        for (String tag : List.of("BREAKPOINT", "STOP", "HTTP", "CONCLUSION", "CLEANUP", "Claude Code", "finished"))
            assertTrue(html.contains(">" + tag + "<"), "tag badge missing: " + tag);
        assertTrue(html.contains("CONCLUSION - diagnosis by the agent")
                && html.contains("percent / 100 is integer division"), "conclusion missing");
        assertTrue(html.contains("CLEANUP - finish_debug_investigation"), "cleanup step missing");
        assertTrue(html.contains("test-container passed"), "a recovered tool error must not fail the investigation");
        assertTrue(html.contains("1 tool call failed; the agent continued."), "failed call count missing");
        assertTrue(html.contains("List&lt;String&gt; &amp; &quot;quotes&quot; &amp; caf&#233;"), "text not escaped");
        assertFalse(html.contains("List<String>"), "unescaped text in report");
        assertFalse(html.contains("cdn.jsdelivr.net"), "report is not offline");
    }

    @Test
    @DisplayName("RC-U2 an investigation that ended without finish is shown as SKIPPED with the reason")
    void rcU2() throws Exception {
        Path dir = fresh("abandoned");
        ChainTestReportWriter writer = new ChainTestReportWriter(new ReportSettings("chaintest", dir, "Agentic Debugger prototype"));
        writer.investigationStarted(new ReportEvent.InvestigationStarted("inv-idle", Instant.now(), "NORMAL", "Claude Code"));
        writer.stepRecorded(new ReportEvent.StepRecorded("inv-idle", Instant.now(), StepKind.BREAKPOINT, StepStatus.OK,
                "set_breakpoint Calculator.java:10", List.of(), List.of(), 20));
        writer.investigationFinished(new ReportEvent.InvestigationFinished("inv-idle", Instant.now(),
                ReportEvent.Outcome.ENDED_WITHOUT_FINISH, null, List.of("Idle lease expired after 10 min", "Removed breakpoint Calculator.java:10")));
        writer.close();
        String html = Files.readString(dir.resolve("index.html"), StandardCharsets.ISO_8859_1);
        assertTrue(html.contains("Ended without finish_debug_investigation"), "reason missing");
        assertTrue(html.toLowerCase().contains("skipped"), "not shown as skipped");
    }

    @Test
    @DisplayName("RC-U3 the real path: the writer is found by name through ServiceLoader and writes the report on close")
    void rcU3() throws Exception {
        Path dir = fresh("service-loader");
        ReportRecorder recorder = ReportRecorders.create(new ReportSettings("chaintest", dir, "Agentic Debugger prototype"), err);
        assertTrue(recorder.active(), () -> "chaintest writer not found: " + errBytes.toString(StandardCharsets.UTF_8));
        recorder.record(new ReportEvent.InvestigationStarted("inv-sl", Instant.now(), "NORMAL", "test"));
        recorder.record(new ReportEvent.StepRecorded("inv-sl", Instant.now(), StepKind.STOP, StepStatus.OK,
                "wait_for_stop: BREAKPOINT", List.of("x = 1"), List.of(), 5));
        recorder.record(new ReportEvent.InvestigationFinished("inv-sl", Instant.now(), ReportEvent.Outcome.FINISHED, "ok", List.of()));
        recorder.close(Duration.ofSeconds(30));
        assertTrue(Files.isRegularFile(dir.resolve("index.html")), "report not written through the recorder");
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8), "unexpected warnings");
    }

    @Test
    @DisplayName("RC-U5 a failed step shows its error once, and step kinds are not copied onto the investigation")
    void rcU5() throws Exception {
        Path dir = fresh("failed-step");
        ChainTestReportWriter writer = new ChainTestReportWriter(new ReportSettings("chaintest", dir, "Agentic Debugger prototype"));
        writer.investigationStarted(new ReportEvent.InvestigationStarted("0d4fe8bf-c038-4799-8476-4ddfeda72efe", Instant.now(), "NORMAL", "claude-code"));
        writer.stepRecorded(new ReportEvent.StepRecorded("0d4fe8bf-c038-4799-8476-4ddfeda72efe", Instant.now(), StepKind.HTTP,
                StepStatus.FAILED, "start_fixture_http_request scenario=discount",
                List.of("scenario: discount", "", "Error: Orders fixture not ready"), List.of(), 61));
        writer.investigationFinished(new ReportEvent.InvestigationFinished("0d4fe8bf-c038-4799-8476-4ddfeda72efe", Instant.now(),
                ReportEvent.Outcome.FINISHED, null, List.of("Action: CLEANUP", "JVM ended: false")));
        writer.close();
        String html = Files.readString(dir.resolve("index.html"), StandardCharsets.ISO_8859_1);
        assertEquals(1, html.split("Orders fixture not ready", -1).length - 1, "error shown more than once");
        assertTrue(html.contains("Investigation 0d4fe8bf<"), "long ID not shortened in the name: " + html.contains("Investigation 0d4fe8bf"));
        assertTrue(html.contains("ID: 0d4fe8bf-c038-4799-8476-4ddfeda72efe"), "full ID missing from the description");
        assertTrue(html.contains("test-container failed"), "a cleanup problem must fail the investigation");
        // The investigation's own column runs from its container to its description (the tag table at the top is excluded).
        int start = html.indexOf("test-container");
        int end = html.indexOf("<code class=\"mt-2\">", start);
        assertFalse(html.substring(start, end).contains(">HTTP<"), "step kind copied onto the investigation");
    }

    @Test
    @DisplayName("RC-U4 HTML escaping of report text")
    void rcU4() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot; &#39;d&#39; &#8364;", ChainTestReportWriter.html("a <b> & \"c\" 'd' €"));
        assertEquals("", ChainTestReportWriter.html(null));
    }
}
