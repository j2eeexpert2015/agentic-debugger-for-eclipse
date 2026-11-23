package org.learningfromexperience.eclipse.agentdebug.debug;

/**
 * Dependency-free checks for {@link TextWindow} (OUT-U1 to OUT-U5). No Eclipse needed.
 * Run from the repository root: {@code java dev-e2e/BridgeUnitTests.java}
 *
 * @author Ayan Dutta
 */
public final class TextWindowTest {

    private static int passed;

    public static void main(String[] args) {
        check("OUT-U1 null and short text are returned unchanged", () -> {
            require(TextWindow.headAndTail(null) == null, "null stays null");
            require(TextWindow.headAndTail("").isEmpty(), "empty stays empty");
            require(TextWindow.headAndTail("Tomcat started on port 8080").equals("Tomcat started on port 8080"), "short unchanged");
        });
        check("OUT-U2 text of exactly 8000 characters is not shortened", () -> {
            String text = "x".repeat(TextWindow.HEAD + TextWindow.TAIL);
            require(TextWindow.headAndTail(text).equals(text), "boundary unchanged");
        });
        check("OUT-U3 long text keeps the first 4000 characters unchanged", () -> {
            String text = "START-MARKER " + "a".repeat(20_000) + " END-MARKER";
            String result = TextWindow.headAndTail(text);
            require(result.startsWith(text.substring(0, TextWindow.HEAD)), "head identical to before this change");
        });
        check("OUT-U4 long text keeps the last 4000 characters (the part that used to be lost)", () -> {
            String text = "a".repeat(20_000) + "Caused by: java.lang.IllegalStateException: boom";
            String result = TextWindow.headAndTail(text);
            require(result.endsWith("Caused by: java.lang.IllegalStateException: boom"), "tail kept: " + result.substring(result.length() - 80));
        });
        check("OUT-U5 omission marker states exactly how much was left out", () -> {
            String text = "b".repeat(8_001);
            String result = TextWindow.headAndTail(text);
            require(result.contains("[... 1 characters omitted ...]"), "marker counts 1 omitted character");
            require(result.length() == TextWindow.HEAD + TextWindow.TAIL + TextWindow.marker(1).length(), "head + marker + tail");
        });
        System.out.println("PASS: " + passed + " process-output checks");
    }

    private static void check(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
