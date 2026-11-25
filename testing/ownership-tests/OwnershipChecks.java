package org.learningfromexperience.agentdebug.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.ui.IStartup;

/** Opt-in, disposable-workspace test. Seeds real JDT user markers independently of the bridge. */
public final class OwnershipChecks implements IStartup {
    private static final String PKG = "org.learningfromexperience.debugfixtures.";
    private final Map<String, Object> results = new LinkedHashMap<>();
    private final List<IBreakpoint> markers = new ArrayList<>();
    private final List<ILaunch> sessions = new ArrayList<>();
    private final List<String> active = new ArrayList<>();
    private Bridge bridge;
    private ILaunchConfiguration config;

    public void earlyStartup() {
        String expected = System.getenv("AGENTDEBUG_OWNERSHIP_WORKSPACE");
        if (expected == null)
            return;
        Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        if (!workspace.equals(Path.of(expected))
                || "8765".equals(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765")))
            return;
        Job.create("Debugger ownership acceptance", monitor -> {
            try {
                run(workspace);
            } catch (Throwable ex) {
                results.put("failure", ex.toString());
            } finally {
                try {
                    cleanup();
                } catch (Throwable ex) {
                    results.put("cleanupFailure", ex.toString());
                }
                try {
                    Files.writeString(workspace.resolve("ownership-results.json"),
                            Json.stringify(results));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }).schedule();
    }

    private void run(Path workspace) throws Exception {
        Map<String, Object> info = null;
        for (int n = 0; n < 180 && info == null; n++) {
            try {
                bridge = new Bridge();
                info = call("GET_E2E_INFO");
            } catch (java.io.IOException ex) {
                Thread.sleep(1000);
            }
        }
        if (info == null || !Path.of((String) info.get("workspace")).equals(workspace))
            throw new IllegalStateException("Wrong bridge");
        results.put("build", info.get("bundleVersion"));
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("phase-one-debugging");
        if (!project.getFile(".agentdebug-fixture").exists())
            throw new IllegalStateException("Disposable fixture required");
        var manager = DebugPlugin.getDefault().getLaunchManager();
        var source = project
                .getFile("src/main/java/org/learningfromexperience/debugfixtures/LongRunningApplication.java");
        int line = 7;
        String type = PKG + "LongRunningApplication";
        if (JDIDebugModel.lineBreakpointExists(source, type, line) != null)
            throw new IllegalStateException("Fixture marker already exists");
        var user = JDIDebugModel.createLineBreakpoint(source, type, line, -1, -1, 0, true, new HashMap<>());
        markers.add(user);
        String exception = "java.lang.ArithmeticException";
        var userException = JDIDebugModel.createExceptionBreakpoint(project, exception, true, false, false, true,
                new HashMap<>());
        markers.add(userException);
        var wc = manager.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION)
                .newInstance(null, manager.generateLaunchConfigurationName("Ownership test user launch"));
        wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, project.getName());
        wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, type);
        config = wc.doSave();
        ILaunch original = config.launch(ILaunchManager.DEBUG_MODE, null);
        sessions.add(original);
        String name = config.getName();
        awaitSuspended(name);
        String owner = begin("NORMAL"), borrower = begin("NORMAL");
        boolean reused = Boolean.TRUE.equals(call("START_DEBUG", name, borrower).get("reused"));
        check("pre-existing launch reused", reused);
        check("user line marker recognized", "user".equals(call("SET_BREAKPOINT", type, line, borrower).get("owner")));
        check("user condition collision rejected",
                rejected("SET_CONDITIONAL_BREAKPOINT", type, line, "false", borrower));
        check("user line marker deletion refused",
                Boolean.FALSE.equals(call("REMOVE_BREAKPOINT", type, line).get("removed")));
        check("user exception collision rejected",
                rejected("SET_EXCEPTION_BREAKPOINT", exception, false, true, borrower));
        check("user exception deletion refused",
                Boolean.FALSE.equals(call("REMOVE_EXCEPTION_BREAKPOINT", exception).get("removed")));
        check("borrower cannot stop", rejected("CONTROL_DEBUG", name, 0, "STOP", borrower));
        check("borrower cannot restart", rejected("RESTART_DEBUG", name, borrower));
        String otherType = PKG + "StepFilterApplication";
        requireNewMarker(otherType, 9, owner);
        check("owned marker created", true);
        String other = startOwned(project.getName(), otherType, owner);
        awaitSuspended(other);
        check("wrong investigation cannot resume other session",
                rejected("CONTROL_DEBUG", other, 0, "RESUME", borrower));
        int before = manager.getLaunchConfigurations().length;
        check("wrong JUnit target rejected", rejected("START_JUNIT_TEST", project.getName(),
                PKG + "OrderCalculatorTest", "appliesTenPercentDiscount", borrower));
        check("rejection creates no launch configuration", before == manager.getLaunchConfigurations().length);
        check("invalid investigation rejected", rejected("CONTROL_DEBUG", name, 0, "STOP", "invalid-test-id"));
        check("invalid mode rejected", rejected("BEGIN_INVESTIGATION", "INVALID"));
        check("unknown JUnit method rejected",
                rejected("START_JUNIT_TEST", project.getName(), PKG + "OrderCalculatorTest", "noSuchMethod", owner));
        check("invalid variable path rejected", rejected("EXPAND_VARIABLE", other, 0, 0, "values.clear()", 0, 10));
        check("out-of-range variable page rejected", rejected("EXPAND_VARIABLE", other, 0, 0, "values", 0, 51));
        check("malformed step filter rejected", rejected("SET_STEP_FILTERS", other, true, "java.*!"));
        check("too many step filters rejected", rejected("SET_STEP_FILTERS", other, true,
                String.join(",", java.util.Collections.nCopies(31, "java.*"))));
        String markerType = PKG + "NestedObjectApplication";
        var extraFile = project
                .getFile("src/main/java/org/learningfromexperience/debugfixtures/NestedObjectApplication.java");
        requireNewMarker(markerType, 12, owner);
        check("temporary owned marker created", true);
        var prior = JDIDebugModel.lineBreakpointExists(extraFile, markerType, 12);
        prior.delete();
        var userReplacement = JDIDebugModel.createLineBreakpoint(extraFile, markerType, 12, -1, -1, 0, true,
                new HashMap<>());
        markers.add(userReplacement);
        finish(owner);
        check("user replacement at old marker location survives cleanup", userReplacement.getMarker().exists());
        check("NORMAL removes its own marker",
                JDIDebugModel.lineBreakpointExists(
                        project.getFile(
                                "src/main/java/org/learningfromexperience/debugfixtures/StepFilterApplication.java"),
                        otherType, 9) == null);
        check("NORMAL terminates only its own launch", rejected("GET_DEBUG_STATE", other) && !original.isTerminated());
        check("completed ID rejected", rejected("FINISH_INVESTIGATION", owner));
        finish(borrower);
        check("reused session stays suspended", Boolean.TRUE.equals(call("GET_DEBUG_STATE", name).get("suspended")));
        check("user markers retain settings",
                user.getMarker().exists() && user.isEnabled() && !user.isConditionEnabled()
                        && userException.getMarker().exists() && userException.isCaught()
                        && !userException.isUncaught());
        ILaunch twin = config.launch(ILaunchManager.DEBUG_MODE, null);
        sessions.add(twin);
        String ambiguous = begin("NORMAL");
        check("same-name concurrent launches rejected", rejected("START_DEBUG", name, ambiguous));
        check("ambiguous state lookup rejected", rejected("GET_DEBUG_STATE", name));
        finish(ambiguous);
        twin.terminate();
        String stale = begin("CONTROLLED");
        call("START_DEBUG", name, stale);
        original.terminate();
        ILaunch replacement = config.launch(ILaunchManager.DEBUG_MODE, null);
        sessions.add(replacement);
        awaitSuspended(name);
        check("old investigation cannot control same-name replacement",
                rejected("CONTROL_DEBUG", name, 0, "PAUSE", stale));
        check("old investigation cannot finalize against replacement", rejected("FINISH_INVESTIGATION", stale));
        String controlled = begin("CONTROLLED");
        requireNewMarker(otherType, 9, controlled);
        startOwned(project.getName(), otherType, controlled);
        awaitSuspended(other);
        for (ILaunch l : manager.getLaunches())
            if (!l.isTerminated() && other.equals(l.getLaunchConfiguration().getName()))
                sessions.add(l);
        check("CONTROLLED retains suspended launch", "KEEP_SUSPENDED".equals(finish(controlled).get("action"))
                && Boolean.TRUE.equals(call("GET_DEBUG_STATE", other).get("suspended")));
        var retained = JDIDebugModel.lineBreakpointExists(
                project.getFile("src/main/java/org/learningfromexperience/debugfixtures/StepFilterApplication.java"),
                otherType, 9);
        check("CONTROLLED retains its marker", retained != null);
        if (retained != null)
            markers.add(retained);
    }

    private void requireNewMarker(String type, int line, String id) throws Exception {
        if (!Boolean.TRUE.equals(call("SET_BREAKPOINT", type, line, id).get("created")))
            throw new IllegalStateException("Ownership fixture location already occupied: " + type + ":" + line);
    }

    private String startOwned(String project, String type, String id) throws Exception {
        var reply = call("START_JAVA_APPLICATION", project, type, id);
        if (!Boolean.FALSE.equals(reply.get("reusedSession")))
            throw new IllegalStateException("An existing launch occupies the ownership fixture: " + type);
        return (String) reply.get("launchName");
    }

    private void awaitSuspended(String name) throws Exception {
        for (int i = 0; i < 150; i++) {
            var threads = bridge.call("GET_THREADS", true, name);
            if (threads.get("threads") instanceof List<?> list)
                for (Object o : list) {
                    if (o instanceof Map<?, ?> t && "main".equals(t.get("name"))
                            && Boolean.TRUE.equals(t.get("suspended"))) {
                        var stack = bridge.call("GET_STACK", true, name,
                                t.get("index") instanceof Number n ? n.intValue() : 0);
                        if (stack.get("frames") instanceof List<?> frames && !frames.isEmpty()
                                && frames.get(0) instanceof Map<?, ?> frame
                                && String.valueOf(frame.get("className")).startsWith(PKG))
                            return;
                    }
                }
            Thread.sleep(200);
        }
        throw new AssertionError("Expected a suspended fixture frame in " + name);
    }

    private Map<String, Object> call(String command, Object... args) throws Exception {
        return bridge.call(command, false, args);
    }

    private boolean rejected(String command, Object... args) throws Exception {
        return "error".equals(bridge.call(command, true, args).get("status"));
    }

    private String begin(String mode) throws Exception {
        String id = (String) call("BEGIN_INVESTIGATION", mode).get("investigationId");
        active.add(id);
        return id;
    }

    private Map<String, Object> finish(String id) throws Exception {
        var reply = call("FINISH_INVESTIGATION", id);
        active.remove(id);
        return reply;
    }

    private void check(String name, boolean ok) {
        results.put(name, ok ? "PASS" : "FAIL");
    }

    private void cleanup() throws Exception {
        for (String id : active)
            bridge.call("FINISH_INVESTIGATION", true, id);
        for (ILaunch launch : sessions)
            if (!launch.isTerminated())
                launch.terminate();
        for (IBreakpoint bp : markers)
            if (bp.getMarker() != null && bp.getMarker().exists())
                bp.delete();
        if (config != null && config.exists())
            config.delete();
    }
}
