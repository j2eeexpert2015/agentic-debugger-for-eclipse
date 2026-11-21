package org.learningfromexperience.eclipse.agentdebug.debug;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

/** Explicit opt-in E2E fixture. Never changes an existing project or launch configuration. */
public final class SmokeTestProject {
    public static final String PROJECT = "AgenticDebuggerCalculatorE2E";
    public static final String LAUNCH = "AverageCalculatorApplication";
    public static final String TYPE = "com.learningfromexperience.debugsample.AverageCalculatorApplication";
    public static final String BREAKPOINT_TYPE = "com.learningfromexperience.debugsample.service.AverageCalculator";
    public static final int BREAKPOINT_LINE = 10;

    private static final String APPLICATION_SOURCE = """
            package com.learningfromexperience.debugsample;
            
            import com.learningfromexperience.debugsample.service.AverageCalculator;
            
            public final class AverageCalculatorApplication {
                public static void main(String[] args) {
                    int[] measurements = {10, 20, 31};
                    AverageCalculator calculator = new AverageCalculator();
                    double average = calculator.calculateAverage(measurements);
                    System.out.println("Average: " + average);
                }
            }
            """;

    private static final String CALCULATOR_SOURCE = """
            package com.learningfromexperience.debugsample.service;
            
            public final class AverageCalculator {
                public double calculateAverage(int[] numbers) {
                    if (numbers == null || numbers.length == 0) {
                        throw new IllegalArgumentException("numbers must not be empty");
                    }
                    int sum = 0;
                    for (int number : numbers) {
                        sum += number; // BREAKPOINT: line 10
                    }
                    return sum / numbers.length; // Intentional bug: integer division
                }
            }
            """;

    private static String readSource(IFile file) throws Exception {
        try (var stream = file.getContents()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String success(boolean reused) {
        return "{\"status\":\"success\",\"reused\":" + reused
                + ",\"project\":\"" + PROJECT + "\",\"launch\":\"" + LAUNCH
                + "\",\"type\":\"" + BREAKPOINT_TYPE + "\",\"line\":" + BREAKPOINT_LINE + "}";
    }

    public String prepare() throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(PROJECT);
        // Repeated test runs reuse only our exact reserved fixture; never overwrite user data.
        if (project.exists()) {
            IFile expectedApplication = project.getFile("src/com/learningfromexperience/debugsample/AverageCalculatorApplication.java");
            IFile expectedCalculator = project.getFile("src/com/learningfromexperience/debugsample/service/AverageCalculator.java");
            if (!project.isOpen() || !expectedApplication.exists() || !expectedCalculator.exists()
                    || !APPLICATION_SOURCE.equals(readSource(expectedApplication))
                    || !CALCULATOR_SOURCE.equals(readSource(expectedCalculator))) {
                throw new IllegalStateException("Reserved test project exists with different contents; refusing to modify: " + PROJECT);
            }
            boolean launchFound = false;
            for (var config : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations()) {
                if (LAUNCH.equals(config.getName())) {
                    launchFound = true;
                    if (!PROJECT.equals(config.getAttribute("org.eclipse.jdt.launching.PROJECT_ATTR", ""))
                            || !TYPE.equals(config.getAttribute("org.eclipse.jdt.launching.MAIN_TYPE", ""))) {
                        throw new IllegalStateException("Reserved launch name belongs to another application: " + LAUNCH);
                    }
                }
            }
            if (!launchFound) throw new IllegalStateException("Reserved project exists but test launch is missing; refusing to modify it");
            // Reset only this verified, reserved fixture's previous debug sessions.
            for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
                var config = launch.getLaunchConfiguration();
                if (config != null && LAUNCH.equals(config.getName()) && !launch.isTerminated()) {
                    launch.terminate();
                }
            }
            return success(true);
        }
        for (var config : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations()) {
            if (LAUNCH.equals(config.getName())) {
                throw new IllegalStateException("Reserved smoke-test launch already exists; refusing to overwrite: " + LAUNCH);
            }
        }
        project.create(null);
        project.open(null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });
        ICommand builder = description.newCommand();
        builder.setBuilderName(JavaCore.BUILDER_ID);
        description.setBuildSpec(new ICommand[] { builder });
        project.setDescription(description, null);

        IFolder src = project.getFolder("src");
        src.create(true, true, null);
        IFolder com = src.getFolder("com");
        com.create(true, true, null);
        IFolder organization = com.getFolder("learningfromexperience");
        organization.create(true, true, null);
        IFolder appPackage = organization.getFolder("debugsample");
        appPackage.create(true, true, null);
        IFile application = appPackage.getFile("AverageCalculatorApplication.java");
        application.create(new ByteArrayInputStream(APPLICATION_SOURCE.getBytes(StandardCharsets.UTF_8)), true, null);
        IFolder servicePackage = appPackage.getFolder("service");
        servicePackage.create(true, true, null);
        IFile calculator = servicePackage.getFile("AverageCalculator.java");
        calculator.create(new ByteArrayInputStream(CALCULATOR_SOURCE.getBytes(StandardCharsets.UTF_8)), true, null);

        IJavaProject javaProject = JavaCore.create(project);
        IClasspathEntry[] classpath = {
            JavaCore.newSourceEntry(src.getFullPath()),
            JavaCore.newContainerEntry(new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER"))
        };
        javaProject.setRawClasspath(classpath, project.getFullPath().append("bin"), null);

        ILaunchConfigurationType type = DebugPlugin.getDefault().getLaunchManager()
                .getLaunchConfigurationType("org.eclipse.jdt.launching.localJavaApplication");
        if (type == null) throw new IllegalStateException("Eclipse Java Application launch type unavailable");
        var working = type.newInstance(project, LAUNCH);
        working.setAttribute("org.eclipse.jdt.launching.PROJECT_ATTR", PROJECT);
        working.setAttribute("org.eclipse.jdt.launching.MAIN_TYPE", TYPE);
        working.doSave();
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, null);
        return success(false);
    }
}
