package org.learningfromexperience.agentdebug.setup;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides JavaRuntimeDetector support for the Agentic Debugger integration.
 *
 * @author Ayan Dutta
 */
final class JavaRuntimeDetector {
    Path currentJava() {
        if (Runtime.version().feature() < 21) {
            throw new IllegalStateException("Java 21 or newer is required to run this installer and MCP server.");
        }
        Path binary = InstallPaths.javaExecutable(System.getProperty("java.home"), System.getProperty("os.name"));
        if (!Files.isRegularFile(binary)) {
            throw new IllegalStateException("Cannot find the Java executable for the running JVM: " + binary);
        }
        return binary;
    }
}
