package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPEN-25: what an agent needs from a failed JUnit test, read from the failure trace Eclipse's JUnit integration
 * reports: the message (the trace's first lines, before the first {@code at} frame), expected and actual when the
 * framework gave them only in the message (JUnit {@code expected: <a> but was: <b>}, AssertJ {@code expected: a /
 * but was: b}), and the failure location: the first stack frame that is not test-framework or JDK code (for a failed
 * assertion the test line, for an exception the application line that threw it).
 *
 * <p>Plain Java, no Eclipse imports: unit-tested without a workbench ({@code JUnitFailureTest}).
 *
 * @author Ayan Dutta
 */
public final class JUnitFailure {

    /** Frames from these packages are never the failure location. */
    static final List<String> FRAMEWORK = List.of("org.junit.", "junit.", "org.opentest4j.", "org.assertj.",
            "org.hamcrest.", "org.mockito.", "org.eclipse.jdt.internal.junit", "org.apiguardian.",
            "org.springframework.test.", "java.", "javax.", "jdk.", "sun.", "com.sun.");

    /**
     * A stack frame with a line: {@code at pkg.Class.method(File.java:19)}, also with a module or class-loader prefix
     * ({@code at java.base/java.lang.reflect.Method.invoke(Method.java:580)}, {@code at app//pkg.Class.m(...)}).
     */
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+(?:[^\\s/()]*/)*([\\w$.]+)\\.([\\w$<>]+)\\(([^:()]+):(\\d+)\\)");
    /** Any stack frame line, also without a line number ({@code (Native Method)}, {@code (Unknown Source)}). */
    private static final Pattern ANY_FRAME = Pattern.compile("^\\s*at\\s+\\S");
    /** JUnit 4/5 style. */
    private static final Pattern ANGLE = Pattern.compile("expected:\\s*<(.*?)>\\s*but was:\\s*<(.*?)>", Pattern.DOTALL);
    /** AssertJ style, on separate lines. */
    private static final Pattern ASSERTJ = Pattern.compile("expected:\\s*(.+?)\\s*\\R\\s*but was:\\s*(.+?)\\s*(?:\\R|$)",
            Pattern.DOTALL);

    /** Characters of a message returned to the agent. */
    static final int MAX_MESSAGE = 500;

    private JUnitFailure() { }

    /** Where a test failed. */
    public record Location(String className, String method, int line) {
        /** {@code OrderServiceTest.calculatesDiscount:19}. */
        public String label() {
            return className.substring(className.lastIndexOf('.') + 1) + "." + method + ":" + line;
        }
    }

    /** The trace's header: everything before the first frame (exception type and message). */
    static String header(String trace) {
        if (trace == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : trace.split("\\R")) {
            if (ANY_FRAME.matcher(line).find()) break;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString().strip();
    }

    /** The failure message on one line, shortened to {@link #MAX_MESSAGE} characters. */
    public static String message(String trace) {
        String text = header(trace).replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE - 1) + "…";
    }

    /**
     * Expected and actual: the values Eclipse reports when present, otherwise read from the message; each may be
     * null when neither has them.
     */
    public static String[] expectedActual(String trace, String reportedExpected, String reportedActual) {
        String expected = blankToNull(reportedExpected), actual = blankToNull(reportedActual);
        if (expected != null && actual != null) return new String[] {expected, actual};
        String header = header(trace);
        for (Pattern pattern : List.of(ANGLE, ASSERTJ)) {
            Matcher m = pattern.matcher(header);
            if (m.find()) {
                return new String[] {expected != null ? expected : m.group(1).strip(),
                        actual != null ? actual : m.group(2).strip()};
            }
        }
        return new String[] {expected, actual};
    }

    /** The first frame outside {@link #FRAMEWORK}, or null when the trace has none. */
    public static Location location(String trace) {
        if (trace == null) return null;
        for (String line : trace.split("\\R")) {
            Matcher m = FRAME.matcher(line);
            if (!m.find()) continue;
            String className = m.group(1);
            if (FRAMEWORK.stream().anyMatch(className::startsWith)) continue;
            return new Location(className, m.group(2), Integer.parseInt(m.group(4)));
        }
        return null;
    }

    /** The header and the first {@code maxFrames} frames, for a reader; the rest is counted. */
    public static String shortTrace(String trace, int maxFrames) {
        if (trace == null) return "";
        List<String> out = new ArrayList<>();
        int frames = 0, omitted = 0;
        for (String line : trace.split("\\R")) {
            if (line.isBlank()) continue;
            if (ANY_FRAME.matcher(line).find()) {
                if (frames++ >= maxFrames) { omitted++; continue; }
            }
            out.add(line.strip());
        }
        if (omitted > 0) out.add("... " + omitted + " more frames");
        return String.join("\n", out);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
