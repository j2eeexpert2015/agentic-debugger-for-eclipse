package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * CORE-17 unit checks TR-U1..TR-U5 for the MCP session's open-investigation tracking. Plain Java,
 * kept outside Maven's test folder; run through {@code java dev-e2e/BridgeUnitTests.java}.
 *
 * @author Ayan Dutta
 */
public final class InvestigationTrackerTest {

    private static int passed;

    public static void main(String[] args) {
        check("TR-U1 a successful begin opens the investigation; a failed begin does not", () -> {
            var t = new InvestigationTracker();
            t.observe("BEGIN_INVESTIGATION " + enc("NORMAL"), "{\"status\":\"success\",\"investigationId\":\"inv-1\",\"mode\":\"NORMAL\"}");
            t.observe("BEGIN_INVESTIGATION " + enc("NORMAL"), "{\"status\":\"error\",\"message\":\"x\"}");
            return t.openIds().equals(List.of("inv-1"));
        });
        check("TR-U2 without an ID, a request is attached to the only open investigation", () -> {
            var t = open("inv-1");
            return "inv-1".equals(t.resolve(null)) && "given".equals(t.resolve("given"));
        });
        check("TR-U3 with several open investigations, a request without an ID is rejected", () -> {
            var t = open("inv-1", "inv-2");
            try {
                t.resolve(null);
                return false;
            } catch (IllegalArgumentException expected) {
                return expected.getMessage().contains("pass investigationId") && "inv-2".equals(t.resolve("inv-2"));
            }
        });
        check("TR-U4 no open investigation: no ID is invented", () -> new InvestigationTracker().resolve(null) == null);
        check("TR-U5 finish closes it (also when Eclipse says expired or unknown); a failed finish keeps it", () -> {
            var t = open("a", "b", "c", "d");
            t.observe("FINISH_INVESTIGATION " + enc("a"), "{\"status\":\"success\",\"completed\":true}");
            t.observe("FINISH_INVESTIGATION " + enc("b"), "{\"status\":\"error\",\"message\":\"IllegalStateException: Investigation b expired after 600 s\"}");
            t.observe("FINISH_INVESTIGATION " + enc("c"), "{\"status\":\"error\",\"message\":\"IllegalArgumentException: Unknown investigationId: c\"}");
            t.observe("FINISH_INVESTIGATION " + enc("d"), "{\"status\":\"error\",\"message\":\"CONTROLLED investigation is not suspended\"}");
            return t.openIds().equals(List.of("d"));
        });
        System.out.println("PASS: " + passed + " MCP investigation tracking checks");
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

    private static void check(String name, java.util.function.BooleanSupplier test) {
        if (!test.getAsBoolean()) throw new AssertionError("FAIL " + name);
        passed++;
        System.out.println("PASS " + name);
    }
}
