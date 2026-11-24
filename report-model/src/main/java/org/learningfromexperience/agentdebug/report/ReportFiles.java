package org.learningfromexperience.agentdebug.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Names of report files (OPEN-30): one HTML file per investigation, named so a person recognizes it,
 * {@code <application>_<yyyy-MM-dd>_<HH-mm-ss>.html}, for example {@code OrdersApplication_2026-11-24_10-39-31.html}.
 *
 * <p>The application is what the investigation launched (main class, or test class and method); without a launch it
 * is {@value #NO_TARGET}. The time is when the investigation started, on this computer's clock; dashes instead of
 * colons because Windows does not allow {@code :} in file names. Sorting by name also sorts by date per application.
 *
 * <p>Part of the event model on purpose: the MCP server decides the path, so it can print it and tell the agent,
 * and the writer only writes there (ADR-009, the model knows nothing about any writer).
 *
 * @author Ayan Dutta
 */
public final class ReportFiles {

    public static final String NO_TARGET = "Investigation";
    public static final String EXTENSION = ".html";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final int MAX_TARGET_LENGTH = 80;

    private ReportFiles() { }

    /** {@code OrdersApplication_2026-11-24_10-39-31.html}. */
    public static String fileName(String target, Instant start, ZoneId zone) {
        return safe(target) + "_" + STAMP.format(start.atZone(zone)) + EXTENSION;
    }

    /**
     * A file in {@code folder} that does not exist yet and is not in {@code taken}; the second report of the same
     * application in the same second gets {@code _2}, then {@code _3}. The chosen path is added to {@code taken}.
     */
    public static Path reserve(Path folder, String target, Instant start, ZoneId zone, Set<Path> taken) {
        String name = fileName(target, start, zone);
        String stem = name.substring(0, name.length() - EXTENSION.length());
        Path candidate = folder.resolve(name).toAbsolutePath().normalize();
        for (int n = 2; Files.exists(candidate) || taken.contains(candidate); n++)
            candidate = folder.resolve(stem + "_" + n + EXTENSION).toAbsolutePath().normalize();
        taken.add(candidate);
        return candidate;
    }

    /** Keeps letters, digits, '.', '-' and '_'; anything else becomes '-'. Blank gives {@value #NO_TARGET}. */
    static String safe(String target) {
        if (target == null || target.isBlank()) return NO_TARGET;
        StringBuilder out = new StringBuilder();
        for (char c : target.strip().toCharArray())
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '-');
        String cleaned = out.toString().replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (cleaned.isEmpty()) return NO_TARGET;
        return cleaned.length() > MAX_TARGET_LENGTH ? cleaned.substring(0, MAX_TARGET_LENGTH) : cleaned;
    }
}
