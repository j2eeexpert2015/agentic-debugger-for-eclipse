package org.learningfromexperience.eclipse.agentdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * JUnit 5 unit tests for the MCP tool annotations, OPEN-58 (TS-U1 to TS-U4). Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ToolSafetyTest {

    /** Sources are read relative to the module directory, which is surefire's working directory. */
    private static final Path SOURCES = Path.of("src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp");

    @Test @DisplayName("TS-U1 a tool is in at most one list")
    void tsU1() {
        Set<String> seen = new HashSet<>();
        for (Set<String> list : List.of(ToolSafety.READ_ONLY, ToolSafety.RUNS_CODE, ToolSafety.CHANGES_DEBUG_STATE))
            for (String name : list) assertTrue(seen.add(name), name + " is in two lists");
    }

    @Test @DisplayName("TS-U2 evaluation and tracepoints are marked as running code, reads as read-only")
    void tsU2() {
        for (String name : List.of("evaluate_expression", "set_tracepoint")) {
            ToolAnnotations a = ToolSafety.annotationsFor(name);
            assertEquals(Boolean.FALSE, a.readOnlyHint(), name);
            assertEquals(Boolean.TRUE, a.destructiveHint(), name);
        }
        for (String name : List.of("get_frame_variables", "list_breakpoints", "wait_for_stop", "eclipse_ping")) {
            ToolAnnotations a = ToolSafety.annotationsFor(name);
            assertEquals(Boolean.TRUE, a.readOnlyHint(), name);
            assertEquals(Boolean.FALSE, a.destructiveHint(), name);
        }
        ToolAnnotations breakpoint = ToolSafety.annotationsFor("set_breakpoint");
        assertEquals(Boolean.FALSE, breakpoint.readOnlyHint());
        assertEquals(Boolean.FALSE, breakpoint.destructiveHint());
        assertNull(ToolSafety.annotationsFor("some_new_tool"), "an unknown tool gets no annotations");
    }

    @Test @DisplayName("TS-U3 annotate keeps order and handlers, adds annotations, leaves unknown tools alone")
    void tsU3() {
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> null;
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
        List<SyncToolSpecification> tools = List.of(
                new SyncToolSpecification(Tool.builder("evaluate_expression", schema).description("d").build(), handler),
                new SyncToolSpecification(Tool.builder("some_new_tool", schema).build(), handler),
                new SyncToolSpecification(Tool.builder("list_breakpoints", schema).build(), handler));
        List<SyncToolSpecification> annotated = ToolSafety.annotate(tools);
        assertEquals(3, annotated.size());
        assertEquals("evaluate_expression", annotated.get(0).tool().name());
        assertEquals("d", annotated.get(0).tool().description(), "description kept");
        assertSame(handler, annotated.get(0).callHandler(), "handler kept");
        assertEquals(Boolean.TRUE, annotated.get(0).tool().annotations().destructiveHint());
        assertSame(tools.get(1), annotated.get(1), "an unknown tool is passed on unchanged");
        assertNotNull(annotated.get(2).tool().annotations());
        assertEquals(Boolean.TRUE, annotated.get(2).tool().annotations().readOnlyHint());
    }

    @Test @DisplayName("TS-U4 every tool the server registers is classified")
    void tsU4() throws IOException {
        Set<String> registered = new TreeSet<>();
        Pattern name = Pattern.compile("(?:Tool\\.builder|bridgeTool)\\(\\s*\"([a-z_]+)\"");
        for (String file : List.of("DebugMcpServer.java", "FixtureHttpTools.java")) {
            Matcher m = name.matcher(Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8));
            while (m.find()) registered.add(m.group(1));
        }
        assertTrue(registered.size() >= 30, "found the tool registrations: " + registered);
        for (String tool : registered)
            assertNotNull(ToolSafety.annotationsFor(tool),
                    "Unclassified tool " + tool + ": add it to ToolSafety READ_ONLY, RUNS_CODE or CHANGES_DEBUG_STATE");
    }
}
