package org.learningfromexperience.eclipse.agentdebug.debug;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.eval.EvaluationManager;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;
import org.eclipse.jdt.debug.eval.IEvaluationResult;
import org.learningfromexperience.eclipse.agentdebug.ui.AgentDebugUiCoordinator;
import org.learningfromexperience.eclipse.agentdebug.ui.EvaluationConsentDialog;

/**
 * Exposes authenticated Eclipse Java debugging operations to the local MCP bridge.
 *
 * @author Ayan Dutta
 */
public final class EclipseAgentOperations {
    private final EclipseBreakpointService breakpoints = new EclipseBreakpointService();
    private final AgentDebugUiCoordinator ui = new AgentDebugUiCoordinator();
    /** OPEN-58: the user decides in Eclipse before an agent runs code in the debugged program (90 s to answer). */
    /** OPEN-58/OPEN-60: asks only when the user turned "Ask before the agent runs code" on (off by default). */
    private final EvaluationConsent consent = new EvaluationConsent(new EvaluationConsentDialog(), 90_000,
            EvaluationConsentDialog::askEnabled);
    private final DebugInvestigationPolicy investigations = new DebugInvestigationPolicy();
    /** Records real debugger stops for WAIT_FOR_STOP (event-based, no polling). */
    private final DebugStopTracker stops = new DebugStopTracker();
    /** Serializes finishing an investigation and expiring an abandoned one (CORE-17). */
    private final Object lifecycle = new Object();
    /** Checks every 2 s for investigations whose agent went away (idle lease ended). */
    private final java.util.concurrent.ScheduledExecutorService leaseChecker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AgentDebug-InvestigationLease");
                t.setDaemon(true);
                return t;
            });

    public EclipseAgentOperations() {
        leaseChecker.scheduleWithFixedDelay(this::expireAbandoned, 2, 2, TimeUnit.SECONDS);
        // After Eclipse starts, no investigation exists yet, so agent breakpoints still linked to one
        // are leftovers from before the restart (CORE-17).
        leaseChecker.schedule(this::removeOrphanedBreakpoints, 5, TimeUnit.SECONDS);
    }

    /** Cleanup path 4 (Eclipse restarted mid-investigation); overview in {@link DebugInvestigationPolicy}. */
    private void removeOrphanedBreakpoints() {
        try {
            synchronized (lifecycle) {
                List<String> removed = breakpoints.removeOrphanedAgentBreakpoints(investigations::isActive);
                if (removed.isEmpty()) return;
                restoreRecurrencePolicyIfUnused();
                Platform.getLog(EclipseAgentOperations.class).info("[Agentic Debugger] Removed " + removed.size()
                        + " agent breakpoint(s) left by investigations that no longer exist: " + removed);
            }
        } catch (Exception e) {
            Platform.getLog(EclipseAgentOperations.class).warn("[Agentic Debugger] Could not remove leftover agent breakpoints: " + e);
        }
    }

    /** Remove the UI and stop event listeners when the bridge shuts down. */
    public void close() {
        leaseChecker.shutdownNow();
        ui.close();
        stops.close();
        // Never leave the user's repeated-exception setting changed after Eclipse/the bridge stops.
        ExceptionRecurrencePolicy.restoreIfUnused(false);
    }

    public String execute(String command) {
        String[] p = command.split(" ", -1);
        // CORE-17: a command that names an investigation (ID or launch) keeps it alive while it runs.
        var named = investigations.namedBy(p);
        named.forEach(DebugInvestigationPolicy.Investigation::enter);
        try {
            return switch (p[0]) {
                case "GET_E2E_INFO" -> TEST_SUPPORT ? buildIdentity() : testOnly(p[0]);
                case "LIST_BREAKPOINTS" -> listBreakpoints();
                case "BEGIN_INVESTIGATION" -> beginInvestigation(p);
                case "FINISH_INVESTIGATION" -> finishInvestigation(p);
                case "SET_BREAKPOINT" -> setBreakpoint(p);
                case "SET_CONDITIONAL_BREAKPOINT" -> setConditionalBreakpoint(p, false);
                case "SET_TRACEPOINT" -> setConditionalBreakpoint(p, true);
                case "SET_EXCEPTION_BREAKPOINT" -> setExceptionBreakpoint(p);
                case "LIST_EXCEPTION_BREAKPOINTS" -> listExceptionBreakpoints();
                case "REMOVE_EXCEPTION_BREAKPOINT" -> removeExceptionBreakpoint(p);
                case "LIST_JUNIT_TESTS" -> listJUnitTests();
                case "START_JUNIT_TEST" -> startJUnitTest(p);
                case "GET_JUNIT_RESULTS" -> getJUnitResults(p);
                case "EXPAND_VARIABLE" -> expandVariable(p);
                case "RESTART_DEBUG" -> restartDebug(p);
                case "SET_STEP_FILTERS" -> setStepFilters(p);
                case "REMOVE_BREAKPOINT" -> removeBreakpoint(p);
                case "LIST_RUN_CONFIGURATIONS" -> listRunConfigurations();
                case "LIST_JAVA_APPLICATIONS" -> listJavaApplications();
                case "START_JAVA_APPLICATION" -> startJavaApplication(p);
                case "START_DEBUG" -> startDebug(p);
                case "GET_DEBUG_STATE" -> getDebugState(p);
                case "SHOW_DEBUG_LOCATION" -> showDebugLocation(p);
                case "GET_UI_STATUS" -> ok(",\"navigation\":" + q(ui.status()));
                case "GET_THREADS" -> getThreads(p);
                case "WAIT_FOR_STOP" -> waitForStop(p);
                case "GET_STACK" -> getStack(p);
                case "GET_VARIABLES" -> getVariables(p);
                case "EVALUATE_EXPRESSION" -> evaluateExpression(p);
                case "CONTROL_DEBUG" -> control(p);
                case "VERIFY_OWNED_FIXTURE_LAUNCH" -> TEST_SUPPORT ? verifyOwnedFixtureLaunch(p) : testOnly(p[0]);
                case "GET_PROCESS_OUTPUT" -> getProcessOutput(p);
                // OPEN-02/03: the user's own breakpoints and launches, created for the user-resource safety tests.
                case "TEST_USER_SEED" -> TEST_SUPPORT ? TestUserResources.execute(p) : testOnly(p[0]);
                case "TEST_USER_STATE" -> TEST_SUPPORT ? TestUserResources.execute(p) : testOnly(p[0]);
                case "TEST_USER_REPLACE_BREAKPOINT" -> TEST_SUPPORT ? TestUserResources.execute(p) : testOnly(p[0]);
                case "TEST_USER_LAUNCH_AGAIN" -> TEST_SUPPORT ? TestUserResources.execute(p) : testOnly(p[0]);
                case "TEST_TERMINATE_LAUNCH" -> TEST_SUPPORT ? TestUserResources.execute(p) : testOnly(p[0]);
                case "TEST_USER_CLEANUP" -> TEST_SUPPORT ? TestUserResources.execute(p) : testOnly(p[0]);
                // OPEN-34: break and repair the build of a fixture project for the build-check tests.
                case "TEST_WRITE_SOURCE" -> TEST_SUPPORT ? TestWorkspaceFiles.execute(p) : testOnly(p[0]);
                case "TEST_DELETE_SOURCE" -> TEST_SUPPORT ? TestWorkspaceFiles.execute(p) : testOnly(p[0]);
                case "TEST_DELETE_CLASS_FILE" -> TEST_SUPPORT ? TestWorkspaceFiles.execute(p) : testOnly(p[0]);
                case "TEST_BUILD_PROJECT" -> TEST_SUPPORT ? TestWorkspaceFiles.execute(p) : testOnly(p[0]);
                // OPEN-58: unattended tests preset the answer to the consent question (allow, deny, ask).
                case "TEST_SET_CONSENT" -> TEST_SUPPORT
                        ? ok(",\"consent\":" + q(consent.preset(require(p, 1)).name().toLowerCase(java.util.Locale.ROOT)))
                        : testOnly(p[0]);
                default -> error("Unknown command");
            };
        } catch (Exception e) {
            return error(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        } finally {
            named.forEach(DebugInvestigationPolicy.Investigation::exit);
        }
    }

    /**
     * Test support (OPEN-38 step 5): the commands only the test tools use ({@code GET_E2E_INFO},
     * {@code VERIFY_OWNED_FIXTURE_LAUNCH}, the {@code TEST_USER_*} commands of {@link TestUserResources}) answer only in an Eclipse started by {@code dev-e2e/DevE2E.java}, which
     * sets {@code AGENTDEBUG_E2E=1}; in a normal Eclipse they are refused. (The fixture HTTP tools of the MCP server
     * are switched on the same way, by {@code AGENTDEBUG_E2E_HTTP}, and the fixture projects by
     * {@code AGENTDEBUG_FIXTURE_SOURCE}.)
     */
    private static final boolean TEST_SUPPORT = "1".equals(System.getenv("AGENTDEBUG_E2E"));

    private static String testOnly(String command) {
        return error(command + " is a test command, available only in an Eclipse started by dev-e2e/DevE2E.java"
                + " (AGENTDEBUG_E2E=1)");
    }

    /** Identifies the exact loaded classes, not merely the installed feature label.
     * Read-only; for the authenticated local test runner only ({@link #TEST_SUPPORT}).
     */
    private static String buildIdentity() throws IOException {
        String bundleVersion = EclipseAgentOperations.class.getPackage().getImplementationVersion();
        // OSGi bundle metadata is authoritative; Package implementation version may be null.
        org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(EclipseAgentOperations.class);
        if (bundle != null) bundleVersion = bundle.getVersion().toString();
        var source = EclipseAgentOperations.class.getProtectionDomain().getCodeSource();
        String loadedFrom = source == null ? "unknown" : source.getLocation().toString();
        // Bundle location reliably distinguishes p2-installed JARs from PDE workspace bundles.
        if (bundle != null && bundle.getLocation() != null) loadedFrom = bundle.getLocation();
        return ok(",\"fixtureProtocol\":\"calculator-v2\",\"bundleVersion\":" + q(bundleVersion)
                + ",\"workspace\":" + q(ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString())
                + ",\"installDirectory\":" + q(org.eclipse.core.runtime.Platform.getInstallLocation().getURL().toExternalForm())
                + ",\"loadedFrom\":" + q(loadedFrom)
                + ",\"operationsSha256\":" + q(classSha256(EclipseAgentOperations.class))
                + ",\"breakpointSha256\":" + q(classSha256(EclipseBreakpointService.class)));
    }

    private static String classSha256(Class<?> type) throws IOException {
        try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (stream == null) throw new IOException("Cannot read loaded class: " + type.getName());
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 unavailable", e);
            }
        }
    }

    private static String decoded(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    public static String q(String value) {
        if (value == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
    private static String ok(String fields) { return "{\"status\":\"success\"" + fields + "}"; }
    private static String error(String message) { return "{\"status\":\"error\",\"message\":" + q(message) + "}"; }
    private static String require(String[] p, int index) {
        if (p.length <= index || p[index].isEmpty()) throw new IllegalArgumentException("Missing argument " + index);
        return decoded(p[index]);
    }
    private static int number(String[] p, int index) {
        if (p.length <= index) throw new IllegalArgumentException("Missing numeric argument " + index);
        return Integer.parseInt(p[index]);
    }

    /** Optional investigation IDs preserve compatibility with existing MCP tools. */
    private String optionalInvestigation(String[] parts, int index) {
        return parts.length > index && !parts[index].isBlank() ? require(parts, index) : null;
    }

    private String beginInvestigation(String[] p) {
        String mode = p.length > 1 ? require(p, 1) : "NORMAL";
        // Optional idle lease in seconds (default 10 min, AGENTDEBUG_INVESTIGATION_LEASE_MINUTES).
        long lease = p.length > 2 && !p[2].isBlank() ? number(p, 2) * 1000L : DebugInvestigationPolicy.DEFAULT_LEASE_MILLIS;
        var investigation = investigations.begin(mode, lease);
        return ok(",\"investigationId\":" + q(investigation.id())
                + ",\"mode\":" + q(investigation.mode().name())
                + ",\"leaseSeconds\":" + investigation.leaseMillis() / 1000);
    }

    /**
     * Cleanup path 3 (overview in {@link DebugInvestigationPolicy}).
     * CORE-17: finishes investigations whose agent stopped sending commands (crashed, cancelled,
     * disconnected) once their idle lease ended. NORMAL: the same cleanup as
     * finish_debug_investigation (own breakpoints removed, own JVM ended and verified). CONTROLLED:
     * session and breakpoints are kept for the user to inspect; only the investigation ends.
     * User-owned and borrowed sessions and breakpoints are never touched.
     */
    private void expireAbandoned() {
        for (var investigation : investigations.abandoned()) {
            try {
                synchronized (lifecycle) {
                    if (!investigation.abandoned()) continue;
                    long idle = investigation.idleMillis() / 1000;
                    String reason;
                    if (investigation.mode() == DebugInvestigationPolicy.Mode.NORMAL) {
                        Cleanup result = cleanUp(investigation);
                        reason = "Investigation " + investigation.id() + " expired after " + idle
                                + " s without calls (agent gone?); its own breakpoints were removed and its JVM "
                                + (result.ended() ? "ended" : "could NOT be ended") + " automatically";
                    } else {
                        reason = "Investigation " + investigation.id() + " expired after " + idle
                                + " s without calls; CONTROLLED, so its session and breakpoints were kept for inspection";
                    }
                    investigations.expire(investigation.id(), reason);
                    Platform.getLog(EclipseAgentOperations.class).info("[Agentic Debugger] " + reason);
                }
            } catch (Exception e) {
                // Never let one failure stop the checker; retried on the next round.
                Platform.getLog(EclipseAgentOperations.class).warn(
                        "[Agentic Debugger] Could not expire investigation " + investigation.id() + ": " + e);
            }
        }
    }

    private record Cleanup(String json, boolean ended) {}

    /**
     * NORMAL cleanup shared by paths 1 (finish) and 3 (expiry), see {@link DebugInvestigationPolicy}:
     * own breakpoints first, so nothing can suspend the JVM while it shuts down, then own JVM, verified.
     */
    private Cleanup cleanUp(DebugInvestigationPolicy.Investigation investigation) throws CoreException {
        String id = investigation.id();
        int removed = 0;
        for (var bp : investigation.breakpoints()) {
            if (breakpoints.removeInvestigationBreakpoint(bp.typeName(), bp.lineNumber(), id)) removed++;
        }
        for (var exception : investigation.exceptions()) {
            if (breakpoints.removeInvestigationException(exception, id)) removed++;
        }
        restoreRecurrencePolicyIfUnused();
        // OPEN-39: the reply confirms the cleanup, so the agent needs no list_breakpoints / list_debug_sessions call.
        int left = breakpoints.countInvestigationBreakpoints(id);
        String counts = ",\"breakpointsRemoved\":" + removed + ",\"ownBreakpointsLeft\":" + left;
        // OPEN-25: test runs without the debugger normally ended long ago; one still running is ended here.
        for (Object run : investigation.runs()) {
            if (run instanceof ILaunch launch && !launch.isTerminated()) {
                try { launch.terminate(); } catch (Exception ignored) { /* reported by the run's own state */ }
            }
        }
        if (investigation.ownedLaunch() instanceof ILaunch launch) {
            List<String> steps = new ArrayList<>();
            boolean ended = terminateAndVerify(launch, steps);
            return new Cleanup(counts + ",\"processesEnded\":" + ended + ",\"terminationSteps\":["
                    + String.join(",", steps.stream().map(EclipseAgentOperations::q).toList()) + "]"
                    + cleanupNote(left, ended, true), ended);
        }
        return new Cleanup(counts + cleanupNote(left, true, false), true);
    }

    private static String cleanupNote(int ownBreakpointsLeft, boolean ended, boolean ownLaunch) {
        String note = DebugInvestigationPolicy.cleanupNote(ownBreakpointsLeft, ended, ownLaunch);
        return note == null ? "" : ",\"cleanup\":" + q(note);
    }

    /** Cleanup path 1: finalizes only work belonging to the explicitly identified investigation
     *  (overview of all cleanup paths in {@link DebugInvestigationPolicy}). */
    private String finishInvestigation(String[] p) throws CoreException {
        synchronized (lifecycle) {
            return finishInvestigationLocked(p);
        }
    }

    private String finishInvestigationLocked(String[] p) throws CoreException {
        String id = require(p, 1);
        var investigation = investigations.require(id);
        String action = investigation.mode() == DebugInvestigationPolicy.Mode.NORMAL
                ? "CLEANUP" : "KEEP_SUSPENDED";
        // Ignore free-form client actions: mode is determined when investigation begins.
        if (investigation.mode() == DebugInvestigationPolicy.Mode.CONTROLLED
                && investigation.launchName() != null) {
            ILaunch active = launch(investigation.launchName());
            investigation.verifySession(active);
            boolean suspended = false;
            for (var debugTarget : active.getDebugTargets()) {
                if (debugTarget.isTerminated()) continue;
                for (var thread : debugTarget.getThreads()) {
                    if (thread.isSuspended()) suspended = true;
                }
            }
            if (!suspended) {
                throw new IllegalStateException("CONTROLLED investigation is not suspended; "
                        + "pause the owned session before finishing");
            }
        }
        String termination = "";
        boolean ended = true;
        if (investigation.mode() == DebugInvestigationPolicy.Mode.NORMAL) {
            // Remove breakpoints first, so nothing can suspend the JVM again while it shuts down.
            Cleanup result = cleanUp(investigation);
            termination = result.json();
            ended = result.ended();
        }
        investigations.finish(id);
        return ok(",\"investigationId\":" + q(id)
                + ",\"mode\":" + q(investigation.mode().name())
                + ",\"action\":" + q(action)
                + ",\"launchName\":" + q(investigation.launchName())
                + ",\"ownedSession\":" + (investigation.ownedLaunch() != null)
                + termination
                + ",\"completed\":" + ended
                + (ended ? "" : ",\"warning\":" + q("The owned JVM is still running after terminate, process "
                        + "terminate and OS kill. Check the Eclipse window for an open dialog.")));
    }

    /**
     * Ends the owned launch and checks that its JVM processes really exited. Steps, each only if
     * the previous one did not end every process within 5 s: Eclipse terminate, process terminate,
     * OS kill by PID. Success is judged by the OS processes, not by Eclipse's debug-target state,
     * which can lag (for example while a modal dialog blocks JDT's event handling).
     */
    private static boolean terminateAndVerify(ILaunch launch, List<String> steps) {
        if (processesEnded(launch)) {
            steps.add("already-ended");
            return true;
        }
        try {
            launch.terminate();
            steps.add("launch-terminate");
        } catch (Exception e) {
            steps.add("launch-terminate-failed: " + e.getMessage());
        }
        if (waitForEnd(launch, 5_000)) return true;
        for (IProcess process : launch.getProcesses()) {
            try {
                if (!process.isTerminated()) process.terminate();
            } catch (Exception e) {
                steps.add("process-terminate-failed: " + e.getMessage());
            }
        }
        steps.add("process-terminate");
        if (waitForEnd(launch, 5_000)) return true;
        for (IProcess process : launch.getProcesses()) {
            String pid = process.getAttribute("org.eclipse.debug.core.ATTR_PROCESS_ID");
            if (pid == null || process.isTerminated()) continue;
            try {
                ProcessHandle.of(Long.parseLong(pid.trim())).ifPresent(handle -> {
                    handle.descendants().forEach(ProcessHandle::destroyForcibly);
                    handle.destroyForcibly();
                });
                steps.add("os-kill pid " + pid.trim());
            } catch (Exception e) {
                steps.add("os-kill-failed pid " + pid + ": " + e.getMessage());
            }
        }
        boolean ended = waitForEnd(launch, 5_000) || osProcessesGone(launch);
        if (!ended) steps.add("still-running");
        return ended;
    }

    private static boolean waitForEnd(ILaunch launch, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (processesEnded(launch)) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return processesEnded(launch);
            }
        }
        return processesEnded(launch);
    }

    private static boolean processesEnded(ILaunch launch) {
        IProcess[] processes = launch.getProcesses();
        if (processes.length == 0) return launch.isTerminated();
        for (IProcess process : processes) if (!process.isTerminated()) return false;
        return true;
    }

    /** Final check straight against the OS, in case Eclipse has not noticed the exit yet. */
    private static boolean osProcessesGone(ILaunch launch) {
        for (IProcess process : launch.getProcesses()) {
            String pid = process.getAttribute("org.eclipse.debug.core.ATTR_PROCESS_ID");
            if (pid == null) return false;
            try {
                if (ProcessHandle.of(Long.parseLong(pid.trim())).map(ProcessHandle::isAlive).orElse(false)) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Puts the user's repeated-exception setting back once no agent exception breakpoint is left. */
    private void restoreRecurrencePolicyIfUnused() throws CoreException {
        boolean remain = false;
        for (var item : breakpoints.listExceptionBreakpoints()) if ("agent".equals(item.owner())) remain = true;
        ExceptionRecurrencePolicy.restoreIfUnused(remain);
    }

    private String listBreakpoints() throws CoreException {
        var all = breakpoints.listBreakpoints();
        List<String> items = new ArrayList<>();
        for (var bp : all) items.add("{\"typeName\":" + q(bp.typeName())
                + ",\"lineNumber\":" + bp.lineNumber() + ",\"projectName\":" + q(bp.projectName())
                + ",\"enabled\":" + bp.enabled() + ",\"owner\":" + q(bp.owner())
                + ",\"condition\":" + q(bp.condition()) + ",\"tracepoint\":" + bp.tracepoint() + "}");
        return ok(",\"breakpoints\":[" + String.join(",", items) + "]");
    }

    private String setBreakpoint(String[] p) throws CoreException {
        String investigationId = optionalInvestigation(p, 3);
        var investigation = investigationId == null ? null : investigations.require(investigationId);
        var bp = breakpoints.setBreakpoint(require(p, 1), number(p, 2), investigationId);
        if (investigation != null && bp.created()) {
            investigation.recordBreakpoint(bp.typeName(), bp.lineNumber());
        }
        return ok(",\"typeName\":" + q(bp.typeName()) + ",\"lineNumber\":" + bp.lineNumber()
                + ",\"projectName\":" + q(bp.projectName()) + ",\"created\":" + bp.created()
                + ",\"owner\":" + q(bp.owner()));
    }

    private String setConditionalBreakpoint(String[] p, boolean tracepoint) throws CoreException {
        String type = require(p, 1);
        int line = number(p, 2);
        String expression = require(p, 3);
        String id = optionalInvestigation(p, 4);
        var investigation = id == null ? null : investigations.require(id);
        if (tracepoint && expression.length() > 200) {
            throw new IllegalArgumentException("Trace expression must be at most 200 characters");
        }
        if (tracepoint) {
            // OPEN-58: a tracepoint runs the expression in the program each time the line runs; the user decides.
            var decision = consent.decide(new EvaluationConsent.Request(true, expression,
                    type.substring(type.lastIndexOf('.') + 1) + " line " + line, investigation));
            if (!decision.allowed()) return error(decision.message());
        }
        var bp = breakpoints.setBreakpoint(type, line, id, expression, tracepoint);
        if (investigation != null && bp.created()) investigation.recordBreakpoint(bp.typeName(), bp.lineNumber());
        if (!bp.created()) {
            return error("Breakpoint already exists at this location (" + bp.owner()
                    + "); existing breakpoints are never modified");
        }
        return ok(",\"typeName\":" + q(type) + ",\"lineNumber\":" + line
                + ",\"expression\":" + q(expression) + ",\"tracepoint\":" + tracepoint
                + ",\"created\":true,\"owner\":\"agent\"");
    }

    private String setExceptionBreakpoint(String[] p) throws CoreException {
        String type = require(p, 1);
        boolean caught = Boolean.parseBoolean(require(p, 2));
        boolean uncaught = Boolean.parseBoolean(require(p, 3));
        String id = optionalInvestigation(p, 4);
        var investigation = id == null ? null : investigations.require(id);
        // Avoid JDT's modal "Repeated exception occurrence" dialog (see ExceptionRecurrencePolicy).
        String recurrence = ExceptionRecurrencePolicy.beforeAgentExceptionBreakpoint();
        var result = breakpoints.setExceptionBreakpoint(type, caught, uncaught, id);
        if (investigation != null && result.created()) investigation.recordException(type);
        if (!result.created()) {
            restoreRecurrencePolicyIfUnused();
            return error("An exception breakpoint already exists for " + type
                    + "; existing markers are never modified");
        }
        return ok(",\"exceptionName\":" + q(type) + ",\"caught\":" + caught
                + ",\"uncaught\":" + uncaught + ",\"created\":true"
                + ",\"repeatedException\":" + q(recurrence));
    }

    private String listExceptionBreakpoints() throws CoreException {
        List<String> items = new ArrayList<>();
        for (var item : breakpoints.listExceptionBreakpoints()) {
            items.add("{\"exceptionName\":" + q(item.typeName())
                    + ",\"caught\":" + item.caught()
                    + ",\"uncaught\":" + item.uncaught()
                    + ",\"owner\":" + q(item.owner()) + "}");
        }
        return ok(",\"breakpoints\":[" + String.join(",", items) + "]");
    }

    private String removeExceptionBreakpoint(String[] p) throws CoreException {
        boolean removed = breakpoints.removeAgentExceptionBreakpoint(require(p, 1));
        restoreRecurrencePolicyIfUnused();
        return ok(",\"removed\":" + removed);
    }

    /** Discover JUnit 4/5 method candidates without requiring test execution. */
    private String listJUnitTests() throws CoreException {
        List<String> result = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) continue;
            var jp = JavaCore.create(project);
            for (var root : jp.getPackageFragments()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
                for (var unit : root.getCompilationUnits()) {
                    for (var type : unit.getAllTypes()) {
                        if (!type.isClass()) continue;
                        for (var method : type.getMethods()) {
                            for (var annotation : method.getAnnotations()) {
                                if (!"Test".equals(annotation.getElementName())
                                        && !"ParameterizedTest".equals(annotation.getElementName())
                                        && !"RepeatedTest".equals(annotation.getElementName())) continue;
                                result.add("{\"projectName\":" + q(project.getName())
                                        + ",\"typeName\":" + q(type.getFullyQualifiedName())
                                        + ",\"methodName\":" + q(method.getElementName()) + "}");
                                break;
                            }
                        }
                    }
                }
            }
        }
        return ok(",\"tests\":[" + String.join(",", result) + "]");
    }

    /**
     * Use Eclipse's JUnit launch delegate, without coupling the plugin to JUnit internals. Arguments: project, test
     * class, method (may be empty: the whole class), the investigation (may be empty) and, OPEN-25, the mode:
     * {@code DEBUG} (default) or {@code RUN}, which runs the test without the debugger so its result
     * ({@code GET_JUNIT_RESULTS}) is there in seconds; breakpoints do not stop a run.
     */
    private String startJUnitTest(String[] p) throws CoreException {
        String projectName = require(p, 1);
        String typeName = require(p, 2);
        String methodName = p.length > 3 ? decoded(p[3]) : "";
        String id = optionalInvestigation(p, 4);
        String mode = p.length > 5 && !p[5].isBlank() ? decoded(p[5]).toUpperCase(java.util.Locale.ROOT) : "DEBUG";
        if (!mode.equals("DEBUG") && !mode.equals("RUN")) throw new IllegalArgumentException("mode must be debug or run");
        boolean run = mode.equals("RUN");
        var investigation = id == null ? null : investigations.require(id);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
            throw new IllegalArgumentException("Open Java test project not found: " + projectName);
        }
        IType type = JavaCore.create(project).findType(typeName);
        if (type == null || !type.exists()) throw new IllegalArgumentException("Test class not found");
        if (!methodName.isBlank()) {
            boolean found = false;
            for (var method : type.getMethods()) if (methodName.equals(method.getElementName())) found = true;
            if (!found) throw new IllegalArgumentException("JUnit test method not found");
        }
        BuildCheck.verifyLaunchable(type); // OPEN-34
        var configType = manager().getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig");
        if (configType == null) throw new IllegalArgumentException("Eclipse JUnit launch support is unavailable");
        String suggestedName = "Agentic JUnit - " + projectName + " - " + type.getElementName()
                + (methodName.isBlank() ? "" : "." + methodName);
        var workingCopy = configType.newInstance(null, manager().generateLaunchConfigurationName(suggestedName));
        workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
        workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, typeName);
        if (!methodName.isBlank()) workingCopy.setAttribute("org.eclipse.jdt.junit.TESTNAME", methodName);
        // Select the runner supported by the test source; JUnit attributes are Eclipse internals.
        String testSource = type.getCompilationUnit() == null ? "" : type.getCompilationUnit().getSource();
        workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND",
                testSource.contains("org.junit.jupiter")
                        ? "org.eclipse.jdt.junit.loader.junit5"
                        : "org.eclipse.jdt.junit.loader.junit4");
        // OPEN-25: a run without the debugger does not take the investigation's one debug session (the debug
        // launch that follows in the same investigation must still be allowed, trial 26 Nov 02:25).
        if (investigation != null) {
            if (run) investigation.ensureActive();
            else {
                // OPEN-25: the investigation's own earlier debug run has ended: the new debug run replaces it.
                investigation.releaseEndedLaunch(
                        investigation.ownedLaunch() instanceof ILaunch previous && launchEnded(previous));
                investigation.verifyNewLaunch(workingCopy.getName());
            }
        }
        // OPEN-25: listen before the launch, so this run's results are captured from its start.
        boolean results = listenToJUnit();
        var config = workingCopy.doSave();
        ILaunch launched;
        try { launched = config.launch(run ? ILaunchManager.RUN_MODE : ILaunchManager.DEBUG_MODE, null); }
        catch (CoreException failure) { config.delete(); throw failure; }
        if (investigation != null) {
            if (run) investigation.recordRun(launched);
            else investigation.recordLaunch(config.getName(), launched, true);
        }
        if (!run) ui.follow(launched);
        return ok(",\"launchName\":" + q(config.getName())
                + ",\"projectName\":" + q(projectName) + projectLocation(config)
                + ",\"typeName\":" + q(typeName) + ",\"methodName\":" + q(methodName)
                + ",\"mode\":" + q(mode.toLowerCase(java.util.Locale.ROOT))
                + ",\"resultsAvailable\":" + results);
    }

    /**
     * OPEN-25: the launch's JVM has ended. A JUnit debug launch whose test JVM ended is left "disconnected", not
     * terminated, so {@link ILaunch#isTerminated()} alone stays false (check 26 Nov 03:58): the TERMINATE event seen by
     * the stop tracker, or all processes ended, count as well.
     */
    private boolean launchEnded(ILaunch launch) {
        if (launch.isTerminated() || stops.ended(launch)) return true;
        var processes = launch.getProcesses();
        if (processes.length == 0) return false;
        for (var process : processes) if (!process.isTerminated()) return false;
        return true;
    }

    /** OPEN-25: registers the JUnit result listener; false when Eclipse's JUnit support is not installed. */
    private static boolean listenToJUnit() {
        try {
            JUnitResults.listen();
            return true;
        } catch (LinkageError | RuntimeException unavailable) {
            return false;
        }
    }

    /** Tests listed in a result reply; failed and erroring tests come first. */
    static final int MAX_LISTED_TESTS = 50;

    /**
     * OPEN-25: the result of a JUnit run started by {@code START_JUNIT_TEST}: per test method its result (PASSED,
     * FAILED, ERROR, SKIPPED), time, and for a failure the message, expected and actual, the failure location (first
     * frame outside the test framework and the JDK) and a short trace; counts and the run's overall result. Waits up
     * to the given milliseconds (0-60000) for the run to finish. Read-only.
     */
    private String getJUnitResults(String[] p) throws InterruptedException {
        String name = require(p, 1);
        int waitMillis = p.length > 2 && !p[2].isBlank() ? number(p, 2) : 0;
        if (waitMillis < 0 || waitMillis > 60_000) throw new IllegalArgumentException("waitMillis must be 0-60000");
        if (!listenToJUnit()) return error("JUnit results are not available: Eclipse's JUnit support is not installed");
        long deadline = System.currentTimeMillis() + waitMillis;
        long endedSince = 0;
        JUnitResults.Run run;
        boolean ended;
        while (true) {
            run = JUnitResults.run(name);
            ended = !anyLaunchRunning(name);
            if (run != null && run.finished) break;
            long now = System.currentTimeMillis();
            // The JVM has ended: give Eclipse's JUnit model 2 s to report the end of the run.
            if (ended && endedSince == 0) endedSince = now;
            if (ended && now - endedSince > 2_000) break;
            if (now >= deadline) break;
            Thread.sleep(200);
        }
        if (run == null) {
            return ok(",\"launchName\":" + q(name) + ",\"finished\":false,\"result\":\"UNKNOWN\",\"tests\":[]"
                    + ",\"note\":" + q(ended
                    ? "No JUnit results were captured for this launch: it is not a JUnit launch started by the agent,"
                            + " or it ended before the test run reported."
                    : "The test run has not reported yet; call again with waitSeconds."));
        }
        List<JUnitResults.TestCase> tests = new ArrayList<>(run.tests);
        tests.sort(java.util.Comparator.comparingInt(t -> t.result().equals("FAILED") || t.result().equals("ERROR") ? 0 : 1));
        int passed = 0, failed = 0, errors = 0, skipped = 0;
        for (var t : tests) {
            switch (t.result()) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "ERROR" -> errors++;
                case "SKIPPED" -> skipped++;
                default -> { }
            }
        }
        List<String> items = new ArrayList<>();
        for (var t : tests.subList(0, Math.min(tests.size(), MAX_LISTED_TESTS))) items.add(testJson(t));
        String note = run.finished ? null : ended
                ? "The JVM ended before the test run finished (for example it was terminated); the results so far are listed."
                : "The test run is still in progress (in debug mode it may be paused at a breakpoint); the results so far"
                        + " are listed. Call again with waitSeconds, or resume the program.";
        return ok(",\"launchName\":" + q(name) + ",\"finished\":" + run.finished
                + ",\"result\":" + q(run.finished ? run.result : "RUNNING")
                + ",\"total\":" + tests.size() + ",\"passed\":" + passed + ",\"failed\":" + failed
                + ",\"errors\":" + errors + ",\"skipped\":" + skipped
                + ",\"tests\":[" + String.join(",", items) + "]"
                + (tests.size() > MAX_LISTED_TESTS ? ",\"testsListed\":" + MAX_LISTED_TESTS : "")
                + (note == null ? "" : ",\"note\":" + q(note)));
    }

    private static String testJson(JUnitResults.TestCase t) {
        StringBuilder out = new StringBuilder("{\"className\":").append(q(t.className()))
                .append(",\"methodName\":").append(q(t.methodName()))
                .append(",\"result\":").append(q(t.result()));
        if (!Double.isNaN(t.seconds()) && t.seconds() >= 0)
            out.append(",\"seconds\":").append(String.format(java.util.Locale.ROOT, "%.3f", t.seconds()));
        if (t.trace() != null) {
            String[] values = JUnitFailure.expectedActual(t.trace(), t.expected(), t.actual());
            out.append(",\"message\":").append(q(JUnitFailure.message(t.trace())));
            if (values[0] != null) out.append(",\"expected\":").append(q(values[0]));
            if (values[1] != null) out.append(",\"actual\":").append(q(values[1]));
            var where = JUnitFailure.location(t.trace());
            if (where != null) out.append(",\"location\":").append(q(where.label()))
                    .append(",\"locationClass\":").append(q(where.className()))
                    .append(",\"locationLine\":").append(where.line());
            out.append(",\"trace\":").append(q(JUnitFailure.shortTrace(t.trace(), 8)));
        }
        return out.append('}').toString();
    }

    /**
     * OPEN-25: when a JUnit run ends, the reply the agent already gets ({@code wait_for_stop} TERMINATED) carries the
     * test result: overall result, counts and the first failed test with expected, actual and location. No extra call
     * and no extra JVM. Empty for a launch that is not a JUnit run of the agent; waits up to 2 s for Eclipse's JUnit
     * model to report the end of the run.
     */
    private static String testResultOnEnd(String name) {
        try {
            JUnitResults.Run run = JUnitResults.run(name);
            for (int i = 0; run != null && !run.finished && i < 10; i++) {
                Thread.sleep(200);
                run = JUnitResults.run(name);
            }
            if (run == null) return "";
            int passed = 0, failed = 0;
            JUnitResults.TestCase first = null;
            for (var t : run.tests) {
                if (t.result().equals("PASSED")) passed++;
                if (t.result().equals("FAILED") || t.result().equals("ERROR")) {
                    failed++;
                    if (first == null) first = t;
                }
            }
            return ",\"testResult\":{\"result\":" + q(run.finished ? run.result : "UNKNOWN")
                    + ",\"total\":" + run.tests.size() + ",\"passed\":" + passed + ",\"failed\":" + failed
                    + (first == null ? "" : ",\"firstFailure\":" + testJson(first)) + "}";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } catch (LinkageError | RuntimeException unavailable) {
            return "";
        }
    }

    /** True while a launch of this configuration (any mode) is still running. */
    private static boolean anyLaunchRunning(String name) {
        for (var current : manager().getLaunches()) {
            var c = current.getLaunchConfiguration();
            if (c != null && c.getName().equals(name) && !current.isTerminated()) return true;
        }
        return false;
    }

    /** Restart only a session created and still owned by this investigation. */
    private String restartDebug(String[] p) throws CoreException {
        String name = require(p, 1);
        var investigation = investigations.require(require(p, 2));
        ILaunch old = launch(name);
        if (investigation.ownedLaunch() != old) {
            throw new IllegalArgumentException("Restart requires the investigation-owned launch");
        }
        var configuration = old.getLaunchConfiguration();
        if (configuration == null) throw new IllegalArgumentException("Launch configuration unavailable");
        old.terminate();
        ILaunch replacement = configuration.launch(ILaunchManager.DEBUG_MODE, null);
        investigation.replaceOwnedLaunch(name, old, replacement);
        ui.follow(replacement);
        return ok(",\"launchName\":" + q(name) + ",\"restarted\":true" + projectLocation(configuration));
    }

    private String setStepFilters(String[] p) throws CoreException {
        String name = require(p, 1);
        boolean enabled = Boolean.parseBoolean(require(p, 2));
        String raw = p.length > 3 ? decoded(p[3]) : "";
        IJavaDebugTarget javaTarget = (IJavaDebugTarget) target(launch(name));
        if (!javaTarget.supportsStepFilters()) throw new IllegalArgumentException("Step filters not supported");
        String[] patterns = raw.isBlank() ? new String[0] : raw.split(",");
        if (patterns.length > 30) throw new IllegalArgumentException("Too many step filters");
        for (String pattern : patterns) {
            if (!pattern.matches("[A-Za-z0-9_.$*]+")) {
                throw new IllegalArgumentException("Invalid Java step-filter pattern: " + pattern);
            }
        }
        javaTarget.setStepFilters(patterns);
        javaTarget.setStepFiltersEnabled(enabled);
        return ok(",\"enabled\":" + javaTarget.isStepFiltersEnabled()
                + ",\"filters\":" + q(String.join(",", javaTarget.getStepFilters())));
    }

    /** Read-only, bounded child navigation using Eclipse variable APIs; no Java code evaluated. */
    private String expandVariable(String[] p) throws CoreException {
        var selected = frame(thread(launch(require(p, 1)), number(p, 2)), number(p, 3));
        String path = require(p, 4);
        int offset = number(p, 5);
        int limit = number(p, 6);
        if (limit < 1 || limit > 50 || offset < 0 || offset > 10000) {
            throw new IllegalArgumentException("Invalid offset or limit");
        }
        IVariable[] roots = selected.getVariables();
        IVariable current = null;
        // JDT uses display names such as "processOrder() is throwing" for synthetic locals.
        for (IVariable root : roots) {
            if (path.equals(root.getName())) {
                current = root;
                break;
            }
        }
        if (current == null) {
            if (!path.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*|\\[[0-9]{1,5}\\]){0,8}")) {
                throw new IllegalArgumentException("Invalid variable path");
            }
            String[] elements = path.replace("[", ".[").split("\\.");
            current = variableByName(roots, elements[0]);
            for (int i = 1; i < elements.length; i++) {
                IValue parent = current.getValue();
                if (parent == null || !parent.hasVariables()) throw new IllegalArgumentException("Variable has no children: " + elements[i]);
                current = variableByName(parent.getVariables(), elements[i]);
            }
        }
        IValue value = current.getValue();
        if (value == null) return ok(",\"path\":" + q(path) + ",\"children\":[],\"total\":0");
        // An object's own fields first, its static constants after them (a BigDecimal has 30 constants before its
        // value, trial 25 Nov 12:29).
        IVariable[] children = ownFieldsFirst(value.getVariables());
        List<String> items = new ArrayList<>();
        for (int i = offset; i < Math.min(children.length, offset + limit); i++) {
            IVariable child = children[i];
            IValue item = child.getValue();
            items.add("{\"name\":" + q(child.getName()) + ",\"type\":"
                    + q(child.getReferenceTypeName()) + ",\"value\":"
                    + q(item == null ? null : clipped(valueText(item), 200))
                    + ",\"hasChildren\":" + (item != null && item.hasVariables()) + "}");
        }
        return ok(",\"path\":" + q(path) + ",\"total\":" + children.length
                + ",\"offset\":" + offset + ",\"children\":[" + String.join(",", items) + "]");
    }

    private static IVariable variableByName(IVariable[] candidates, String name) throws CoreException {
        for (IVariable candidate : candidates) {
            if (name.equals(candidate.getName()) || (name.startsWith("[")
                    && (name.equals(candidate.getName()) || name.substring(1, name.length() - 1).equals(candidate.getName()))))
                return candidate;
        }
        throw new IllegalArgumentException("Variable or field not found: " + name);
    }

    private String removeBreakpoint(String[] p) throws CoreException {
        boolean removed = breakpoints.removeAgentBreakpoint(require(p, 1), number(p, 2));
        return ok(",\"removed\":" + removed);
    }

    private String listRunConfigurations() throws CoreException {
        List<String> result = new ArrayList<>();
        for (var c : manager().getLaunchConfigurations()) {
            if (!c.supportsMode(ILaunchManager.DEBUG_MODE)) continue;
            result.add("{\"name\":" + q(c.getName()) + ",\"type\":" + q(c.getType().getIdentifier()) + "}");
        }
        return ok(",\"configurations\":[" + String.join(",", result) + "]");
    }

    /** Discovers source main methods without creating or modifying configurations. */
    private String listJavaApplications() throws CoreException {
        List<String> items = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) continue;
            IJavaProject javaProject = JavaCore.create(project);
            for (IPackageFragment fragment : javaProject.getPackageFragments()) {
                if (fragment.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
                for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                    for (IType type : unit.getAllTypes()) {
                        if (type.getDeclaringType() != null || (!type.isClass() && !type.isEnum())) continue;
                        for (var method : type.getMethods()) {
                            if (method.isMainMethod()) {
                                items.add("{\"project\":" + q(project.getName())
                                        + ",\"mainType\":" + q(type.getFullyQualifiedName()) + "}");
                                break;
                            }
                        }
                    }
                }
            }
        }
        return ok(",\"applications\":[" + String.join(",", items) + "]");
    }

    /**
     * Creates a Java Application configuration only for an unambiguous, validated main type.
     * Existing application configurations are reused and never overwritten.
     */
    private String startJavaApplication(String[] parts) throws CoreException {
        String investigationId = optionalInvestigation(parts, 3);
        var investigation = investigationId == null ? null : investigations.require(investigationId);
        String projectName = require(parts, 1);
        String mainType = require(parts, 2);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
            throw new IllegalArgumentException("Open Java project not found: " + projectName);
        }
        IJavaProject javaProject = JavaCore.create(project);
        IType type = javaProject.findType(mainType);
        if (type == null || !type.exists()) {
            throw new IllegalArgumentException("Main type not found in project: " + mainType);
        }
        boolean runnable = false;
        for (var method : type.getMethods()) {
            if (method.isMainMethod()) { runnable = true; break; }
        }
        if (!runnable) throw new IllegalArgumentException("Type has no Java main method: " + mainType);

        String configurationTypeId = IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION;
        List<ILaunchConfiguration> matching = new ArrayList<>();
        for (var config : manager().getLaunchConfigurations()) {
            if (!configurationTypeId.equals(config.getType().getIdentifier())) continue;
            if (projectName.equals(config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, ""))
                    && mainType.equals(config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, ""))) {
                matching.add(config);
            }
        }
        if (matching.size() > 1) {
            throw new IllegalArgumentException("Several launch configurations match this application; choose one by launch name");
        }
        // OPEN-34: before a NEW start (and before a configuration is created), compile errors or no compiled class
        // are refused; an already running session is reused as it is.
        if (matching.isEmpty() || activeLaunch(matching.get(0).getName()) == null) BuildCheck.verifyLaunchable(type);
        ILaunchConfiguration config;
        boolean created = matching.isEmpty();
        if (created) {
            var configType = manager().getLaunchConfigurationType(configurationTypeId);
            if (configType == null) throw new IllegalStateException("Java Application launch support is unavailable");
            String suggestedName = "Agentic Debugger - " + projectName + " - " + type.getElementName();
            var workingCopy = configType.newInstance(null, manager().generateLaunchConfigurationName(suggestedName));
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, mainType);
            if (investigation != null) investigation.verifyNewLaunch(workingCopy.getName());
            config = workingCopy.doSave();
        } else {
            config = matching.get(0);
        }
        String name = config.getName();
        if (investigation != null) investigation.verifyLaunchTarget(name);
        ILaunch existing = activeLaunch(name);
        if (existing != null) {
            if (investigation != null) investigation.recordLaunch(name, existing, false);
            ui.follow(existing);
            return ok(",\"launchName\":" + q(name)
                    + ",\"createdConfiguration\":" + created + ",\"reusedSession\":true" + projectLocation(config));
        }
        if (investigation != null) investigation.verifyNewLaunch(name);
        ILaunch started = config.launch(ILaunchManager.DEBUG_MODE, null);
        if (investigation != null) investigation.recordLaunch(name, started, true);
        ui.follow(started);
        return ok(",\"launchName\":" + q(name)
                + ",\"createdConfiguration\":" + created + ",\"reusedSession\":false" + projectLocation(config));
    }

    private static ILaunchManager manager() { return DebugPlugin.getDefault().getLaunchManager(); }

    /**
     * {@code ,"projectLocation":"<folder>"} for the Eclipse project of a launch configuration, or nothing when the
     * configuration names no project or the project has no local folder. The MCP server writes the debugging session
     * report into this project (OPEN-30); the field is informational for every other client.
     */
    private static String projectLocation(ILaunchConfiguration configuration) {
        try {
            String projectName = configuration == null ? ""
                    : configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
            if (projectName.isBlank()) return "";
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists() || project.getLocation() == null) return "";
            return ",\"projectLocation\":" + q(project.getLocation().toOSString());
        } catch (CoreException | RuntimeException unavailable) {
            return "";
        }
    }

    private String startDebug(String[] p) throws CoreException {
        String investigationId = optionalInvestigation(p, 2);
        var investigation = investigationId == null ? null : investigations.require(investigationId);
        String name = require(p, 1);
        ILaunchConfiguration found = null;
        for (var c : manager().getLaunchConfigurations()) {
            if (c.getName().equals(name) && c.supportsMode(ILaunchManager.DEBUG_MODE)) {
                if (found != null) throw new IllegalArgumentException("Ambiguous launch configuration name");
                found = c;
            }
        }
        if (found == null) throw new IllegalArgumentException("Debug-capable launch configuration not found");
        if (investigation != null) investigation.verifyLaunchTarget(name);
        ILaunch existing = activeLaunch(name);
        if (existing != null) {
            if (investigation != null) investigation.recordLaunch(name, existing, false);
            ui.follow(existing);
            return ok(",\"reused\":true,\"launchName\":" + q(name) + projectLocation(found));
        }
        if (investigation != null) investigation.verifyNewLaunch(name);
        verifyBuilt(found); // OPEN-34
        ILaunch started = found.launch(ILaunchManager.DEBUG_MODE, null);
        if (investigation != null) investigation.recordLaunch(name, started, true);
        ui.follow(started);
        return ok(",\"reused\":false,\"launchName\":" + q(name) + projectLocation(found));
    }

    /** OPEN-34 for an existing launch configuration: checks the project and main (or test) class it names, if found. */
    private static void verifyBuilt(ILaunchConfiguration configuration) throws CoreException {
        String projectName = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
        String typeName = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
        if (projectName.isBlank() || typeName.isBlank()) return;
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) return;
        IType type = JavaCore.create(project).findType(typeName);
        if (type != null && type.exists()) BuildCheck.verifyLaunchable(type);
    }

    private static ILaunch activeLaunch(String name) {
        ILaunch match = null;
        for (var current : manager().getLaunches()) {
            if (current.isTerminated() || !ILaunchManager.DEBUG_MODE.equals(current.getLaunchMode())) continue;
            var c = current.getLaunchConfiguration();
            if (c != null && c.getName().equals(name)) {
                if (match != null) throw new IllegalArgumentException("Multiple active launches share that name");
                match = current;
            }
        }
        return match;
    }

    private static ILaunch launch(String name) {
        ILaunch match = activeLaunch(name);
        if (match == null) {
            // OPEN-12: say plainly when the program has already ended (the message keeps its old start for callers).
            throw new IllegalArgumentException("Active debug launch not found: " + name + (endedLaunch(name)
                    ? " (this program has ended; nothing is left to inspect or control - start it again to debug further)"
                    : ""));
        }
        return match;
    }

    /** True when a debug launch of that name exists and has terminated (OPEN-12). */
    private static boolean endedLaunch(String name) {
        for (var current : manager().getLaunches()) {
            var c = current.getLaunchConfiguration();
            if (current.isTerminated() && c != null && c.getName().equals(name)
                    && ILaunchManager.DEBUG_MODE.equals(current.getLaunchMode())) return true;
        }
        return false;
    }

    private static IDebugTarget target(ILaunch l) {
        IDebugTarget chosen = null;
        boolean ended = false;
        for (var t : l.getDebugTargets()) {
            if (!(t instanceof IJavaDebugTarget)) continue;
            if (t.isTerminated()) {
                ended = true;
                continue;
            }
            if (chosen != null) throw new IllegalArgumentException("Multiple Java targets; operation is ambiguous");
            chosen = t;
        }
        // OPEN-12: the program's JVM has ended although Eclipse has not closed the launch yet (seen 27 Nov 02:33).
        // "No active Java debug target yet" stays for a program that is still starting: the MCP server waits on it.
        if (chosen == null) throw new IllegalArgumentException(ended
                ? "This program has ended; nothing is left to inspect or control - start it again to debug further"
                : "No active Java debug target yet");
        return chosen;
    }

    /** Display the suspended source for the exact named launch without changing execution. */
    private String showDebugLocation(String[] p) throws CoreException {
        ILaunch selected = launch(require(p, 1));
        boolean scheduled = ui.showSuspended(selected, true);
        return ok(",\"scheduled\":" + scheduled + ",\"launchName\":"
                + q(selected.getLaunchConfiguration().getName()));
    }

    private String getDebugState(String[] p) throws CoreException {
        ILaunch l = launch(require(p, 1));
        IDebugTarget t = target(l);
        boolean suspended = false;
        for (IThread th : t.getThreads()) suspended |= th.isSuspended();
        return ok(",\"launchName\":" + q(l.getLaunchConfiguration().getName())
                + ",\"targetName\":" + q(t.getName()) + ",\"terminated\":" + t.isTerminated()
                + ",\"disconnected\":" + t.isDisconnected() + ",\"suspended\":" + suspended);
    }

    /**
     * WAIT_FOR_STOP launchName timeoutMillis — waits for the next real debugger stop of the launch
     * (event-based, see {@link DebugStopTracker}) and returns, in one reply, the stop reason, the
     * thread, its location, the top stack frames and the top frame's locals. Each stop is reported
     * once. Replies with stopped=false and reason TIMEOUT or TERMINATED otherwise.
     */
    private String waitForStop(String[] p) throws Exception {
        String name = require(p, 1);
        int timeout = number(p, 2);
        if (timeout < 0 || timeout > 60_000) throw new IllegalArgumentException("timeout must be 0-60000 ms");
        ILaunch launch;
        try {
            launch = launchForWait(name);
        } catch (IllegalArgumentException notFound) {
            // OPEN-23: the launch ended and Eclipse removed it from its list (a JUnit run, after the agent resumed it):
            // the same answer as for an ended launch, not an error (Copilot run 26 Nov 17:35).
            if (!stops.endedByName(name)) throw notFound;
            return ok(",\"stopped\":false,\"reason\":\"TERMINATED\",\"launchName\":" + q(name)
                    + ",\"note\":\"The launch has ended and is no longer listed in Eclipse.\"" + testResultOnEnd(name));
        }
        DebugStopTracker.Outcome outcome = stops.waitForStop(launch, timeout);
        if (outcome.stop() == null) {
            return ok(",\"stopped\":false,\"reason\":" + q(outcome.terminated() ? "TERMINATED" : "TIMEOUT")
                    + ",\"launchName\":" + q(name) + (outcome.terminated() ? testResultOnEnd(name) : ""));
        }
        DebugStopTracker.Stop stop = outcome.stop();
        IThread thread = stop.thread();
        ui.follow(launch);
        int index = -1;
        IThread[] threads = target(launch).getThreads();
        for (int i = 0; i < threads.length; i++) if (threads[i] == thread) index = i;
        // Frames and locals are read separately: a frame without readable locals (for example a
        // native method such as Thread.sleep after a pause) must not hide the stop itself.
        IStackFrame[] frames;
        try {
            frames = thread.getStackFrames();
        } catch (org.eclipse.debug.core.DebugException unreadable) {
            if (!thread.isSuspended()) {
                throw new IllegalStateException("The thread resumed before its stop could be read; call wait_for_stop again");
            }
            throw new IllegalStateException("Stack of the stopped thread could not be read: " + unreadable.getMessage());
        }
        List<String> frameItems = new ArrayList<>();
        for (int i = 0; i < Math.min(frames.length, 5); i++) {
            IStackFrame f = frames[i];
            String type = null, method = null;
            int line = -1;
            try {
                type = f instanceof IJavaStackFrame j ? j.getDeclaringTypeName() : null;
                method = f instanceof IJavaStackFrame j ? j.getMethodName() : f.getName();
                line = f.getLineNumber();
            } catch (org.eclipse.debug.core.DebugException unreadable) {
                // Keep the frame with the fields that could be read.
            }
            frameItems.add("{\"index\":" + i + ",\"className\":" + q(type)
                    + ",\"methodName\":" + q(method) + ",\"lineNumber\":" + line + "}");
        }
        List<String> locals = new ArrayList<>();
        String localsNote = null;
        if (frames.length > 0) {
            try {
                if (frames[0] instanceof IJavaStackFrame j && j.isNative()) {
                    localsNote = "Native method: no local variables";
                } else {
                    int count = 0;
                    for (IVariable v : frames[0].getVariables()) {
                        if (NO_RETURN_VALUE.equals(v.getName())) continue;
                        if (++count > 50) break;
                        String value;
                        try {
                            IValue raw = v.getValue();
                            value = raw == null ? null : clipped(valueText(raw), 300);
                        } catch (org.eclipse.debug.core.DebugException unreadable) {
                            value = "<unreadable: " + unreadable.getMessage() + ">";
                        }
                        locals.add("{\"name\":" + q(v.getName()) + ",\"type\":" + q(v.getReferenceTypeName())
                                + ",\"value\":" + q(value) + "}");
                    }
                }
            } catch (org.eclipse.debug.core.DebugException unreadable) {
                localsNote = "Local variables not available: " + unreadable.getMessage();
            }
        }
        return ok(",\"stopped\":true,\"reason\":" + q(stop.reason().name())
                + ",\"launchName\":" + q(name)
                + ",\"threadIndex\":" + index + ",\"threadName\":" + q(thread.getName())
                + (stop.breakpointType() == null ? "" : ",\"breakpoint\":{\"typeName\":" + q(stop.breakpointType())
                    + ",\"lineNumber\":" + stop.breakpointLine() + "}")
                + (stop.exceptionType() == null ? "" : ",\"exceptionType\":" + q(stop.exceptionType()))
                + ",\"location\":" + (frameItems.isEmpty() ? "null" : frameItems.get(0))
                + ",\"frames\":[" + String.join(",", frameItems) + "]"
                + ",\"totalFrames\":" + frames.length
                + ",\"locals\":[" + String.join(",", locals) + "]"
                + (localsNote == null ? "" : ",\"localsNote\":" + q(localsNote)));
    }

    /** The active launch with this name, or else the most recent terminated one (to report TERMINATED). */
    private static ILaunch launchForWait(String name) {
        ILaunch active = activeLaunch(name);
        if (active != null) return active;
        ILaunch latest = null;
        for (var current : manager().getLaunches()) {
            var c = current.getLaunchConfiguration();
            if (ILaunchManager.DEBUG_MODE.equals(current.getLaunchMode()) && c != null && c.getName().equals(name))
                latest = current;
        }
        if (latest == null) throw new IllegalArgumentException("Debug launch not found: " + name);
        return latest;
    }

    private String getThreads(String[] p) throws CoreException {
        IThread[] threads = target(launch(require(p, 1))).getThreads();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < threads.length; i++) {
            IThread t = threads[i];
            items.add("{\"index\":" + i + ",\"name\":" + q(t.getName())
                    + ",\"suspended\":" + t.isSuspended() + ",\"terminated\":" + t.isTerminated() + "}");
        }
        return ok(",\"threads\":[" + String.join(",", items) + "]");
    }

    private static IThread thread(ILaunch launch, int index) throws CoreException {
        IThread[] all = target(launch).getThreads();
        if (index < 0 || index >= all.length) {
            // OPEN-12: the JVM is ending: its process has ended, or its target has no threads left, before Eclipse marks
            // the target terminated (seen 27 Nov 02:40 right after wait_for_stop reported TERMINATED).
            boolean ending = all.length == 0;
            for (IProcess process : launch.getProcesses()) if (process.isTerminated()) ending = true;
            if (ending) throw new IllegalArgumentException("This program has ended; nothing is left to inspect or control"
                    + " - start it again to debug further");
            throw new IllegalArgumentException("Thread index out of range; call get_threads again");
        }
        // OPEN-12: a thread that ended between the agent's last look and this call.
        if (all[index].isTerminated()) throw new IllegalArgumentException("Thread " + index + " has ended; call get_threads again");
        return all[index];
    }

    private static IStackFrame frame(IThread thread, int index) throws CoreException {
        if (!thread.isSuspended()) throw new IllegalArgumentException("Thread must be suspended");
        IStackFrame[] all = thread.getStackFrames();
        if (index < 0 || index >= all.length) throw new IllegalArgumentException("Frame index out of range; call get_stack again");
        return all[index];
    }

    private String getStack(String[] p) throws CoreException {
        ILaunch selected = launch(require(p, 1));
        ui.follow(selected);
        IThread t = thread(selected, number(p, 2));
        if (!t.isSuspended()) throw new IllegalArgumentException("Thread is not suspended");
        List<String> items = new ArrayList<>();
        IStackFrame[] frames = t.getStackFrames();
        for (int i = 0; i < Math.min(frames.length, 60); i++) {
            IStackFrame f = frames[i];
            String type = f instanceof IJavaStackFrame j ? j.getDeclaringTypeName() : null;
            String method = f instanceof IJavaStackFrame j ? j.getMethodName() : f.getName();
            items.add("{\"index\":" + i + ",\"className\":" + q(type)
                    + ",\"methodName\":" + q(method) + ",\"lineNumber\":" + f.getLineNumber() + "}");
        }
        return ok(",\"frames\":[" + String.join(",", items) + "]");
    }

    private String getVariables(String[] p) throws CoreException {
        IStackFrame f = frame(thread(launch(require(p, 1)), number(p, 2)), number(p, 3));
        List<String> items = new ArrayList<>();
        int count = 0;
        for (IVariable v : f.getVariables()) {
            if (NO_RETURN_VALUE.equals(v.getName())) continue;
            if (++count > 100) break;
            IValue value = v.getValue();
            items.add("{\"name\":" + q(v.getName()) + ",\"type\":" + q(v.getReferenceTypeName())
                    + ",\"value\":" + q(value == null ? null : clipped(valueText(value), 500)) + "}");
        }
        return ok(",\"variables\":[" + String.join(",", items) + "]");
    }

    /** Eclipse's placeholder entry when no method has returned yet; tells the agent nothing. */
    private static final String NO_RETURN_VALUE = "no method return value";

    /**
     * The value text for the agent: for common JDK value objects (BigDecimal, number wrappers, dates and times, UUID)
     * the value itself, read from their fields without running code in the program ({@link ValueText}); otherwise what
     * Eclipse shows, such as {@code (id=74)} for other objects.
     */
    static String valueText(IValue value) throws org.eclipse.debug.core.DebugException {
        if (value == null) return null;
        String shown = value.getValueString();
        if (value instanceof org.eclipse.jdt.debug.core.IJavaValue java && !java.isNull()) {
            String type = value.getReferenceTypeName();
            if (ValueText.TYPES.contains(type)) {
                String readable = ValueText.format(type, ownFields(value, 1));
                if (readable != null) return readable;
            }
        }
        return shown;
    }

    /** An object's own (not static) fields: name to value text; nested value objects read once more ({@code depth}). */
    private static java.util.Map<String, String> ownFields(IValue value, int depth) throws org.eclipse.debug.core.DebugException {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (IVariable v : value.getVariables()) {
            if (v instanceof org.eclipse.jdt.debug.core.IJavaFieldVariable field && field.isStatic()) continue;
            IValue child = v.getValue();
            String text = child == null ? "null" : child.getValueString();
            if (depth > 0 && child instanceof org.eclipse.jdt.debug.core.IJavaValue java && !java.isNull()
                    && ValueText.TYPES.contains(child.getReferenceTypeName())) {
                String nested = ValueText.format(child.getReferenceTypeName(), ownFields(child, depth - 1));
                if (nested != null) text = nested;
            }
            out.put(v.getName(), text);
        }
        return out;
    }

    /** The same variables, an object's own fields before its static ones (order kept within each). */
    private static IVariable[] ownFieldsFirst(IVariable[] variables) throws org.eclipse.debug.core.DebugException {
        List<IVariable> own = new ArrayList<>(), statics = new ArrayList<>();
        for (IVariable v : variables)
            (v instanceof org.eclipse.jdt.debug.core.IJavaFieldVariable field && field.isStatic() ? statics : own).add(v);
        own.addAll(statics);
        return own.toArray(new IVariable[0]);
    }

    private static String clipped(String value, int length) {
        return value == null ? null : value.substring(0, Math.min(value.length(), length));
    }

    private String evaluateExpression(String[] p) throws Exception {
        String name = require(p, 1);
        ILaunch evaluated = launch(name);
        IStackFrame f = frame(thread(evaluated, number(p, 2)), number(p, 3));
        if (!(f instanceof IJavaStackFrame javaFrame)) throw new IllegalArgumentException("Java stack frame required");
        String expression = require(p, 4);
        if (expression.length() > 300 || expression.isBlank()) throw new IllegalArgumentException("Expression must be 1-300 characters");
        // OPEN-58: evaluation runs code in the program; the user decides in Eclipse ("Allow for this debugging session"
        // covers this launch only).
        String declaring = javaFrame.getDeclaringTypeName();
        var decision = consent.decide(new EvaluationConsent.Request(false, expression,
                declaring.substring(declaring.lastIndexOf('.') + 1) + "." + javaFrame.getMethodName() + ":"
                        + javaFrame.getLineNumber() + " (thread " + f.getThread().getName() + ")", evaluated));
        if (!decision.allowed()) return error(decision.message());
        // The question may have taken a while: the frame must still be the suspended one (OPEN-12).
        if (f.isTerminated() || !f.getThread().isSuspended())
            throw new IllegalArgumentException("The thread is no longer suspended at that frame; nothing was evaluated."
                    + " Call wait_for_stop, then evaluate again");
        // Evaluation can invoke methods and therefore have side effects: allowed by the user above (OPEN-58).
        IJavaDebugTarget debugTarget = (IJavaDebugTarget) f.getDebugTarget();
        IJavaProject project = null;
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (candidate.isOpen() && candidate.hasNature(JavaCore.NATURE_ID)) {
                IJavaProject jp = JavaCore.create(candidate);
                if (jp.findType(javaFrame.getDeclaringTypeName()) != null) {
                    if (project != null) throw new IllegalArgumentException("Ambiguous Java project for evaluation");
                    project = jp;
                }
            }
        }
        if (project == null) throw new IllegalArgumentException("Java project not found for evaluation");
        IAstEvaluationEngine engine = EvaluationManager.newAstEvaluationEngine(project, debugTarget);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IEvaluationResult> reference = new AtomicReference<>();
        try {
            engine.evaluate(expression, javaFrame, result -> {
                reference.set(result);
                latch.countDown();
            }, DebugEvent.EVALUATION, false);
            if (!latch.await(4, TimeUnit.SECONDS)) throw new IllegalArgumentException("Evaluation timed out");
            IEvaluationResult result = reference.get();
            if (result == null || result.hasErrors()) {
                return error(result == null ? "No result" : String.join("; ", result.getErrorMessages()));
            }
            return ok(",\"expression\":" + q(expression) + ",\"value\":"
                    + q(result.getValue() == null ? null : clipped(valueText(result.getValue()), 1000)));
        } finally {
            engine.dispose();
        }
    }

    private String control(String[] p) throws CoreException {
        ILaunch l = launch(require(p, 1));

        String action = require(p, 3);
        String investigationId = optionalInvestigation(p, 4);
        if (investigationId != null) {
            var investigation = investigations.require(investigationId);
            investigation.verifySession(l);
            if ("STOP".equals(action) && investigation.ownedLaunch() != l) {
                throw new IllegalStateException("Cannot terminate a reused/user-owned session");
            }
        } else if (investigations.ownerOf(l) == null) {
            // OPEN-40: without an investigation, only a session an open investigation started may be controlled;
            // never the user's own program (or one an investigation only borrowed).
            throw new IllegalStateException("Refused: this debug session was not started by an open agent investigation"
                    + " (it may be the user's own program). Use begin_debug_investigation, start or reuse the session in"
                    + " it, and pass its investigationId; a reused session can be stepped and resumed, never stopped");
        }
        ui.follow(l);
        if ("STOP".equals(action)) {
            target(l).terminate();
        } else {
            IThread t = thread(l, number(p, 2));
            // OPEN-12: Eclipse silently ignores a step or resume it cannot do (a running thread, a thread still held by
            // Eclipse at the program's start, STEP_RETURN in the outermost frame), so the agent would get "accepted" and
            // then wait for a stop that never comes. Refuse instead, and say what to do.
            String which = "thread " + number(p, 2) + " (" + t.getName() + ")";
            if ("PAUSE".equals(action)) {
                if (t.isSuspended() && t.canResume()) {
                    ui.showSuspended(l, false);
                    return ok(",\"action\":\"PAUSE\",\"accepted\":true,\"alreadySuspended\":true");
                }
                if (!t.canSuspend())
                    throw new IllegalArgumentException("PAUSE is not possible on " + which + " right now (it may still be"
                            + " starting); nothing was changed. Call wait_for_stop, then try again");
            } else {
                boolean can = switch (action) {
                    case "RESUME" -> t.canResume();
                    case "STEP_INTO" -> t.canStepInto();
                    case "STEP_OVER" -> t.canStepOver();
                    case "STEP_RETURN" -> t.canStepReturn();
                    default -> throw new IllegalArgumentException("Unknown control action");
                };
                if (!t.isSuspended())
                    throw new IllegalArgumentException(action + " needs a suspended thread, but " + which + " is running;"
                            + " nothing was changed. Call wait_for_stop (or PAUSE) first");
                if (!can)
                    throw new IllegalArgumentException(action + " needs a suspended thread stopped at a breakpoint, step"
                            + " or pause, which " + which + " is not right now (for example it is still starting, or"
                            + " STEP_RETURN in the outermost frame); nothing was changed. Call wait_for_stop first");
            }
            switch (action) {
                case "RESUME" -> t.resume();
                case "PAUSE" -> t.suspend();
                case "STEP_INTO" -> t.stepInto();
                case "STEP_OVER" -> t.stepOver();
                case "STEP_RETURN" -> t.stepReturn();
                default -> throw new IllegalArgumentException("Unknown control action");
            }
        }
        if (!"STOP".equals(action)) ui.showSuspended(l, false);
        return ok(",\"action\":" + q(action) + ",\"accepted\":true");
    }

/** Test-only check: the exact selected launch must belong to this NORMAL investigation. */
private String verifyOwnedFixtureLaunch(String[] p) throws CoreException {
    String investigationId = require(p, 1);
    String launchName = require(p, 2);
    var investigation = investigations.require(investigationId);
    if (investigation.mode() != DebugInvestigationPolicy.Mode.NORMAL)
        throw new IllegalStateException("HTTP fixture POST requires a NORMAL investigation");
    ILaunch selected = launch(launchName);
    investigation.verifySession(selected);
    if (investigation.ownedLaunch() != selected || selected.isTerminated())
        throw new IllegalStateException("Selected fixture launch is not active and investigation-owned");
    ILaunchConfiguration configuration = selected.getLaunchConfiguration();
    String project = configuration.getAttribute(
            IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
    String mainType = configuration.getAttribute(
            IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
    if (!"spring-boot-debugging".equals(project)
            || !"org.learningfromexperience.bootfixture.orders.OrdersApplication".equals(mainType))
        throw new IllegalStateException("Wrong fixture project or main type");
    return ok(",\"owned\":true,\"launchName\":" + q(launchName));
}

    private String getProcessOutput(String[] p) {
        ILaunch l = launch(require(p, 1));
        List<String> items = new ArrayList<>();
        for (IProcess process : l.getProcesses()) {
            IStreamsProxy streams = process.getStreamsProxy();
            String stdout = streams == null || streams.getOutputStreamMonitor() == null ? null
                    : streams.getOutputStreamMonitor().getContents();
            String stderr = streams == null || streams.getErrorStreamMonitor() == null ? null
                    : streams.getErrorStreamMonitor().getContents();
            String consoleText = null;
            int consoleChars = 0;
            var console = org.eclipse.debug.ui.DebugUITools.getConsole(process);
            if (console instanceof org.eclipse.debug.ui.console.IConsole processConsole) {
                var document = processConsole.getDocument();
                if (document != null) {
                    Object lock = document instanceof org.eclipse.jface.text.ISynchronizable sync ? sync.getLockObject() : null;
                    synchronized (lock == null ? document : lock) {
                        try {
                            consoleChars = document.getLength();
                            // Beginning and end, not just the beginning (see TextWindow).
                            consoleText = consoleChars <= TextWindow.HEAD + TextWindow.TAIL
                                    ? document.get(0, consoleChars)
                                    : document.get(0, TextWindow.HEAD)
                                        + TextWindow.marker(consoleChars - TextWindow.HEAD - TextWindow.TAIL)
                                        + document.get(consoleChars - TextWindow.TAIL, TextWindow.TAIL);
                        } catch (org.eclipse.jface.text.BadLocationException ignored) {
                            // The console may be trimmed while its output is read.
                        }
                    }
                }
            }
            items.add("{\"label\":" + q(process.getLabel()) + ",\"stdout\":" + q(TextWindow.headAndTail(stdout))
                    + ",\"stderr\":" + q(TextWindow.headAndTail(stderr)) + ",\"console\":" + q(consoleText)
                    + ",\"stdoutChars\":" + (stdout == null ? 0 : stdout.length())
                    + ",\"stderrChars\":" + (stderr == null ? 0 : stderr.length())
                    + ",\"consoleChars\":" + consoleChars + "}");
        }
        return ok(",\"processes\":[" + String.join(",", items) + "]");
    }
}
