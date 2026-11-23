import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles and runs the Eclipse-independent unit tests:
 * bridge dispatcher checks (BR-U1..BR-U13) and process-output checks (OUT-U1..OUT-U5).
 * No Maven, no Eclipse and no running bridge are needed.
 *
 * <pre>From the repository root:  java dev-e2e/BridgeUnitTests.java</pre>
 */
public final class BridgeUnitTests {

    private static int exitCode;

    public static void main(String[] args) throws Exception {
        Path repo = Path.of("").toAbsolutePath();
        Path src = repo.resolve("eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug");
        Path tests = repo.resolve("eclipse-plugin/tests/org/learningfromexperience/eclipse/agentdebug");
        Path operations = src.resolve("debug/EclipseAgentOperations.java");
        if (!Files.isRegularFile(tests.resolve("bridge/BridgeDispatcherTest.java")))
            throw new IllegalStateException("Run from the repository root");

        // Only classes without Eclipse imports can be compiled here.
        List<String> sources = new ArrayList<>();
        for (String file : List.of("bridge/BridgeCommands.java", "bridge/BridgeLog.java",
                "bridge/BridgeDispatcher.java", "debug/TextWindow.java")) {
            sources.add(src.resolve(file).toString());
        }
        sources.add(tests.resolve("bridge/BridgeDispatcherTest.java").toString());
        sources.add(tests.resolve("debug/TextWindowTest.java").toString());

        Path classes = Files.createTempDirectory("agentdebug-unit-tests");
        try {
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) throw new IllegalStateException("A JDK (not a JRE) is required");
            List<String> options = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8",
                    "-Xlint:all", "-d", classes.toString()));
            options.addAll(sources);
            if (javac.run(null, null, null, options.toArray(String[]::new)) != 0)
                throw new IllegalStateException("Compilation failed");

            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                    BridgeUnitTests.class.getClassLoader())) {
                run(loader, "org.learningfromexperience.eclipse.agentdebug.bridge.BridgeDispatcherTest",
                        new String[]{operations.toString()});
                run(loader, "org.learningfromexperience.eclipse.agentdebug.debug.TextWindowTest", new String[0]);
            }
        } finally {
            try (Stream<Path> files = Files.walk(classes)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(exitCode == 0 ? "PASS: all unit tests" : "FAIL: see errors above");
        // Exit explicitly: a failed check must never leave the JVM waiting on a thread.
        System.exit(exitCode);
    }

    private static void run(ClassLoader loader, String className, String[] args) throws Exception {
        Method main = loader.loadClass(className).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException failed) {
            System.err.println("FAIL " + className + ": " + failed.getCause());
            failed.getCause().printStackTrace(System.err);
            exitCode = 1;
        }
    }
}
