package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for Normal and Controlled investigation ownership in {@link DebugInvestigationPolicy}.
 * No Eclipse needed; run by {@code mvn clean package}. Lease expiry is covered by {@link InvestigationLeaseTest}.
 *
 * @author Ayan Dutta
 */
class DebugInvestigationPolicyTest {

    private final DebugInvestigationPolicy registry = new DebugInvestigationPolicy();

    @Test
    @DisplayName("PO-U1 no mode gives NORMAL, an explicit CONTROLLED is kept")
    void poU1() {
        assertEquals(DebugInvestigationPolicy.Mode.NORMAL, registry.begin(null).mode());
        assertEquals(DebugInvestigationPolicy.Mode.CONTROLLED, registry.begin("CONTROLLED").mode());
    }

    @Test
    @DisplayName("PO-U2 breakpoints and exception breakpoints are owned per investigation")
    void poU2() {
        var normal = registry.begin(null);
        var controlled = registry.begin("CONTROLLED");
        normal.recordBreakpoint("example.Calculator", 10);
        normal.recordException("java.lang.IllegalStateException");
        assertTrue(normal.exceptions().contains("java.lang.IllegalStateException"), "owned exception tracked");
        assertTrue(controlled.exceptions().isEmpty(), "exception ownership isolated");
        assertEquals(1, normal.breakpoints().size(), "own breakpoint tracked");
        assertTrue(controlled.breakpoints().isEmpty(), "breakpoint ownership isolated");
    }

    @Test
    @DisplayName("PO-U3 a reused launch is not owned, and a same-name replacement is rejected")
    void poU3() {
        var controlled = registry.begin("CONTROLLED");
        Object reused = new Object();
        controlled.recordLaunch("Pricing", reused, false);
        controlled.verifySession(reused);
        // A launch configuration name can outlive the JVM.
        assertThrows(IllegalStateException.class, () -> controlled.verifySession(new Object()));
        // Fail before Eclipse creates a new launch under a stale ID.
        assertThrows(IllegalStateException.class, () -> controlled.verifyNewLaunch("Pricing"));
        assertNull(controlled.ownedLaunch(), "reused launch is not owned");
    }

    @Test
    @DisplayName("PO-U4 an owned restart moves ownership to the new launch, the old one cannot restart again")
    void poU4() {
        var normal = registry.begin(null);
        Object launch1 = new Object();
        normal.recordLaunch("Calculator", launch1, true);
        assertSame(launch1, normal.ownedLaunch());
        Object launch2 = new Object();
        normal.replaceOwnedLaunch("Calculator", launch1, launch2);
        assertSame(launch2, normal.ownedLaunch());
        assertThrows(IllegalStateException.class,
                () -> normal.replaceOwnedLaunch("Calculator", launch1, new Object()));
    }

    @Test
    @DisplayName("PO-U5 a running investigation cannot switch to another application")
    void poU5() {
        var normal = registry.begin(null);
        normal.recordLaunch("Calculator", new Object(), true);
        assertThrows(IllegalStateException.class, () -> normal.verifyLaunchTarget("Different"));
    }

    @Test
    @DisplayName("PO-U6 a finished ID is rejected, other investigations stay intact")
    void poU6() {
        var normal = registry.begin(null);
        var controlled = registry.begin("CONTROLLED");
        registry.finish(normal.id());
        assertThrows(IllegalArgumentException.class, () -> registry.require(normal.id()));
        assertSame(controlled, registry.require(controlled.id()));
        registry.finish(controlled.id());
    }
}
