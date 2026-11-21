package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;

/** Creates Java line breakpoints in projects of the current Eclipse workspace.
 *
 * @author Ayan Dutta
 */
public class EclipseBreakpointService {

    public static final String INVESTIGATION_ATTRIBUTE =
            "org.learningfromexperience.eclipse.agentdebug.investigationId";

    public static final String TRACEPOINT_ATTRIBUTE =
            "org.learningfromexperience.eclipse.agentdebug.tracepoint";

    public static final String OWNER_ATTRIBUTE =
            "org.learningfromexperience.eclipse.agentdebug.owner";

    public record BreakpointResult(String typeName, int lineNumber,
                                   String projectName, boolean created,
                                   String owner) {}

    public record ExceptionBreakpointResult(String exceptionName, boolean created, String owner) {}

    /** Exceptions are workspace-global; keep user markers unchanged. */
    public ExceptionBreakpointResult setExceptionBreakpoint(String exceptionName, boolean caught,
            boolean uncaught, String investigationId) throws CoreException {
        if (exceptionName == null || !exceptionName.matches(
                "[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            throw new IllegalArgumentException("Provide a fully qualified exception type");
        }
        if (!caught && !uncaught) throw new IllegalArgumentException("Enable caught and/or uncaught");
        for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
            if (bp instanceof IJavaExceptionBreakpoint javaBp
                    && exceptionName.equals(javaBp.getTypeName())) {
                return new ExceptionBreakpointResult(exceptionName, false,
                        "agent".equals(bp.getMarker().getAttribute(OWNER_ATTRIBUTE, "user"))
                                ? "agent" : "user");
            }
        }
        Map<String,Object> attrs = new HashMap<>();
        attrs.put(OWNER_ATTRIBUTE, "agent");
        if (investigationId != null) attrs.put(INVESTIGATION_ATTRIBUTE, investigationId);
        JDIDebugModel.createExceptionBreakpoint(ResourcesPlugin.getWorkspace().getRoot(),
                exceptionName, caught, uncaught, false, true, attrs);
        return new ExceptionBreakpointResult(exceptionName, true, "agent");
    }

    public boolean removeInvestigationException(String exceptionName, String investigationId)
            throws CoreException {
        boolean removed = false;
        for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
            if (bp instanceof IJavaExceptionBreakpoint exception && bp.getMarker() != null
                    && bp.getMarker().exists() && exceptionName.equals(exception.getTypeName())
                    && "agent".equals(bp.getMarker().getAttribute(OWNER_ATTRIBUTE, "user"))
                    && investigationId.equals(bp.getMarker().getAttribute(INVESTIGATION_ATTRIBUTE, ""))) {
                bp.delete();
                removed = true;
            }
        }
        return removed;
    }

    public record ExceptionBreakpointInfo(String typeName, boolean caught, boolean uncaught, String owner) {}

    public List<ExceptionBreakpointInfo> listExceptionBreakpoints() throws CoreException {
        List<ExceptionBreakpointInfo> result = new ArrayList<>();
        for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
            if (!(bp instanceof IJavaExceptionBreakpoint exception) || bp.getMarker() == null
                    || !bp.getMarker().exists()) continue;
            result.add(new ExceptionBreakpointInfo(exception.getTypeName(), exception.isCaught(),
                    exception.isUncaught(), "agent".equals(bp.getMarker().getAttribute(OWNER_ATTRIBUTE,"user"))
                            ? "agent" : "user"));
        }
        return result;
    }

    public boolean removeAgentExceptionBreakpoint(String typeName) throws CoreException {
        boolean removed = false;
        for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
            if (bp instanceof IJavaExceptionBreakpoint exception && bp.getMarker() != null
                    && bp.getMarker().exists() && typeName.equals(exception.getTypeName())
                    && "agent".equals(bp.getMarker().getAttribute(OWNER_ATTRIBUTE,"user"))) {
                bp.delete();
                removed = true;
            }
        }
        return removed;
    }

    public record BreakpointInfo(String typeName, int lineNumber,
                                 String projectName, boolean enabled,
                                 String owner, String condition, boolean tracepoint) {}

    public List<BreakpointInfo> listBreakpoints() throws CoreException {
        List<BreakpointInfo> result = new ArrayList<>();
        for (IBreakpoint breakpoint : DebugPlugin.getDefault()
                .getBreakpointManager().getBreakpoints()) {
            if (!(breakpoint instanceof IJavaLineBreakpoint javaBreakpoint)) {
                continue;
            }
            // Eclipse may retain a breakpoint while its underlying marker is
            // being removed. Such stale entries must not break the entire list.
            var marker = javaBreakpoint.getMarker();
            if (marker == null || !marker.exists() || marker.getResource() == null
                    || marker.getResource().getProject() == null) continue;
            try {
                IResource resource = marker.getResource();
                result.add(new BreakpointInfo(
                        javaBreakpoint.getTypeName(), javaBreakpoint.getLineNumber(),
                        resource.getProject().getName(), javaBreakpoint.isEnabled(),
                        getOwner(javaBreakpoint), javaBreakpoint.isConditionEnabled()
                                ? javaBreakpoint.getCondition() : null,
                        marker.getAttribute(TRACEPOINT_ATTRIBUTE, false)));
            } catch (CoreException staleBreakpoint) {
                // Another thread may remove the marker during enumeration.
                System.err.println("[Eclipse Agentic Debugger] Skipped stale breakpoint: "
                        + staleBreakpoint.getMessage());
            }
        }
        return result;
    }

    private String getOwner(IJavaLineBreakpoint breakpoint) throws CoreException {
        return "agent".equals(breakpoint.getMarker().getAttribute(OWNER_ATTRIBUTE, "user"))
                ? "agent" : "user";
    }

    /** Never removes manually created or unmarked breakpoints. */
    public boolean removeAgentBreakpoint(String typeName, int lineNumber) throws CoreException {
        if (lineNumber < 1 || typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("Invalid breakpoint location");
        }
        boolean removed = false;
        for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
            if (breakpoint instanceof IJavaLineBreakpoint javaBreakpoint
                    && typeName.equals(javaBreakpoint.getTypeName())
                    && lineNumber == javaBreakpoint.getLineNumber()
                    && "agent".equals(getOwner(javaBreakpoint))) {
                javaBreakpoint.delete();
                removed = true;
            }
        }
        return removed;
    }

    /** Deletes only the marker created for this exact investigation. */
    public boolean removeInvestigationBreakpoint(String typeName, int lineNumber,
                                                 String investigationId) throws CoreException {
        boolean removed = false;
        for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
            if (!(breakpoint instanceof IJavaLineBreakpoint javaBreakpoint)) continue;
            var marker = javaBreakpoint.getMarker();
            if (marker == null || !marker.exists()) continue;
            if (typeName.equals(javaBreakpoint.getTypeName())
                    && lineNumber == javaBreakpoint.getLineNumber()
                    && "agent".equals(marker.getAttribute(OWNER_ATTRIBUTE, "user"))
                    && investigationId.equals(marker.getAttribute(INVESTIGATION_ATTRIBUTE, ""))) {
                javaBreakpoint.delete();
                removed = true;
            }
        }
        return removed;
    }

    public BreakpointResult setBreakpoint(String qualifiedTypeName, int lineNumber)
            throws CoreException {
        return setBreakpoint(qualifiedTypeName, lineNumber, null, null, false);
    }

    /** Associate newly created markers with an investigation for safe cleanup. */
    public BreakpointResult setBreakpoint(String qualifiedTypeName, int lineNumber,
                                          String investigationId) throws CoreException {
        return setBreakpoint(qualifiedTypeName, lineNumber, investigationId, null, false);
    }

    /** Configures a condition or trace expression only on a newly owned breakpoint. */
    public BreakpointResult setBreakpoint(String qualifiedTypeName, int lineNumber,
            String investigationId, String condition, boolean tracepoint) throws CoreException {
        if (tracepoint && (condition == null || condition.isBlank())) {
            throw new IllegalArgumentException("Trace expression required");
        }
        if (condition != null && (condition.isBlank() || condition.length() > 500)) {
            throw new IllegalArgumentException("Condition must be 1-500 characters");
        }
        if (qualifiedTypeName == null ||
                !qualifiedTypeName.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            throw new IllegalArgumentException("Provide a fully qualified Java type name");
        }
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Line number must be positive");
        }

        IType found = null;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
                continue;
            }
            IJavaProject javaProject = JavaCore.create(project);
            IType type = javaProject.findType(qualifiedTypeName);
            if (type != null && type.exists() && type.getCompilationUnit() != null) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "Type occurs in multiple projects; select an unambiguous type");
                }
                found = type;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Java source type not found in open workspace projects");
        }

        ICompilationUnit unit = found.getCompilationUnit();
        String source = unit.getSource();
        String[] lines = source.split("\\R", -1);
        if (lineNumber > lines.length || lines[lineNumber - 1].isBlank()) {
            throw new IllegalArgumentException("Line is outside the source or blank");
        }

        IResource resource = unit.getResource();
        if (resource == null) {
            throw new IllegalArgumentException("Source is not a workspace resource");
        }

        IJavaLineBreakpoint existing = JDIDebugModel.lineBreakpointExists(
                resource, qualifiedTypeName, lineNumber);
        if (existing != null) {
            return new BreakpointResult(qualifiedTypeName, lineNumber,
                    resource.getProject().getName(), false, getOwner(existing));
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(OWNER_ATTRIBUTE, "agent");
        if (investigationId != null) attributes.put(INVESTIGATION_ATTRIBUTE, investigationId);
        if (tracepoint) attributes.put(TRACEPOINT_ATTRIBUTE, true);
        IJavaLineBreakpoint created = JDIDebugModel.createLineBreakpoint(resource, qualifiedTypeName,
                lineNumber, -1, -1, 0, true, attributes);
        try {
            if (condition != null) {
                // Eclipse supports multi-statement conditions for non-suspending tracepoints.
                created.setCondition(tracepoint
                        ? "System.out.println(" + condition + "); return false;"
                        : condition);
                created.setConditionEnabled(true);
                created.setConditionSuspendOnTrue(true);
            }
        } catch (CoreException | RuntimeException failure) {
            created.delete();
            throw failure;
        }

        return new BreakpointResult(qualifiedTypeName, lineNumber,
                resource.getProject().getName(), true, "agent");
    }
}
