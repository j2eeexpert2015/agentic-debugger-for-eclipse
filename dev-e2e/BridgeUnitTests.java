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
 * Compiles and runs the Eclipse-independent bridge unit tests (BR-U1 to BR-U12).
 * No Maven, no Eclipse and no running bridge are needed.
 *
 * <pre>From the repository root:  java dev-e2e/BridgeUnitTests.java</pre>
 */
public final class BridgeUnitTests {

    private static int exitCode;

    public static void main(String[] args) throws Exception {
        Path repo = Path.of("").toAbsolutePath();
        Path bridge = repo.resolve("eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/bridge");
        Path test = repo.resolve("eclipse-plugin/tests/org/learningfromexperience/eclipse/agentdebug/bridge/BridgeDispatcherTest.java");
        Path operations = repo.resolve("eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java");
        if (!Files.isRegularFile(test)) throw new IllegalStateException("Run from the repository root (missing " + test + ")");

        List<String> sources = new ArrayList<>();
        for (String file : List.of("BridgeCommands.java", "BridgeLog.java", "BridgeDispatcher.java")) {
            sources.add(bridge.resolve(file).toString());
        }
        sources.add(test.toString());

        Path classes = Files.createTempDirectory("agentdebug-bridge-tests");
        try {
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) throw new IllegalStateException("A JDK (not a JRE) is required");
            List<String> options = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8",
                    "-Xlint:all", "-d", classes.toString()));
            options.addAll(sources);
            int status = javac.run(null, null, null, options.toArray(String[]::new));
            if (status != 0) throw new IllegalStateException("Compilation failed");

            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                    BridgeUnitTests.class.getClassLoader())) {
                Class<?> testClass = loader.loadClass(
                        "org.learningfromexperience.eclipse.agentdebug.bridge.BridgeDispatcherTest");
                Method main = testClass.getMethod("main", String[].class);
                try {
                    main.invoke(null, (Object) new String[]{operations.toString()});
                } catch (java.lang.reflect.InvocationTargetException failed) {
                    System.err.println("FAIL: " + failed.getCause());
                    failed.getCause().printStackTrace(System.err);
                    exitCode = 1;
                }
            }
        } finally {
            try (Stream<Path> files = Files.walk(classes)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        // Exit explicitly: a failed check must never leave the JVM waiting on a thread.
        System.exit(exitCode);
    }
}
