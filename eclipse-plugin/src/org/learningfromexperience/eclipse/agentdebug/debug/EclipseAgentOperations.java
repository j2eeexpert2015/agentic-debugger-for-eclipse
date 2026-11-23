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

/**
 * Exposes authenticated Eclipse Java debugging operations to the local MCP bridge.
 *
 * @author Ayan Dutta
 */
public final class EclipseAgentOperations {
    private final EclipseBreakpointService breakpoints = new EclipseBreakpointService();
    private final AgentDebugUiCoordinator ui = new AgentDebugUiCoordinator();
    private final DebugInvestigationPolicy investigations = new DebugInvestigationPolicy();
    /** Records real debugger stops for WAIT_FOR_STOP (event-based, no polling). */
    private final DebugStopTracker stops = new DebugStopTracker();

    /** Remove the UI and stop event listeners when the bridge shuts down. */
    public void close() {
        ui.close();
        stops.close();
    }

    public String execute(String command) {
        try {
            String[] p = command.split(" ", -1);
            return switch (p[0]) {
                case "GET_E2E_INFO" -> buildIdentity();
                case "PREPARE_SMOKE_TEST" -> new SmokeTestProject().prepare();
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
                case "VERIFY_OWNED_FIXTURE_LAUNCH" -> verifyOwnedFixtureLaunch(p);
                case "GET_PROCESS_OUTPUT" -> getProcessOutput(p);
                default -> error("Unknown command");
            };
        } catch (Exception e) {
            return error(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    /** Identifies the exact loaded classes, not merely the installed feature label.
     * Read-only and deliberately available to the authenticated local test runner.
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
        var investigation = investigations.begin(mode);
        return ok(",\"investigationId\":" + q(investigation.id())
                + ",\"mode\":" + q(investigation.mode().name()));
    }

    /** Finalizes only work belonging to the explicitly identified investigation. */
    private String finishInvestigation(String[] p) throws CoreException {
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
        if (investigation.mode() == DebugInvestigationPolicy.Mode.NORMAL) {
            Object owned = investigation.ownedLaunch();
            if (owned instanceof ILaunch launch && !launch.isTerminated()) {
                launch.terminate();
            }
            for (var bp : investigation.breakpoints()) {
                breakpoints.removeInvestigationBreakpoint(bp.typeName(), bp.lineNumber(), id);
            }
            for (var exception : investigation.exceptions()) {
                breakpoints.removeInvestigationException(exception, id);
            }
        }
        investigations.finish(id);
        return ok(",\"investigationId\":" + q(id)
                + ",\"mode\":" + q(investigation.mode().name())
                + ",\"action\":" + q(action)
                + ",\"launchName\":" + q(investigation.launchName())
                + ",\"ownedSession\":" + (investigation.ownedLaunch() != null)
                + ",\"completed\":true");
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
        var result = breakpoints.setExceptionBreakpoint(type, caught, uncaught, id);
        if (investigation != null && result.created()) investigation.recordException(type);
        if (!result.created()) {
            return error("An exception breakpoint already exists for " + type
                    + "; existing markers are never modified");
        }
        return ok(",\"exceptionName\":" + q(type) + ",\"caught\":" + caught
                + ",\"uncaught\":" + uncaught + ",\"created\":true");
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
        return ok(",\"removed\":" + breakpoints.removeAgentExceptionBreakpoint(require(p, 1)));
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

    /** Use Eclipse's JUnit launch delegate, without coupling the plugin to JUnit internals. */
    private String startJUnitTest(String[] p) throws CoreException {
        String projectName = require(p, 1);
        String typeName = require(p, 2);
        String methodName = p.length > 3 ? decoded(p[3]) : "";
        String id = optionalInvestigation(p, 4);
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
        if (investigation != null) investigation.verifyNewLaunch(workingCopy.getName());
        var config = workingCopy.doSave();
        ILaunch launched;
        try { launched = config.launch(ILaunchManager.DEBUG_MODE, null); }
        catch (CoreException failure) { config.delete(); throw failure; }
        if (investigation != null) investigation.recordLaunch(config.getName(), launched, true);
        ui.follow(launched);
        return ok(",\"launchName\":" + q(config.getName())
                + ",\"projectName\":" + q(projectName)
                + ",\"typeName\":" + q(typeName) + ",\"methodName\":" + q(methodName));
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
        return ok(",\"launchName\":" + q(name) + ",\"restarted\":true");
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
        IVariable[] children = value.getVariables();
        List<String> items = new ArrayList<>();
        for (int i = offset; i < Math.min(children.length, offset + limit); i++) {
            IVariable child = children[i];
            IValue item = child.getValue();
            items.add("{\"name\":" + q(child.getName()) + ",\"type\":"
                    + q(child.getReferenceTypeName()) + ",\"value\":"
                    + q(item == null ? null : clipped(item.getValueString(), 200))
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
                    + ",\"createdConfiguration\":" + created + ",\"reusedSession\":true");
        }
        if (investigation != null) investigation.verifyNewLaunch(name);
        ILaunch started = config.launch(ILaunchManager.DEBUG_MODE, null);
        if (investigation != null) investigation.recordLaunch(name, started, true);
        ui.follow(started);
        return ok(",\"launchName\":" + q(name)
                + ",\"createdConfiguration\":" + created + ",\"reusedSession\":false");
    }

    private static ILaunchManager manager() { return DebugPlugin.getDefault().getLaunchManager(); }

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
            return ok(",\"reused\":true,\"launchName\":" + q(name));
        }
        if (investigation != null) investigation.verifyNewLaunch(name);
        ILaunch started = found.launch(ILaunchManager.DEBUG_MODE, null);
        if (investigation != null) investigation.recordLaunch(name, started, true);
        ui.follow(started);
        return ok(",\"reused\":false,\"launchName\":" + q(name));
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
        ILaunch launch = launchForWait(name);
        DebugStopTracker.Outcome outcome = stops.waitForStop(launch, timeout);
        if (outcome.stop() == null) {
            return ok(",\"stopped\":false,\"reason\":" + q(outcome.terminated() ? "TERMINATED" : "TIMEOUT")
                    + ",\"launchName\":" + q(name));
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
                        if (++count > 50) break;
                        String value;
                        try {
                            IValue raw = v.getValue();
                            value = raw == null ? null : clipped(raw.getValueString(), 300);
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
        String investigationId = optionalInvestigation(p, 4);
        if (investigationId != null) {
            var investigation = investigations.require(investigationId);
            investigation.verifySession(l);
            if ("STOP".equals(action) && investigation.ownedLaunch() != l) {
                throw new IllegalStateException("Cannot terminate a reused/user-owned session");
            }
        }
        ui.follow(l);
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
