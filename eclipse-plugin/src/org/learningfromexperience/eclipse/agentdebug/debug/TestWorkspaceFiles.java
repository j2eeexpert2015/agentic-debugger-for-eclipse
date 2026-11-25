package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.learningfromexperience.eclipse.agentdebug.debug.EclipseAgentOperations.q;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

/**
 * Test support for the build-check tests (OPEN-34): breaks and repairs the build of a fixture project in the test
 * workspace, the way a person editing code would. Only in the test Eclipse ({@code AGENTDEBUG_E2E=1}), only in projects
 * that carry the {@code .agentdebug-fixture} marker (the workspace's disposable copies; {@code examples/} in the
 * repository is never touched), only under {@code src/}, never with {@code ..}.
 *
 * <ul>
 * <li>{@code TEST_WRITE_SOURCE project path content}: creates or replaces a source file and builds the project.</li>
 * <li>{@code TEST_DELETE_SOURCE project path}: deletes a source file (no error when missing) and builds the project.</li>
 * <li>{@code TEST_DELETE_CLASS_FILE project type}: deletes the compiled class of a type from the output folder,
 *     behind Eclipse's back (as when a build output was cleaned outside Eclipse).</li>
 * <li>{@code TEST_BUILD_PROJECT project}: clean and full build, which restores every class.</li>
 * </ul>
 *
 * @author Ayan Dutta
 */
final class TestWorkspaceFiles {

    private TestWorkspaceFiles() { }

    static synchronized String execute(String[] p) throws Exception {
        IProject project = fixture(text(p, 1));
        return switch (p[0]) {
            case "TEST_WRITE_SOURCE" -> {
                IFile file = source(project, text(p, 2));
                byte[] content = text(p, 3).getBytes(StandardCharsets.UTF_8);
                if (file.exists()) file.setContents(new ByteArrayInputStream(content), IResource.FORCE, null);
                else file.create(new ByteArrayInputStream(content), IResource.FORCE, null);
                project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
                yield ok(",\"written\":" + q(file.getFullPath().toString()));
            }
            case "TEST_DELETE_SOURCE" -> {
                IFile file = source(project, text(p, 2));
                boolean existed = file.exists();
                if (existed) file.delete(IResource.FORCE, null);
                project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
                yield ok(",\"deleted\":" + existed);
            }
            case "TEST_DELETE_CLASS_FILE" -> {
                IType type = JavaCore.create(project).findType(text(p, 2));
                if (type == null || !type.exists()) throw new IllegalArgumentException("Type not found: " + text(p, 2));
                Path compiled = BuildCheck.classFile(type);
                if (compiled == null) throw new IllegalArgumentException("Not a source type: " + text(p, 2));
                yield ok(",\"deleted\":" + Files.deleteIfExists(compiled) + ",\"classFile\":" + q(compiled.toString()));
            }
            case "TEST_BUILD_PROJECT" -> {
                project.build(IncrementalProjectBuilder.CLEAN_BUILD, null);
                project.build(IncrementalProjectBuilder.FULL_BUILD, null);
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
                yield ok(",\"built\":true,\"errors\":" + BuildCheck.errors(project).size());
            }
            default -> throw new IllegalArgumentException("Unknown test command " + p[0]);
        };
    }

    private static IProject fixture(String name) {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.isOpen() || !project.getFile(".agentdebug-fixture").exists())
            throw new IllegalStateException("Only a disposable fixture project is changed by tests: " + name);
        return project;
    }

    private static IFile source(IProject project, String path) {
        if (!path.startsWith("src/") || path.contains("..") || !path.endsWith(".java"))
            throw new IllegalArgumentException("Only .java files under src/ are changed by tests: " + path);
        return project.getFile(path);
    }

    private static String ok(String fields) { return "{\"status\":\"success\"" + fields + "}"; }

    private static String text(String[] p, int index) {
        if (p.length <= index || p[index].isEmpty()) throw new IllegalArgumentException("Missing argument " + index);
        return new String(Base64.getUrlDecoder().decode(p[index]), StandardCharsets.UTF_8);
    }
}
