package org.learningfromexperience.eclipse.agentdebug.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for {@link PerspectivePolicy} (PP-U1 to PP-U7, OPEN-35 and OPEN-36). No Eclipse needed: run by
 * {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class PerspectivePolicyTest {

    @Test @DisplayName("PP-U1 without a saved choice Eclipse switches to the Debug perspective (first, then Agent Activity opens)")
    void ppU1() {
        assertTrue(PerspectivePolicy.switchToDebugPerspective(null), "never set must mean: switch (OPEN-36)");
    }

    @Test @DisplayName("PP-U2 an explicitly saved choice is respected")
    void ppU2() {
        assertTrue(PerspectivePolicy.switchToDebugPerspective("true"), "explicit true must switch");
        assertFalse(PerspectivePolicy.switchToDebugPerspective("false"), "explicit false must keep");
    }

    @Test @DisplayName("PP-U3 Debug, Breakpoints, Expressions and Console are opened, Variables last and in front")
    void ppU3() {
        assertEquals(5, PerspectivePolicy.DEBUG_VIEWS.size());
        assertEquals("org.eclipse.debug.ui.DebugView", PerspectivePolicy.DEBUG_VIEWS.get(0),
                "Debug first: its selected frame feeds Variables");
        assertEquals(PerspectivePolicy.FRONT_VIEW, PerspectivePolicy.DEBUG_VIEWS.get(PerspectivePolicy.DEBUG_VIEWS.size() - 1),
                "Variables last, so it ends up in front");
        assertTrue(PerspectivePolicy.DEBUG_VIEWS.containsAll(java.util.List.of(
                "org.eclipse.debug.ui.BreakpointView", "org.eclipse.debug.ui.ExpressionView", "org.eclipse.ui.console.ConsoleView")));
    }

    @Test @DisplayName("PP-U4 JDK frames are recognised so the editor shows the first application frame instead")
    void ppU4() {
        assertTrue(PerspectivePolicy.jdkType("java.lang.ref.Reference"));
        assertTrue(PerspectivePolicy.jdkType("java.lang.Thread"));
        assertTrue(PerspectivePolicy.jdkType("jdk.internal.misc.Unsafe"));
        assertTrue(PerspectivePolicy.jdkType("sun.nio.ch.Net"));
        assertFalse(PerspectivePolicy.jdkType("org.learningfromexperience.debugfixtures.LongRunningApplication"));
        assertFalse(PerspectivePolicy.jdkType("javaapp.Main"), "only the JDK packages, not every name starting with java");
        assertFalse(PerspectivePolicy.jdkType(null));
    }

    @Test @DisplayName("PP-U5 Agent Activity has its own window unless turned off; it first opens at the right edge, from near the bottom to above the middle")
    void ppU5() {
        assertTrue(PerspectivePolicy.floatingActivityView(null), "never set: floating");
        assertTrue(PerspectivePolicy.floatingActivityView("true"));
        assertFalse(PerspectivePolicy.floatingActivityView("false"), "turned off: docked view next to the Console");
        // 1366 x 768 window: 478 wide (35%), 445 high (58%: from near the bottom to a little above the middle), at the right edge.
        assertEquals(java.util.Arrays.toString(new int[] {100 + 1366 - 478 - 8, 50 + 768 - 445 - 40, 478, 445}),
                java.util.Arrays.toString(PerspectivePolicy.floatingBounds(100, 50, 1366, 768)));
        int[] big = PerspectivePolicy.floatingBounds(0, 0, 2560, 1400);
        assertEquals(640, big[2], "not wider than 640");
        assertEquals(812, big[3], "58% of the height");
        int[] small = PerspectivePolicy.floatingBounds(0, 0, 300, 200);
        assertTrue(small[0] >= 0 && small[1] >= 0, "never left of or above the window");
        assertTrue(small[2] <= 300 && small[3] <= 200, "never bigger than the window");
    }

    @Test @DisplayName("PP-U6 the Agent Activity window reopens where the user left it, unless that place is off every screen")
    void ppU6() {
        assertEquals("10,20,500,450", PerspectivePolicy.formatBounds(10, 20, 500, 450));
        assertEquals(java.util.Arrays.toString(new int[] {10, 20, 500, 450}),
                java.util.Arrays.toString(PerspectivePolicy.parseBounds("10,20,500,450")));
        assertEquals(java.util.Arrays.toString(new int[] {-1900, 5, 600, 400}),
                java.util.Arrays.toString(PerspectivePolicy.parseBounds(" -1900, 5 ,600,400")), "a left second screen");
        assertEquals(null, PerspectivePolicy.parseBounds(null), "never saved");
        assertEquals(null, PerspectivePolicy.parseBounds("10,20,500"), "malformed");
        assertEquals(null, PerspectivePolicy.parseBounds("a,b,c,d"), "malformed");
        assertEquals(null, PerspectivePolicy.parseBounds("10,20,50,450"), "too small to use");

        java.util.List<int[]> one = java.util.List.<int[]>of(new int[] {0, 0, 1366, 728});
        assertTrue(PerspectivePolicy.titleOnScreen(new int[] {880, 280, 478, 445}, one), "the first place");
        assertTrue(PerspectivePolicy.titleOnScreen(new int[] {1200, 100, 478, 445}, one), "mostly off to the right, 166 px of the title still grabbable");
        assertFalse(PerspectivePolicy.titleOnScreen(new int[] {1330, 100, 478, 445}, one), "only 36 px of the title visible");
        assertFalse(PerspectivePolicy.titleOnScreen(new int[] {-1900, 5, 600, 400}, one), "the second screen is no longer connected");
        assertFalse(PerspectivePolicy.titleOnScreen(new int[] {100, 720, 478, 445}, one), "title below the usable area");
        java.util.List<int[]> two = java.util.List.<int[]>of(new int[] {0, 0, 1366, 728}, new int[] {-1920, 0, 1920, 1040});
        assertTrue(PerspectivePolicy.titleOnScreen(new int[] {-1900, 5, 600, 400}, two), "on the second screen");
        assertFalse(PerspectivePolicy.titleOnScreen(null, one));
    }

    @Test @DisplayName("PP-U7 terminal views are recognised by their ID, all of them, so the Claude terminal comes along to Debug")
    void ppU7() {
        assertTrue(PerspectivePolicy.terminalView("org.eclipse.terminal.view.ui.TerminalsView"), "Eclipse Platform Terminal");
        assertTrue(PerspectivePolicy.terminalView("org.eclipse.tm.terminal.view.ui.TerminalsView"), "CDT/TM Terminal");
        assertTrue(PerspectivePolicy.terminalView("org.eclipse.tcf.te.ui.terminals.TerminalsView"), "old TCF ID");
        assertTrue(PerspectivePolicy.terminalView("org.eclipse.tm.terminal.view.TerminalView"), "old TM view");
        assertFalse(PerspectivePolicy.terminalView("org.eclipse.ui.console.ConsoleView"), "the Console is not a terminal");
        assertFalse(PerspectivePolicy.terminalView("org.eclipse.debug.ui.VariableView"));
        assertFalse(PerspectivePolicy.terminalView(null));
    }
}
