package org.learningfromexperience.eclipse.agentdebug.debug;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
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
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.eval.EvaluationManager;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;
import org.eclipse.jdt.debug.eval.IEvaluationResult;

/** Authenticated bridge operations. Selects sessions explicitly and never assumes one of many. */
public final class EclipseAgentOperations {
    private final EclipseBreakpointService breakpoints = new EclipseBreakpointService();

    public String execute(String command) {
        try {
            String[] p = command.split(" ", -1);
            return switch (p[0]) {
                case "GET_E2E_INFO" -> "{\"status\":\"success\",\"fixtureProtocol\":\"calculator-v2\"}";
                case "PREPARE_SMOKE_TEST" -> new SmokeTestProject().prepare();
                case "LIST_BREAKPOINTS" -> listBreakpoints();
                case "SET_BREAKPOINT" -> setBreakpoint(p);
                case "REMOVE_BREAKPOINT" -> removeBreakpoint(p);
                case "LIST_RUN_CONFIGURATIONS" -> listRunConfigurations();
                case "START_DEBUG" -> startDebug(p);
                case "GET_DEBUG_STATE" -> getDebugState(p);
                case "GET_THREADS" -> getThreads(p);
                case "GET_STACK" -> getStack(p);
                case "GET_VARIABLES" -> getVariables(p);
                case "EVALUATE_EXPRESSION" -> evaluateExpression(p);
                case "CONTROL_DEBUG" -> control(p);
                case "GET_PROCESS_OUTPUT" -> getProcessOutput(p);
                default -> error("Unknown command");
            };
        } catch (Exception e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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

    private String listBreakpoints() throws CoreException {
        var all = breakpoints.listBreakpoints();
        List<String> items = new ArrayList<>();
        for (var bp : all) items.add("{\"typeName\":" + q(bp.typeName())
                + ",\"lineNumber\":" + bp.lineNumber() + ",\"projectName\":" + q(bp.projectName())
                + ",\"enabled\":" + bp.enabled() + ",\"owner\":" + q(bp.owner()) + "}");
        return ok(",\"breakpoints\":[" + String.join(",", items) + "]");
    }

    private String setBreakpoint(String[] p) throws CoreException {
        var bp = breakpoints.setBreakpoint(require(p, 1), number(p, 2));
        return ok(",\"typeName\":" + q(bp.typeName()) + ",\"lineNumber\":" + bp.lineNumber()
                + ",\"projectName\":" + q(bp.projectName()) + ",\"created\":" + bp.created()
                + ",\"owner\":" + q(bp.owner()));
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

    private static ILaunchManager manager() { return DebugPlugin.getDefault().getLaunchManager(); }

    private String startDebug(String[] p) throws CoreException {
        String name = require(p, 1);
        ILaunchConfiguration found = null;
        for (var c : manager().getLaunchConfigurations()) {
            if (c.getName().equals(name) && c.supportsMode(ILaunchManager.DEBUG_MODE)) {
                if (found != null) throw new IllegalArgumentException("Ambiguous launch configuration name");
                found = c;
            }
        }
        if (found == null) throw new IllegalArgumentException("Debug-capable launch configuration not found");
        // Reuse the existing active launch for this configuration by name.
        for (var launch : manager().getLaunches()) {
            if (launch.getLaunchConfiguration() != null
                    && launch.getLaunchConfiguration().equals(found) && !launch.isTerminated()
                    && ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())) {
                return ok(",\"reused\":true,\"launchName\":" + q(name));
            }
        }
        found.launch(ILaunchManager.DEBUG_MODE, null);
        return ok(",\"reused\":false,\"launchName\":" + q(name));
    }

    private static ILaunch launch(String name) {
        ILaunch match = null;
        for (var current : manager().getLaunches()) {
            if (current.isTerminated() || !ILaunchManager.DEBUG_MODE.equals(current.getLaunchMode())) continue;
            var c = current.getLaunchConfiguration();
            if (c != null && c.getName().equals(name)) {
                if (match != null) throw new IllegalArgumentException("Multiple active launches share that name");
                match = current;
            }
        }
        if (match == null) throw new IllegalArgumentException("Active debug launch not found: " + name);
        return match;
    }

    private static IDebugTarget target(ILaunch l) {
        IDebugTarget chosen = null;
        for (var t : l.getDebugTargets()) {
            if (!t.isTerminated() && t instanceof IJavaDebugTarget) {
                if (chosen != null) throw new IllegalArgumentException("Multiple Java targets; operation is ambiguous");
                chosen = t;
            }
        }
        if (chosen == null) throw new IllegalArgumentException("No active Java debug target yet");
        return chosen;
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
        if (index < 0 || index >= all.length) throw new IllegalArgumentException("Thread index out of range; call get_threads again");
        return all[index];
    }

    private static IStackFrame frame(IThread thread, int index) throws CoreException {
        if (!thread.isSuspended()) throw new IllegalArgumentException("Thread must be suspended");
        IStackFrame[] all = thread.getStackFrames();
        if (index < 0 || index >= all.length) throw new IllegalArgumentException("Frame index out of range; call get_stack again");
        return all[index];
    }

    private String getStack(String[] p) throws CoreException {
        IThread t = thread(launch(require(p, 1)), number(p, 2));
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
            if (++count > 100) break;
            IValue value = v.getValue();
            items.add("{\"name\":" + q(v.getName()) + ",\"type\":" + q(v.getReferenceTypeName())
                    + ",\"value\":" + q(value == null ? null : clipped(value.getValueString(), 500)) + "}");
        }
        return ok(",\"variables\":[" + String.join(",", items) + "]");
    }

    private static String clipped(String value, int length) {
        return value == null ? null : value.substring(0, Math.min(value.length(), length));
    }

    private String evaluateExpression(String[] p) throws Exception {
        String name = require(p, 1);
        IStackFrame f = frame(thread(launch(name), number(p, 2)), number(p, 3));
        if (!(f instanceof IJavaStackFrame javaFrame)) throw new IllegalArgumentException("Java stack frame required");
        String expression = require(p, 4);
        if (expression.length() > 300 || expression.isBlank()) throw new IllegalArgumentException("Expression must be 1-300 characters");
        // Evaluation can invoke methods and therefore have side effects; the agent must request consent.
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
                    + q(result.getValue() == null ? null : clipped(result.getValue().getValueString(), 1000)));
        } finally {
            engine.dispose();
        }
    }

    private String control(String[] p) throws CoreException {
        ILaunch l = launch(require(p, 1));
        String action = require(p, 3);
        if ("STOP".equals(action)) {
            target(l).terminate();
        } else {
            IThread t = thread(l, number(p, 2));
            switch (action) {
                case "RESUME" -> t.resume();
                case "PAUSE" -> t.suspend();
                case "STEP_INTO" -> t.stepInto();
                case "STEP_OVER" -> t.stepOver();
                case "STEP_RETURN" -> t.stepReturn();
                default -> throw new IllegalArgumentException("Unknown control action");
            }
        }
        return ok(",\"action\":" + q(action) + ",\"accepted\":true");
    }

    private String getProcessOutput(String[] p) {
        ILaunch l = launch(require(p, 1));
        List<String> items = new ArrayList<>();
        for (IProcess process : l.getProcesses()) {
            IStreamsProxy streams = process.getStreamsProxy();
            if (streams == null) continue;
            items.add("{\"label\":" + q(process.getLabel())
                    + ",\"stdout\":" + q(streams.getOutputStreamMonitor() == null ? null
                            : clipped(streams.getOutputStreamMonitor().getContents(), 4000))
                    + ",\"stderr\":" + q(streams.getErrorStreamMonitor() == null ? null
                            : clipped(streams.getErrorStreamMonitor().getContents(), 4000)) + "}");
        }
        return ok(",\"processes\":[" + String.join(",", items) + "]");
    }
}
