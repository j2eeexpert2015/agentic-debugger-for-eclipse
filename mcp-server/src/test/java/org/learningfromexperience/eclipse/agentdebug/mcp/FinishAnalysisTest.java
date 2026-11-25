package org.learningfromexperience.eclipse.agentdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.learningfromexperience.agentdebug.report.ReportEvent;
import org.learningfromexperience.agentdebug.report.ReportRecorder;
import org.learningfromexperience.agentdebug.report.format.Json;

/**
 * Unit tests (JUnit 5) for {@link FinishAnalysis}, the agent's structured findings at the finish with the root cause's
 * source lines read from the project (FA-U1 to FA-U4, OPEN-30 R4 phase 2). Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class FinishAnalysisTest {

    @TempDir
    Path project;

    private static final List<String> SOURCE = List.of("package org.example;", "", "class OrderService {",
            "    BigDecimal quote(int quantity, BigDecimal unitPrice, int discountPercent) {",
            "        int percent = repository.findDiscount(discountPercent);",
            "        int rate = percent / 100;", "        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));",
            "        return subtotal.multiply(BigDecimal.valueOf(1 - rate));", "    }", "}");

    private Path write(String relative, List<String> lines) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> parsed(String json) {
        return Json.obj(Json.parse(json));
    }

    @Test @DisplayName("FA-U1 the agent's fields kept, the root cause's lines read from the project with context")
    void faU1() throws IOException {
        write("src/main/java/org/example/OrderService.java", SOURCE);
        write("target/classes/OrderService.java", List.of("stale copy"));
        String json = FinishAnalysis.json(Map.of(
                "title", "Discount lost by integer division",
                "trackedValue", Map.of("name", "rate", "expected", "0.10", "actual", "0"),
                "findings", List.of(Map.of("at", "OrderService.quote:24", "text", "rate is 0")),
                "rootCause", Map.of("file", "OrderService.java", "lines", "6", "explanation", "integer division"),
                "fix", "BigDecimal.valueOf(percent).movePointLeft(2)",
                "notObserved", List.of("line 6 was not stepped"),
                "assumedPath", "OrderController.quote -> OrderService.quote"), project);
        Map<String, Object> a = parsed(json);
        assertEquals("Discount lost by integer division", a.get("title"));
        assertEquals("0", Json.obj(a.get("trackedValue")).get("actual"));
        assertEquals("rate is 0", Json.obj(Json.arr(a.get("findings")).get(0)).get("text"));
        assertEquals(List.of("line 6 was not stepped"), a.get("notObserved"));
        Map<String, Object> source = Json.obj(a.get("source"));
        assertEquals("src/main/java/org/example/OrderService.java", source.get("path"), "not the copy under target");
        assertEquals("4", Json.str(source.get("from")), "two lines of context before");
        assertEquals("6", Json.str(source.get("highlightFrom")));
        List<Object> lines = Json.arr(source.get("lines"));
        assertEquals(5, lines.size(), "lines 4 to 8");
        assertEquals("        int rate = percent / 100;", lines.get(2));
    }

    @Test @DisplayName("FA-U2 arguments as JSON text, a path with folders, a range: all understood")
    void faU2() throws IOException {
        write("src/main/java/org/example/OrderService.java", SOURCE);
        Map<String, Object> a = parsed(FinishAnalysis.json(Map.of(
                "trackedValue", "{\"name\":\"rate\",\"actual\":\"0\"}",
                "findings", "[{\"at\":\"OrderService.quote:24\",\"text\":\"seen\"}]",
                "rootCause", Map.of("file", "org/example/OrderService.java", "lines", "5-6"),
                "notObserved", "only one scenario tried"), project));
        assertEquals("rate", Json.obj(a.get("trackedValue")).get("name"));
        assertEquals("seen", Json.obj(Json.arr(a.get("findings")).get(0)).get("text"));
        assertEquals(List.of("only one scenario tried"), a.get("notObserved"), "a single text taken as a list");
        Map<String, Object> source = Json.obj(a.get("source"));
        assertEquals("5", Json.str(source.get("highlightFrom")));
        assertEquals("6", Json.str(source.get("highlightTo")));
        assertEquals(new int[]{22, 25}[1], FinishAnalysis.range("22 to 25")[1]);
    }

    @Test @DisplayName("FA-U3 no source read outside the project, for a missing file, a line past the end, or no project")
    void faU3() throws IOException {
        write("src/OrderService.java", SOURCE);
        Files.writeString(project.getParent().resolve("secret-" + project.getFileName() + ".txt"), "token=abc");
        Map<String, Object> outside = parsed(FinishAnalysis.json(Map.of("rootCause", Map.of(
                "file", "../secret-" + project.getFileName() + ".txt", "lines", "1")), project));
        assertFalse(outside.containsKey("source"), "never outside the project");
        assertTrue(Json.str(outside.get("sourceNote")).contains("not found"), outside.toString());
        Map<String, Object> past = parsed(FinishAnalysis.json(Map.of("rootCause", Map.of(
                "file", "OrderService.java", "lines", "99")), project));
        assertTrue(Json.str(past.get("sourceNote")).contains("only 10 lines"), past.toString());
        Map<String, Object> noProject = parsed(FinishAnalysis.json(Map.of("rootCause", Map.of(
                "file", "OrderService.java", "lines", "6")), null));
        assertTrue(Json.str(noProject.get("sourceNote")).contains("launched no project"), noProject.toString());
        Map<String, Object> noLine = parsed(FinishAnalysis.json(Map.of("rootCause", Map.of(
                "file", "OrderService.java", "explanation", "somewhere")), project));
        assertTrue(Json.str(noLine.get("sourceNote")).contains("no line number"), noLine.toString());
    }

    @Test @DisplayName("FA-U4 nothing given: no analysis; given at the finish: sent with the finish event, long text cut")
    void faU4() {
        assertNull(FinishAnalysis.json(Map.of("investigationId", "inv-1", "summary", "## Done"), project));
        List<ReportEvent> events = new ArrayList<>();
        ReportRecorder recorder = new ReportRecorder() {
            @Override public void record(ReportEvent event) { events.add(event); }
            @Override public void close(Duration timeout) { }
            @Override public boolean active() { return true; }
        };
        SessionReport session = new SessionReport(recorder, null, null,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        session.toolCalled("claude-code", "begin_debug_investigation", Map.of(), false,
                "{\"status\":\"success\",\"investigationId\":\"inv-1\",\"mode\":\"NORMAL\"}", 5);
        session.toolCalled("claude-code", "finish_debug_investigation", Map.of("investigationId", "inv-1",
                "title", "x".repeat(500)), false, "{\"status\":\"success\",\"action\":\"CLEANUP\"}", 3);
        var finished = (ReportEvent.InvestigationFinished) events.get(events.size() - 1);
        String title = Json.str(parsed(finished.analysis()).get("title"));
        assertEquals(201, title.length(), "cut to 200 characters and an ellipsis");
    }
}
