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
import org.eclipse.jdt.debug.core.JDIDebugModel;

/** Creates Java line breakpoints in projects of the current Eclipse workspace.
 *
 * @author Ayan Dutta
 */
public class EclipseBreakpointService {

    public static final String INVESTIGATION_ATTRIBUTE =
            "org.learningfromexperience.eclipse.agentdebug.investigationId";

    public static final String OWNER_ATTRIBUTE =
            "org.learningfromexperience.eclipse.agentdebug.owner";

    public record BreakpointResult(String typeName, int lineNumber,
                                   String projectName, boolean created,
                                   String owner) {}

    public record BreakpointInfo(String typeName, int lineNumber,
                                 String projectName, boolean enabled,
                                 String owner) {}

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
                        getOwner(javaBreakpoint)));
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
        return setBreakpoint(qualifiedTypeName, lineNumber, null);
    }

    /** Associate newly created markers with an investigation for safe cleanup. */
    public BreakpointResult setBreakpoint(String qualifiedTypeName, int lineNumber,
                                          String investigationId) throws CoreException {
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
        JDIDebugModel.createLineBreakpoint(resource, qualifiedTypeName,
                lineNumber, -1, -1, 0, true, attributes);

        return new BreakpointResult(qualifiedTypeName, lineNumber,
                resource.getProject().getName(), true, "agent");
    }
}
