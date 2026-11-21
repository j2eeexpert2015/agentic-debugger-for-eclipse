package org.learningfromexperience.eclipse.agentdebug.debug;

/**
 * Dependency-free checks for Normal and Controlled investigation ownership.
 *
 * @author Ayan Dutta
 */
public final class DebugInvestigationPolicyTest {
    public static void main(String[] args) {
        DebugInvestigationPolicy registry = new DebugInvestigationPolicy();
        var normal = registry.begin(null);
        require(normal.mode() == DebugInvestigationPolicy.Mode.NORMAL, "default NORMAL");
        var controlled = registry.begin("CONTROLLED");
        require(controlled.mode() == DebugInvestigationPolicy.Mode.CONTROLLED, "explicit CONTROLLED");
        var launch1 = new Object();
        normal.recordBreakpoint("example.Calculator", 10);
        normal.recordLaunch("Calculator", launch1, true);
        controlled.recordLaunch("Pricing", new Object(), false);
        require(normal.breakpoints().size() == 1, "tracked own breakpoint");
        require(controlled.breakpoints().isEmpty(), "separate breakpoint ownership");
        require(normal.ownedLaunch() == launch1, "owned launch recorded");
        require(controlled.ownedLaunch() == null, "reused launch not owned");
        try {
            normal.verifyLaunchTarget("Different");
            throw new AssertionError("incorrect launch not rejected");
        } catch (IllegalStateException expected) {
            // A running investigation cannot silently switch to another application.
        }
        registry.finish(normal.id());
        try {
            registry.require(normal.id());
            throw new AssertionError("completed ID accepted");
        } catch (IllegalArgumentException expected) {
            // A completed ID cannot be used for subsequent debugging operations.
        }
        require(registry.require(controlled.id()) == controlled, "other investigation intact");
        registry.finish(controlled.id());
        System.out.println("DebugInvestigationPolicy tests: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
