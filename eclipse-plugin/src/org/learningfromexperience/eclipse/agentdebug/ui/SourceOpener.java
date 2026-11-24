package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;

/**
 * Opens the source file of a class at a line, for the links in the Agent Activity view (OPEN-36 A9/A10). Navigation
 * only: it opens an editor and selects the line; it never changes a file, a breakpoint or the debug session.
 *
 * <p>The file is found by name in the open projects of the workspace ({@code OrderService} gives
 * {@code OrderService.java}; {@code Outer$Inner} gives {@code Outer.java}; a name with a package must match the
 * folders too). Output and tool folders (target, bin, build, .git, node_modules) and derived resources are skipped.
 * With several matches, files under {@code src/main/java} come first, then the shortest path. Results are cached
 * while the file exists.
 *
 * @author Ayan Dutta
 */
final class SourceOpener {

    private static final Set<String> SKIPPED = Set.of("target", "bin", "build", "out", ".git", "node_modules", ".metadata");
    private static final Map<String, IFile> CACHE = new ConcurrentHashMap<>();

    private SourceOpener() {
    }

    /** Opens the class's file at the line; returns a short message for the view. Runs on the UI thread. */
    static String open(IWorkbenchPage page, String className, int line) {
        if (page == null || className == null || className.isBlank()) return "Nothing to open";
        IFile file = find(className.strip());
        String shown = simpleName(className) + ".java" + (line > 0 ? ":" + line : "");
        if (file == null) return shown + " is not in an open project of this workspace";
        try {
            IMarker marker = file.createMarker(IMarker.TEXT);
            try {
                if (line > 0) marker.setAttribute(IMarker.LINE_NUMBER, line);
                IDE.openEditor(page, marker, true);
            } finally {
                marker.delete();
            }
            return "Opened " + shown;
        } catch (Exception failure) {
            return "Could not open " + shown + ": " + failure.getMessage();
        }
    }

    static IFile find(String className) {
        IFile cached = CACHE.get(className);
        if (cached != null && cached.exists()) return cached;
        String outer = simpleName(className);
        int dollar = outer.indexOf('$');
        if (dollar > 0) outer = outer.substring(0, dollar);
        String fileName = outer + ".java";
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot > 0 ? "/" + className.substring(0, lastDot).replace('.', '/') + "/" + fileName : null;
        List<IFile> found = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen()) continue;
            try {
                project.accept((IResourceProxy proxy) -> {
                    if (proxy.isDerived()) return false;
                    if (proxy.getType() == IResource.FOLDER) return !SKIPPED.contains(proxy.getName());
                    if (proxy.getType() == IResource.FILE && proxy.getName().equals(fileName)) {
                        IFile file = (IFile) proxy.requestResource();
                        String path = file.getFullPath().toString();
                        if (packagePath == null || path.endsWith(packagePath)) found.add(file);
                    }
                    return true;
                }, IResource.NONE);
            } catch (Exception unreadable) {
                // A project that cannot be read is skipped.
            }
        }
        IFile best = found.stream().min(Comparator
                .comparing((IFile f) -> f.getFullPath().toString().contains("/src/main/java/") ? 0 : 1)
                .thenComparingInt(f -> f.getFullPath().segmentCount())).orElse(null);
        if (best != null) CACHE.put(className, best);
        return best;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }
}
