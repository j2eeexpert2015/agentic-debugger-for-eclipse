import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Command-line launcher for the Claude transcript grader. The grader itself lives in the {@code testing}
 * module ({@code org.learningfromexperience.agentdebug.testing.grading.ClaudeTranscriptEvaluator}), where its
 * unit tests EV-U1..EV-U18 run in the normal build ({@code mvn clean package}).
 *
 * <p>This launcher compiles that one source file into {@code testing/target/grader-classes} (ignored by git,
 * rewritten on every run) and runs it, so the commands below still work without Maven, Eclipse or Claude
 * usage, exactly as before.
 *
 * <pre>
 * From the repository root (JDK 21+):
 *   java dev-e2e/ClaudeTranscriptEvaluator.java          (grades every transcript under evaluation\, all subfolders)
 *   java dev-e2e/ClaudeTranscriptEvaluator.java evaluation\reports\claude-trials
 *   java dev-e2e/ClaudeTranscriptEvaluator.java path\to\boot-quote-....claude.jsonl
 *   java dev-e2e/ClaudeTranscriptEvaluator.java --summary [--batch ID] [folder]
 * Options: --scenario NAME, --expectations FILE, --require-pass
 * </pre>
 */
public final class ClaudeTranscriptEvaluator {

    static final String SOURCE =
            "testing/src/main/java/org/learningfromexperience/agentdebug/testing/grading/ClaudeTranscriptEvaluator.java";
    static final String CLASS = "org.learningfromexperience.agentdebug.testing.grading.ClaudeTranscriptEvaluator";

    public static void main(String[] args) throws Exception {
        Path source = Path.of(SOURCE);
        if (!Files.isRegularFile(source))
            throw new IllegalStateException("Run from the repository root (missing " + SOURCE + ")");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("A JDK (not a JRE) is required");

        // Fixed folder instead of a temp folder: the grader ends with System.exit, so a temp folder would never be removed.
        Path classes = Path.of("testing/target/grader-classes");
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(classes);
        if (javac.run(null, null, null, "--release", "21", "-encoding", "UTF-8", "-nowarn",
                "-d", classes.toString(), source.toString()) != 0)
            throw new IllegalStateException("Could not compile " + SOURCE);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                ClaudeTranscriptEvaluator.class.getClassLoader())) {
            Method main = loader.loadClass(CLASS).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args);
            } catch (InvocationTargetException error) {
                // Show the grader's own error, not the reflection wrapper.
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
        }
    }
}
