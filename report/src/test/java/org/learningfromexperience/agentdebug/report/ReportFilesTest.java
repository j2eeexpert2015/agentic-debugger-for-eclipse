package org.learningfromexperience.agentdebug.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests (JUnit 5) for report file names (OPEN-30): {@code <application>_<yyyy-MM-dd>_<HH-mm-ss>.html}.
 * Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ReportFilesTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final Instant START = Instant.parse("2026-11-24T05:09:31Z");   // 10:39:31 in Kolkata

    @TempDir
    Path folder;

    @Test
    @DisplayName("RM-U9 the file name is application, local date and local time")
    void rmU9() {
        assertEquals("OrdersApplication_2026-11-24_10-39-31.html", ReportFiles.fileName("OrdersApplication", START, KOLKATA));
        assertEquals("DiscountTest.appliesDiscount_2026-11-24_10-39-31.html",
                ReportFiles.fileName("DiscountTest.appliesDiscount", START, KOLKATA));
        assertEquals("Investigation_2026-11-24_10-39-31.html", ReportFiles.fileName(null, START, KOLKATA));
        assertEquals("My-saved-launch_2026-11-24_10-39-31.html", ReportFiles.fileName("My saved: launch?", START, KOLKATA));
    }

    @Test
    @DisplayName("RM-U10 a second report of the same application in the same second gets _2, an existing file is never overwritten")
    void rmU10() throws Exception {
        Set<Path> taken = new HashSet<>();
        Path first = ReportFiles.reserve(folder, "OrdersApplication", START, KOLKATA, taken);
        Path second = ReportFiles.reserve(folder, "OrdersApplication", START, KOLKATA, taken);
        assertEquals("OrdersApplication_2026-11-24_10-39-31.html", first.getFileName().toString());
        assertEquals("OrdersApplication_2026-11-24_10-39-31_2.html", second.getFileName().toString());
        assertTrue(first.isAbsolute());

        Files.writeString(folder.resolve("Calculator_2026-11-24_10-39-31.html"), "older report");
        Path next = ReportFiles.reserve(folder, "Calculator", START, KOLKATA, new HashSet<>());
        assertEquals("Calculator_2026-11-24_10-39-31_2.html", next.getFileName().toString());
    }
}
