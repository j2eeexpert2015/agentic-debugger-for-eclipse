package org.learningfromexperience.eclipse.agentdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for the MCP session's open-investigation tracking, CORE-17 (TR-U1 to TR-U5).
 * Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class InvestigationTrackerTest {

    @Test @DisplayName("TR-U1 a successful begin opens the investigation; a failed begin does not")
    void trU1() {
        var t = new InvestigationTracker();
        t.observe("BEGIN_INVESTIGATION " + enc("NORMAL"), "{\"status\":\"success\",\"investigationId\":\"inv-1\",\"mode\":\"NORMAL\"}");
        t.observe("BEGIN_INVESTIGATION " + enc("NORMAL"), "{\"status\":\"error\",\"message\":\"x\"}");
        assertEquals(List.of("inv-1"), t.openIds());
    }

    @Test @DisplayName("TR-U2 without an ID, a request is attached to the only open investigation")
    void trU2() {
        var t = open("inv-1");
        assertEquals("inv-1", t.resolve(null));
        assertEquals("given", t.resolve("given"));
    }

    @Test @DisplayName("TR-U3 with several open investigations, a request without an ID is rejected")
    void trU3() {
        var t = open("inv-1", "inv-2");
        var e = assertThrows(IllegalArgumentException.class, () -> t.resolve(null));
        assertTrue(e.getMessage().contains("pass investigationId"));
        assertEquals("inv-2", t.resolve("inv-2"));
    }

    @Test @DisplayName("TR-U4 no open investigation: no ID is invented")
    void trU4() {
        assertNull(new InvestigationTracker().resolve(null));
    }

    @Test @DisplayName("TR-U5 finish closes it (also when Eclipse says expired or unknown); a failed finish keeps it")
    void trU5() {
        var t = open("a", "b", "c", "d");
        t.observe("FINISH_INVESTIGATION " + enc("a"), "{\"status\":\"success\",\"completed\":true}");
        t.observe("FINISH_INVESTIGATION " + enc("b"), "{\"status\":\"error\",\"message\":\"IllegalStateException: Investigation b expired after 600 s\"}");
        t.observe("FINISH_INVESTIGATION " + enc("c"), "{\"status\":\"error\",\"message\":\"IllegalArgumentException: Unknown investigationId: c\"}");
        t.observe("FINISH_INVESTIGATION " + enc("d"), "{\"status\":\"error\",\"message\":\"CONTROLLED investigation is not suspended\"}");
        assertEquals(List.of("d"), t.openIds());
    }

    private static InvestigationTracker open(String... ids) {
        var t = new InvestigationTracker();
        for (String id : ids)
            t.observe("BEGIN_INVESTIGATION " + enc("NORMAL"), "{\"status\":\"success\",\"investigationId\":\"" + id + "\"}");
        return t;
    }

    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
