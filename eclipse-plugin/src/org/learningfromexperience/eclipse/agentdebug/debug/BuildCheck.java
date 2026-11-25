package org.learningfromexperience.eclipse.agentdebug.debug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;

/**
 * OPEN-34: checks, before the agent starts a program or a test, that the project can run as written, and refuses with
 * a message the agent can act on instead of letting the JVM end with {@code ClassNotFoundException} (found 24 Nov: a
 * Maven project imported without compiled test classes) or Eclipse showing its blocking "Errors exist in project"
 * question.
 *
 * <ul>
 * <li>Compile or build-path errors in the project: refused, naming up to three of them (file, line, message).</li>
 * <li>No compiled class for the type: the project is built once (incremental; a project that was never built is
 *     built fully), then checked again; still missing: refused, suggesting how to fix the build.</li>
 * </ul>
 * Warnings never block. Only the project of the type is checked.
 *
 * @author Ayan Dutta
 */
final class BuildCheck {
    private static final int LISTED_ERRORS = 3;

    private BuildCheck() { }

    /** Throws {@link IllegalStateException} with the reason when {@code type} cannot be launched as built now. */
    static void verifyLaunchable(IType type) throws CoreException {
        IProject project = type.getJavaProject().getProject();
        List<String> errors = errors(project);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Project " + project.getName() + " has " + errors.size()
                    + (errors.size() == 1 ? " compile error" : " compile errors") + ", so it cannot run as written: "
                    + String.join("; ", errors.subList(0, Math.min(LISTED_ERRORS, errors.size())))
                    + (errors.size() > LISTED_ERRORS ? "; ..." : "")
                    + ". Fix them (or ask the user to) and start it again");
        }
        Path compiled = classFile(type);
        if (compiled == null || Files.isRegularFile(compiled)) return;
        project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
        if (!errors(project).isEmpty()) verifyLaunchable(type); // the build found errors: report those instead
        if (!Files.isRegularFile(compiled)) {
            throw new IllegalStateException("Project " + project.getName() + " is not built: " + type.getFullyQualifiedName()
                    + " has no compiled class (" + compiled + "), also after building the project. Ask the user to build it"
                    + " (Project > Clean, or for Maven: Maven > Update Project) and start it again");
        }
    }

    /** Compile and build-path errors of the project, as "File.java:12 message". */
    static List<String> errors(IProject project) throws CoreException {
        List<String> result = new ArrayList<>();
        for (String kind : List.of(IJavaModelMarker.BUILDPATH_PROBLEM_MARKER, IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER)) {
            for (IMarker marker : project.findMarkers(kind, true, IResource.DEPTH_INFINITE)) {
                if (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) != IMarker.SEVERITY_ERROR) continue;
                int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                IResource resource = marker.getResource();
                String where = resource == null || resource.getType() == IResource.PROJECT ? "build path"
                        : resource.getName() + (line > 0 ? ":" + line : "");
                result.add(where + " " + marker.getAttribute(IMarker.MESSAGE, "error"));
            }
        }
        return result;
    }

    /**
     * Where the compiled class of a source type must be: its source folder's output folder (Maven: target/classes or
     * target/test-classes), else the project's. Null for a type that is not from a source folder (a library).
     */
    static Path classFile(IType type) throws CoreException {
        IJavaElement ancestor = type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (!(ancestor instanceof IPackageFragmentRoot root) || root.getKind() != IPackageFragmentRoot.K_SOURCE) return null;
        IClasspathEntry entry = root.getRawClasspathEntry();
        IPath output = entry != null && entry.getOutputLocation() != null ? entry.getOutputLocation()
                : type.getJavaProject().getOutputLocation();
        IPath location;
        try {
            location = ResourcesPlugin.getWorkspace().getRoot().getFolder(output).getLocation();
        } catch (IllegalArgumentException projectRoot) {
            // The output folder is the project itself (classes next to the sources): no separate check.
            return null;
        }
        if (location == null) return null;
        return location.toFile().toPath().resolve(type.getFullyQualifiedName('$').replace('.', '/') + ".class");
    }
}
