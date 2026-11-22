package org.learningfromexperience.eclipse.agentdebug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.ProjectImportConfiguration;

/** Imports the checked-in Phase 1 Maven fixture for an explicitly requested developer run. */
final class DevelopmentFixture {
    static void prepare(Path source, IProgressMonitor monitor) throws Exception {
        var root = ResourcesPlugin.getWorkspace().getRoot();
        source = source.toRealPath();
        String name = source.getFileName().toString();
        var project = root.getProject(name);
        Path local = root.getLocation().toFile().toPath().resolve(name);
        if (project.exists()) {
            if (!project.isOpen()) throw new IllegalStateException("Fixture project is closed: " + name);
            Path location = project.getLocation().toFile().toPath().toRealPath();
            if (!location.equals(source) && !Files.exists(location.resolve(".agentdebug-fixture"))) {
                throw new IllegalStateException("Unrelated project already uses the fixture name: " + location);
            }
            if (!location.equals(source)) verifySnapshot(source, location);
        } else {
            if (Files.exists(local)) {
                if (!Files.isRegularFile(local.resolve(".agentdebug-fixture")))
                    throw new IllegalStateException("Fixture destination already exists: " + local);
                verifySnapshot(source, local);
            } else {
                stageFixture(source, local);
            }
            var scanner = new LocalProjectScanner(List.of(local.toString()), false, MavenPlugin.getMavenModelManager());
            scanner.run(monitor);
            if (scanner.getProjects().size() != 1) throw new IllegalStateException("Expected one Maven fixture: " + local);
            MavenPlugin.getProjectConfigurationManager().importProjects(scanner.getProjects(), new ProjectImportConfiguration(), monitor);
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
    }

    private static void stageFixture(Path source, Path local) throws Exception {
        // Stage outside the project destination so partial copies are never registered.
        Path stage = Files.createTempDirectory(local.getParent(), ".agentdebug-import-");
        try (var paths = Files.walk(source.resolve("src"))) {
            for (Path path : paths.toList()) {
                Path target = stage.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        Files.copy(source.resolve("pom.xml"), stage.resolve("pom.xml"));
        Files.writeString(stage.resolve(".agentdebug-fixture"), source.toString());
        Files.move(stage, local);
    }

    private static void verifySnapshot(Path source, Path local) throws Exception {
        if (!Files.readString(local.resolve(".agentdebug-fixture")).equals(source.toString()))
            throw new IllegalStateException("Fixture belongs to a different checkout: " + local);
        try (var paths = Files.walk(source.resolve("src"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                if (Files.mismatch(file, local.resolve(source.relativize(file))) != -1)
                    throw new IllegalStateException("Fixture source differs; use a fresh test workspace: " + file);
            }
        }
        if (Files.mismatch(source.resolve("pom.xml"), local.resolve("pom.xml")) != -1)
            throw new IllegalStateException("Fixture POM differs; use a fresh test workspace");
    }
}
