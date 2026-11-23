package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for {@link TextWindow} (OUT-U1 to OUT-U5). No Eclipse needed: run by
 * {@code mvn clean package}. Display names keep the OUT-U IDs used in the docs.
 *
 * @author Ayan Dutta
 */
class TextWindowTest {

    @Test @DisplayName("OUT-U1 null and short text are returned unchanged")
    void outU1() {
        assertNull(TextWindow.headAndTail(null), "null stays null");
        assertEquals("", TextWindow.headAndTail(""), "empty stays empty");
        assertEquals("Tomcat started on port 8080", TextWindow.headAndTail("Tomcat started on port 8080"), "short unchanged");
    }

    @Test @DisplayName("OUT-U2 text of exactly 8000 characters is not shortened")
    void outU2() {
        String text = "x".repeat(TextWindow.HEAD + TextWindow.TAIL);
        assertEquals(text, TextWindow.headAndTail(text), "boundary unchanged");
    }

    @Test @DisplayName("OUT-U3 long text keeps the first 4000 characters unchanged")
    void outU3() {
        String text = "START-MARKER " + "a".repeat(20_000) + " END-MARKER";
        assertTrue(TextWindow.headAndTail(text).startsWith(text.substring(0, TextWindow.HEAD)), "head identical to before this change");
    }

    @Test @DisplayName("OUT-U4 long text keeps the last 4000 characters (the part that used to be lost)")
    void outU4() {
        String text = "a".repeat(20_000) + "Caused by: java.lang.IllegalStateException: boom";
        String result = TextWindow.headAndTail(text);
        assertTrue(result.endsWith("Caused by: java.lang.IllegalStateException: boom"),
                () -> "tail kept: " + result.substring(result.length() - 80));
    }

    @Test @DisplayName("OUT-U5 omission marker states exactly how much was left out")
    void outU5() {
        String result = TextWindow.headAndTail("b".repeat(8_001));
        assertTrue(result.contains("[... 1 characters omitted ...]"), "marker counts 1 omitted character");
        assertEquals(TextWindow.HEAD + TextWindow.TAIL + TextWindow.marker(1).length(), result.length(), "head + marker + tail");
    }
}
