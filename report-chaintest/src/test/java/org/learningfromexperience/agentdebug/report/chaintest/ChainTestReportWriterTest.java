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
 * <p>The reports are kept for viewing: open the {@code .html} files in
 * {@code report-chaintest/target/report-prototype/boot-quote/} (and {@code .../abandoned/}) in a browser after the build.
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

        writer.investigationStarted(new ReportEvent.InvestigationStarted("inv-7f3a", Instant.now(), "NORMAL", "Claude Code",
                "The order quote total is wrong: 2 items at 60 with a 10% discount should cost 108.00, but the API returns 120.00.\n"
                        + "Debug the running Spring Boot app in Eclipse and find the cause.", true));
        writer.stepRecorded(step(StepKind.LAUNCH, StepStatus.OK, "Started application in debug mode: OrdersApplication", 4200,
                "Project: spring-boot-debugging", "Main class: org.learningfromexperience.bootfixture.orders.OrdersApplication",
                "Launch: Agentic Debugger - spring-boot-debugging - OrdersApplication", "Server port: 53737"));
        // R1 will take the source lines from Eclipse; here they are scripted to show the intended look.
        writer.stepRecorded(step(StepKind.BREAKPOINT, StepStatus.OK, "Set breakpoint: OrderService, line 24", 35,
                "File: src/main/java/org/learningfromexperience/bootfixture/orders/OrderService.java",
                "Owner: agent   Condition: none",
                "",
                "  22 |     int percent = request.discountPercent();",
                "  23 |     BigDecimal subtotal = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));",
                "> 24 |     int rate = percent / 100;",
                "  25 |     BigDecimal discount = subtotal.multiply(BigDecimal.valueOf(rate));",
                "  26 |     return new Quote(subtotal.subtract(discount));",
                ReportEvent.TECHNICAL_DETAILS,
                "Tool: set_breakpoint", "lineNumber: 24", "Result: created=true, owner=agent"));
        writer.stepRecorded(step(StepKind.HTTP, StepStatus.OK, "Sent HTTP request: POST /orders/quote (discount scenario)", 20,
                "POST /orders/quote {\"quantity\":2,\"unitPrice\":60,\"discountPercent\":10}", "Request id: r1 (pending)"));
        writer.stepRecorded(new ReportEvent.StepRecorded("inv-7f3a", Instant.now(), StepKind.STOP, StepStatus.OK,
                "Stopped at breakpoint: OrderService.quote, line 24", List.of(
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
        writer.stepRecorded(step(StepKind.STEP, StepStatus.OK, "Resumed execution", 12));
        writer.stepRecorded(step(StepKind.HTTP, StepStatus.OK, "Received HTTP response: 200", 8,
                "HTTP 200", "Body: {\"total\":120.00}", "Expected 108.00"));
        writer.stepRecorded(step(StepKind.INSPECT, StepStatus.FAILED, "Evaluated expression: percent / 100.0", 15,
                "Rejected: evaluation is disabled for NORMAL investigations"));
        writer.investigationFinished(new ReportEvent.InvestigationFinished("inv-7f3a", Instant.now(), ReportEvent.Outcome.FINISHED,
                "In OrderService.quote, percent / 100 is integer division, so rate = 0 and the 10% discount is lost: "
                        + "HTTP 200 total 120.00 instead of 108.00.",
                List.of("Removed breakpoint OrderService.java:24 (owner=agent)",
                        "Terminated launch OrdersApplication; JVM exit confirmed")));
        writer.close();

        assertEquals(1, writer.writtenReports().size(), "one report per investigation");
        Path report = writer.writtenReports().get(0);
        assertTrue(report.getFileName().toString().matches("OrdersApplication_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.html"),
                "report not named after the application, date and time: " + report.getFileName());
        assertEquals(dir.toAbsolutePath().normalize(), report.getParent(), "report not in the configured folder");
        assertTrue(Files.isRegularFile(report), "report missing: " + report.toAbsolutePath());
        assertTrue(Files.isRegularFile(dir.resolve("resources/chaintest-pkg.css")), "offline CSS missing");
        assertTrue(Files.isRegularFile(dir.resolve("resources/chaintest-pkg.js")), "offline script missing");
        try (Stream<Path> files = Files.list(dir.resolve("resources"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".png")), "screenshot was not embedded");
        }
        String html = Files.readString(report, StandardCharsets.ISO_8859_1);
        assertTrue(html.contains("Set breakpoint: OrderService, line 24"), "breakpoint step missing");
        assertTrue(html.contains("Request from the user") && html.contains("Exact prompt sent to the agent:")
                && html.contains("should cost 108.00"), "request card missing");
        assertTrue(html.indexOf("Request from the user") < html.indexOf("Conclusion by the agent")
                && html.indexOf("Conclusion by the agent") < html.indexOf("Set breakpoint: OrderService"),
                "order must be: request, conclusion, then the steps");
        assertTrue(html.contains(ChainTestReportWriter.REQUEST_MARK) && html.contains(ChainTestReportWriter.CONCLUSION_MARK),
                "highlight markers missing");
        assertTrue(html.contains("Took "), "duration missing from the investigation line");
        assertTrue(html.contains("Stopped at breakpoint: OrderService.quote, line 24"), "stop step missing");
        assertTrue(html.contains("<style>" + ChainTestReportWriter.CSS + "</style>"), "filter bar not hidden");
        assertTrue(html.contains("rate        int           0"), "variable value missing");
        assertTrue(html.contains("&gt; 24 |     int rate = percent / 100;"), "source line missing");
        assertTrue(html.contains("Debugging OrdersApplication</h6>"), "investigation not named after its launch");
        assertTrue(html.contains("Investigation inv-7f3a"), "investigation ID missing from the description");
        // Two parts with a heading each: request and analysis first, then what the agent did.
        int fromAgent = html.indexOf("From the agent<small>");
        int inEclipse = html.indexOf("What the agent did in Eclipse<small>");
        assertTrue(fromAgent > 0 && fromAgent < html.indexOf("Request from the user")
                && html.indexOf("Conclusion by the agent") < inEclipse
                && inEclipse < html.indexOf("Set breakpoint: OrderService"), "section headings missing or in the wrong place");
        assertTrue(html.contains("data-bs-theme=\"\""), "light theme not set");
        assertTrue(html.contains("<title>Debugging OrdersApplication - debugging session report - "), "page title missing");
        // One self-contained file: stylesheet, script and screenshot are inside the HTML, not linked from resources/.
        assertFalse(html.contains("href=\"resources/chaintest-pkg.css\""), "stylesheet still linked");
        assertFalse(html.contains("src=\"resources/chaintest-pkg.js\""), "script still linked");
        assertFalse(html.contains("<img src=\"resources/"), "screenshot still linked");
        assertTrue(html.contains("<img src=\"data:image/png;base64,"), "screenshot not inlined");
        assertTrue(html.contains("<style id=\"style\">"), "stylesheet not inlined");
        assertTrue(html.contains("url(\"data:font/woff2;base64,"), "icon font not inlined: icons would show as empty boxes");
        // Steps carry their kind for the colours and numbers; the cleanup card too.
        assertTrue(html.contains("<span class=\"ad-k ad-k-breakpoint\"></span>Set breakpoint: OrderService, line 24"),
                "breakpoint step not marked with its kind");
        assertTrue(html.contains("<span class=\"ad-k ad-k-cleanup\"></span>"), "cleanup card not marked");
        // Tool, arguments and raw result are collapsed under "Technical details", after what a reader needs.
        int tech = html.indexOf("<details class=\"ad-tech\"><summary>Technical details</summary>");
        assertTrue(tech > html.indexOf("&gt; 24 |     int rate = percent / 100;")
                && html.indexOf("Tool: set_breakpoint") > tech
                && html.indexOf("</details>", tech) > html.indexOf("Result: created=true, owner=agent"),
                "technical details not collapsed after the reader's lines");
        assertFalse(html.contains(ReportEvent.TECHNICAL_DETAILS), "the separator line itself must not be shown");
        // Header: what was debugged, then what this page is, the product and the start time.
        assertTrue(html.contains("Debugging OrdersApplication<small class=\"ad-sub\">Debugging session report  &#183;  "),
                "header does not name what was debugged");
        for (String tag : List.of("BREAKPOINT", "STOP", "HTTP", "CONCLUSION", "CLEANUP", "Claude Code", "finished"))
            assertTrue(html.contains(">" + tag + "<"), "tag badge missing: " + tag);
        assertTrue(html.contains("Conclusion by the agent")
                && html.contains("percent / 100 is integer division"), "conclusion missing");
        assertTrue(html.contains("Finished investigation and cleaned up"), "cleanup step missing");
        assertTrue(html.contains("test-container passed"), "a recovered tool error must not fail the investigation");
        assertTrue(html.contains("1 tool call of the agent was refused and retried"), "refused call count missing");
        assertTrue(html.contains("this is not an error in the application"), "refused calls must not read as an application error");
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
                "Set breakpoint: Calculator, line 10", List.of(), List.of(), 20));
        writer.investigationFinished(new ReportEvent.InvestigationFinished("inv-idle", Instant.now(),
                ReportEvent.Outcome.ENDED_WITHOUT_FINISH, null, List.of("Idle lease expired after 10 min", "Removed breakpoint Calculator.java:10")));
        writer.close();
        Path report = writer.writtenReports().get(0);
        assertTrue(report.getFileName().toString().startsWith("Investigation_"), "no launch: file must start with Investigation_");
        String html = Files.readString(report, StandardCharsets.ISO_8859_1);
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
                "Stopped at breakpoint", List.of("x = 1"), List.of(), 5));
        recorder.record(new ReportEvent.InvestigationFinished("inv-sl", Instant.now(), ReportEvent.Outcome.FINISHED, "ok", List.of()));
        recorder.close(Duration.ofSeconds(30));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.filter(f -> f.toString().endsWith(".html")).count(), "report not written through the recorder");
        }
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
        Path chosen = dir.resolve("Chosen-by-recorder_2026-11-24_10-39-31.html");
        writer.investigationFinished(new ReportEvent.InvestigationFinished("0d4fe8bf-c038-4799-8476-4ddfeda72efe", Instant.now(),
                ReportEvent.Outcome.FINISHED, null, List.of("Action: CLEANUP", "JVM ended: false"), chosen));
        writer.close();
        assertTrue(Files.isRegularFile(chosen), "the file chosen by the recorder was not used");
        String html = Files.readString(chosen, StandardCharsets.ISO_8859_1);
        assertEquals(1, html.split("Orders fixture not ready", -1).length - 1, "error shown more than once");
        assertTrue(html.contains("Investigation 0d4fe8bf") && !html.contains("0d4fe8bf-c038"),
                "long ID not shortened in the description");
        assertTrue(html.contains(">Debugging session</h6>"), "an investigation without a launch is named \"Debugging session\"");
        assertTrue(html.contains("test-container failed"), "a cleanup problem must fail the investigation");
        // The investigation's own column runs from its container to its description (the tag table at the top is excluded).
        // Search for the element, not the bare class name: the stylesheet in <style> also mentions "test-container".
        int start = html.indexOf("<div class=\"test-container");
        int end = html.indexOf("<code class=\"mt-2\">", start);
        assertFalse(html.substring(start, end).contains(">HTTP<"), "step kind copied onto the investigation");
    }

    @Test
    @DisplayName("RC-U4 HTML escaping of report text, backticks shown as code")
    void rcU4() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot; &#39;d&#39; &#8364;", ChainTestReportWriter.html("a <b> & \"c\" 'd' €"));
        assertEquals("", ChainTestReportWriter.html(null));
        // Backticks in the agent's conclusion become code; text without a closing backtick stays as it is.
        assertEquals("line 23: <code>int rate = percent / 100</code> is integer division",
                ChainTestReportWriter.codeSpans(ChainTestReportWriter.html("line 23: `int rate = percent / 100` is integer division")));
        assertEquals("a ` b", ChainTestReportWriter.codeSpans("a ` b"));
    }

    @Test
    @DisplayName("RC-U6 the agent's Markdown analysis is rendered: headings, paragraphs, lists, tables, code, bold; text stays escaped")
    void rcU6() {
        String analysis = String.join("\n",
                "## Root cause",
                "Line 23 computes `int rate = percent / 100`, which is **integer division**.",
                "So the discount is never applied.",
                "",
                "- percent = 10",
                "- rate = 0",
                "",
                "1. Stop at line 24",
                "2. Read the locals",
                "",
                "| Variable | Value |",
                "|---|---|",
                "| `rate` | **0** |",
                "",
                "```java",
                "int rate = percent / 100; // <0>",
                "```",
                "<script>alert(1)</script>");
        String html = ChainTestReportWriter.markdown(analysis);
        assertEquals("<div class=\"ad-md\">"
                + "<div class=\"ad-h\">Root cause</div>"
                + "<p>Line 23 computes <code>int rate = percent / 100</code>, which is <strong>integer division</strong>. "
                + "So the discount is never applied.</p>"
                + "<ul><li>percent = 10</li><li>rate = 0</li></ul>"
                + "<ol><li>Stop at line 24</li><li>Read the locals</li></ol>"
                + "<table><tr><th>Variable</th><th>Value</th></tr><tr><td><code>rate</code></td><td><strong>0</strong></td></tr></table>"
                + "<code class=\"ad-block\">int rate = percent / 100; // &lt;0&gt;</code>"
                + "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
                + "</div>", html);
        assertEquals("<div class=\"ad-md\"><p>Plain text only.</p></div>", ChainTestReportWriter.markdown("Plain text only."));
        assertEquals("<div class=\"ad-md\"></div>", ChainTestReportWriter.markdown(null));
    }
}
