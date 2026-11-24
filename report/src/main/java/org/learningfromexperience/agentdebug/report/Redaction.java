package org.learningfromexperience.agentdebug.report;

import java.util.Locale;
import java.util.Set;

/**
 * Prepares values before they become report events: masks values whose name suggests a secret and shortens long
 * values, so the report (a file the user may share) and the event queue never hold large or sensitive payloads.
 *
 * @author Ayan Dutta
 */
public final class Redaction {

    /** Longest value kept in a report line; longer values keep their start and say how much was left out. */
    public static final int MAX_VALUE_LENGTH = 300;
    public static final String MASK = "******";

    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "pwd", "secret", "token",
            "apikey", "api_key", "credential", "authorization", "privatekey", "private_key");

    private Redaction() { }

    /** True when the name (variable, field, header, argument) suggests a secret, ignoring case. */
    public static boolean secretName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : SECRET_WORDS) if (lower.contains(word)) return true;
        return false;
    }

    /** The value as it may appear in a report: masked for a secret name, otherwise shortened. */
    public static String value(String name, String value) {
        if (value == null) return "null";
        if (secretName(name)) return MASK;
        return shorten(value);
    }

    /** Keeps the first {@link #MAX_VALUE_LENGTH} characters and states how many were left out. */
    public static String shorten(String text) {
        if (text == null || text.length() <= MAX_VALUE_LENGTH) return text;
        return text.substring(0, MAX_VALUE_LENGTH) + "... (" + (text.length() - MAX_VALUE_LENGTH) + " more characters)";
    }
}
