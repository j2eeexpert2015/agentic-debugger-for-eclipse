package org.learningfromexperience.eclipse.agentdebug.debug;

/**
 * Shortens long process output by keeping its beginning <b>and</b> its end.
 *
 * <p>Previously only the first 4000 characters were returned, so later output (a Spring Boot
 * stack trace, "Tomcat started on port", a final error) was lost once startup logging grew.
 * The beginning is still returned unchanged, so anything callers found before is still found.
 *
 * <p>Plain Java on purpose (no Eclipse imports) so it can be unit-tested without Eclipse.
 *
 * @author Ayan Dutta
 */
public final class TextWindow {

    /** Characters kept from the start: the same amount as before this change. */
    public static final int HEAD = 4_000;

    /** Characters kept from the end. */
    public static final int TAIL = 4_000;

    private TextWindow() { }

    /** Full text if it fits in HEAD + TAIL; otherwise head, an omission marker, and tail. */
    public static String headAndTail(String text) {
        if (text == null || text.length() <= HEAD + TAIL) return text;
        return text.substring(0, HEAD) + marker(text.length() - HEAD - TAIL) + text.substring(text.length() - TAIL);
    }

    /** The line inserted where characters were left out. */
    public static String marker(int omitted) {
        return "\n[... " + omitted + " characters omitted ...]\n";
    }
}
