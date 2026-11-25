package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.learningfromexperience.eclipse.agentdebug.debug.EclipseAgentOperations.q;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

/**
 * Test support for the user-resource safety tests (OPEN-02, OPEN-03): creates things that belong to the USER, the
 * way a person would create them in Eclipse (a line breakpoint, an exception breakpoint, a saved launch configuration
 * with arguments and an environment variable, and a debug run of it), so the integration tests can prove that agent
 * investigations never take them over, change or remove them. Breakpoints are created with JDT directly, without the
 * agent's ownership attribute, exactly like breakpoints set in the editor.
 *
 * <p>Answers only in the test Eclipse ({@code AGENTDEBUG_E2E=1}, see {@code EclipseAgentOperations.TEST_SUPPORT}),
 * only for the disposable fixture project {@code phase-one-debugging} (it must carry the {@code .agentdebug-fixture}
 * marker), and {@link #cleanup()} removes everything it created. Replaces the separate in-Eclipse harness
 * {@code testing/ownership-tests} (started by PowerShell), whose 32 checks now run as JUnit tests.
 *
 * <p>Commands (arguments as the bridge sends them: text in URL-safe Base64, numbers in decimal):
 * <ul>
 * <li>{@code TEST_USER_SEED type line exception}: the user's line breakpoint at {@code type:line}, the user's
 *     exception breakpoint (caught only), the user's launch configuration of {@code type} with program and VM
 *     arguments and an environment variable, started in debug mode. Refuses when a breakpoint is already there.</li>
 * <li>{@code TEST_USER_STATE}: what is left of all that, with every setting.</li>
 * <li>{@code TEST_USER_REPLACE_BREAKPOINT type line}: the user deletes the breakpoint at {@code type:line} (the
 *     agent's) and sets their own there.</li>
 * <li>{@code TEST_USER_LAUNCH_AGAIN}: the user starts their configuration once more.</li>
 * <li>{@code TEST_TERMINATE_LAUNCH name which}: terminates the live debug launches of configuration {@code name}:
 *     {@code oldest}, {@code newest} or {@code all} (to end what a test deliberately left running).</li>
 * <li>{@code TEST_USER_CLEANUP}: removes everything this class created.</li>
 * </ul>
 *
 * @author Ayan Dutta
 */
final class TestUserResources {
    static final String PROJECT = "phase-one-debugging";
    static final String PROGRAM_ARGUMENTS = "--user-argument kept";
    static final String VM_ARGUMENTS = "-Duser.setting=kept";
    static final String ENVIRONMENT_NAME = "USER_SETTING";
    static final String ENVIRONMENT_VALUE = "kept";
    /**
     * Marks what this class created (an extra marker / configuration attribute, ignored by the ownership logic, which
     * only knows its own agent attribute), so {@link #cleanup()} also finds leftovers of a test run that ended with
     * Eclipse, when the fields below are empty again.
     */
    static final String MARK = "org.learningfromexperience.agentdebug.testUserResource";

    private static IJavaLineBreakpoint lineBreakpoint;
    private static IJavaExceptionBreakpoint exceptionBreakpoint;
    private static IJavaLineBreakpoint replacement;
    private static ILaunchConfiguration configuration;
    private static String mainType;
    private static final List<ILaunch> launches = new ArrayList<>();

    private TestUserResources() { }

    static synchronized String execute(String[] p) throws Exception {
        return switch (p[0]) {
            case "TEST_USER_SEED" -> seed(text(p, 1), number(p, 2), text(p, 3));
            case "TEST_USER_STATE" -> state();
            case "TEST_USER_REPLACE_BREAKPOINT" -> replace(text(p, 1), number(p, 2));
            case "TEST_USER_LAUNCH_AGAIN" -> launchAgain();
            case "TEST_TERMINATE_LAUNCH" -> terminate(text(p, 1), text(p, 2));
            case "TEST_USER_CLEANUP" -> cleanup();
            default -> throw new IllegalArgumentException("Unknown test command " + p[0]);
        };
    }

    private static String seed(String type, int line, String exception) throws Exception {
        if (configuration != null || lineBreakpoint != null)
            throw new IllegalStateException("User resources already seeded; run TEST_USER_CLEANUP first");
        IProject project = fixtureProject();
        IFile source = source(project, type);
        if (JDIDebugModel.lineBreakpointExists(source, type, line) != null)
            throw new IllegalStateException("A breakpoint already exists at " + type + ":" + line);
        for (IBreakpoint existing : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints())
            if (existing instanceof IJavaExceptionBreakpoint e && exception.equals(e.getTypeName()))
                throw new IllegalStateException("An exception breakpoint for " + exception + " already exists");
        lineBreakpoint = JDIDebugModel.createLineBreakpoint(source, type, line, -1, -1, 0, true, marked());
        exceptionBreakpoint = JDIDebugModel.createExceptionBreakpoint(project, exception, true, false, false, true,
                marked());
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        var copy = manager.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION)
                .newInstance(null, manager.generateLaunchConfigurationName("User launch for ownership tests"));
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, PROJECT);
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, type);
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, PROGRAM_ARGUMENTS);
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, VM_ARGUMENTS);
        copy.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of(ENVIRONMENT_NAME, ENVIRONMENT_VALUE));
        copy.setAttribute(MARK, true);
        configuration = copy.doSave();
        mainType = type;
        launches.add(configuration.launch(ILaunchManager.DEBUG_MODE, null));
        return ok(",\"configurationName\":" + q(configuration.getName()));
    }

    private static String state() throws CoreException {
        StringBuilder b = new StringBuilder();
        b.append(",\"lineBreakpoint\":").append(line(lineBreakpoint));
        b.append(",\"replacementBreakpoint\":").append(line(replacement));
        if (exceptionBreakpoint == null) b.append(",\"exceptionBreakpoint\":null");
        else {
            boolean exists = exists(exceptionBreakpoint);
            b.append(",\"exceptionBreakpoint\":{\"exists\":").append(exists);
            if (exists) b.append(",\"enabled\":").append(exceptionBreakpoint.isEnabled())
                    .append(",\"caught\":").append(exceptionBreakpoint.isCaught())
                    .append(",\"uncaught\":").append(exceptionBreakpoint.isUncaught());
            b.append('}');
        }
        if (configuration == null) b.append(",\"configuration\":null");
        else {
            boolean exists = configuration.exists();
            b.append(",\"configuration\":{\"name\":").append(q(configuration.getName())).append(",\"exists\":").append(exists);
            if (exists) {
                Map<String, String> environment = configuration.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES,
                        (Map<String, String>) null);
                b.append(",\"mainType\":").append(q(configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "")))
                        .append(",\"programArguments\":").append(q(configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, "")))
                        .append(",\"vmArguments\":").append(q(configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, "")))
                        .append(",\"environment\":").append(q(environment == null ? "" : String.valueOf(new java.util.TreeMap<>(environment))))
                        .append(",\"expectedMainType\":").append(q(mainType));
            }
            b.append('}');
        }
        int live = 0;
        for (ILaunch launch : launches) if (!launch.isTerminated()) live++;
        b.append(",\"userLaunchesStarted\":").append(launches.size()).append(",\"userLaunchesLive\":").append(live);
        return ok(b.toString());
    }

    private static String replace(String type, int line) throws CoreException {
        IFile source = source(fixtureProject(), type);
        var existing = JDIDebugModel.lineBreakpointExists(source, type, line);
        if (existing == null) throw new IllegalStateException("No breakpoint at " + type + ":" + line + " to replace");
        existing.delete();
        replacement = JDIDebugModel.createLineBreakpoint(source, type, line, -1, -1, 0, true, marked());
        return ok(",\"replaced\":true");
    }

    private static String launchAgain() throws CoreException {
        if (configuration == null) throw new IllegalStateException("Nothing seeded");
        launches.add(configuration.launch(ILaunchManager.DEBUG_MODE, null));
        return ok(",\"userLaunchesStarted\":" + launches.size());
    }

    private static String terminate(String name, String which) throws CoreException {
        List<ILaunch> live = new ArrayList<>();
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            var c = launch.getLaunchConfiguration();
            if (!launch.isTerminated() && ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())
                    && c != null && name.equals(c.getName())) live.add(launch);
        }
        List<ILaunch> chosen = switch (which) {
            case "oldest" -> live.isEmpty() ? List.of() : List.of(live.get(0));
            case "newest" -> live.isEmpty() ? List.of() : List.of(live.get(live.size() - 1));
            case "all" -> live;
            default -> throw new IllegalArgumentException("which must be oldest, newest or all");
        };
        for (ILaunch launch : chosen) launch.terminate();
        return ok(",\"terminated\":" + chosen.size());
    }

    static synchronized String cleanup() {
        Map<String, Object> problems = new LinkedHashMap<>();
        for (ILaunch launch : launches) {
            try { if (!launch.isTerminated()) launch.terminate(); }
            catch (Exception e) { problems.put("launch", e.toString()); }
        }
        for (IBreakpoint bp : new IBreakpoint[] {lineBreakpoint, exceptionBreakpoint, replacement}) {
            try { if (exists(bp)) bp.delete(); }
            catch (Exception e) { problems.put("breakpoint", e.toString()); }
        }
        try { if (configuration != null && configuration.exists()) configuration.delete(); }
        catch (Exception e) { problems.put("configuration", e.toString()); }
        // Leftovers of an earlier run whose Eclipse was closed before its cleanup (the fields above were empty then).
        try {
            for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints())
                if (exists(bp) && Boolean.TRUE.equals(bp.getMarker().getAttribute(MARK))) bp.delete();
            for (ILaunchConfiguration c : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations())
                if (c.getAttribute(MARK, false)) {
                    for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches())
                        if (!launch.isTerminated() && c.equals(launch.getLaunchConfiguration())) launch.terminate();
                    c.delete();
                }
        } catch (Exception e) { problems.put("leftovers", e.toString()); }
        launches.clear();
        lineBreakpoint = null;
        exceptionBreakpoint = null;
        replacement = null;
        configuration = null;
        mainType = null;
        return problems.isEmpty() ? ok(",\"cleaned\":true")
                : "{\"status\":\"error\",\"message\":" + q("Cleanup incomplete: " + problems) + "}";
    }

    // ------------------------------------------------------------------ helpers

    private static IProject fixtureProject() {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (!project.isOpen() || !project.getFile(".agentdebug-fixture").exists())
            throw new IllegalStateException("The disposable fixture project " + PROJECT + " is required");
        return project;
    }

    private static IFile source(IProject project, String type) {
        IFile file = project.getFile("src/main/java/" + type.replace('.', '/') + ".java");
        if (!file.exists()) throw new IllegalArgumentException("Source of " + type + " not found in " + PROJECT);
        return file;
    }

    private static boolean exists(IBreakpoint bp) {
        return bp != null && bp.getMarker() != null && bp.getMarker().exists();
    }

    private static String line(IJavaLineBreakpoint bp) throws CoreException {
        if (bp == null) return "null";
        boolean exists = exists(bp);
        StringBuilder b = new StringBuilder("{\"exists\":").append(exists);
        if (exists) b.append(",\"typeName\":").append(q(bp.getTypeName()))
                .append(",\"lineNumber\":").append(bp.getLineNumber())
                .append(",\"enabled\":").append(bp.isEnabled())
                .append(",\"conditionEnabled\":").append(bp.isConditionEnabled());
        return b.append('}').toString();
    }

    private static Map<String, Object> marked() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MARK, Boolean.TRUE);
        return attributes;
    }

    private static String ok(String fields) { return "{\"status\":\"success\"" + fields + "}"; }

    private static String text(String[] p, int index) {
        if (p.length <= index || p[index].isEmpty()) throw new IllegalArgumentException("Missing argument " + index);
        return new String(Base64.getUrlDecoder().decode(p[index]), StandardCharsets.UTF_8);
    }

    private static int number(String[] p, int index) {
        if (p.length <= index) throw new IllegalArgumentException("Missing numeric argument " + index);
        return Integer.parseInt(p[index]);
    }
}
