package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for {@link ValueText}: the readable value of common JDK value objects from their fields, as the
 * debugger reads them (VT-U1 to VT-U3). No Eclipse needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ValueTextTest {

    @Test @DisplayName("VT-U1 BigDecimal from intCompact and scale (unitPrice = 60 in the boot-quote trial)")
    void vtU1() {
        assertEquals("60", ValueText.format("java.math.BigDecimal",
                Map.of("intCompact", "60", "scale", "0", "intVal", "null", "precision", "0", "stringCache", "null")));
        assertEquals("108.00", ValueText.format("java.math.BigDecimal",
                Map.of("intCompact", "10800", "scale", "2", "intVal", "null")));
        assertEquals("-0.5", ValueText.format("java.math.BigDecimal", Map.of("intCompact", "-5", "scale", "1", "intVal", "null")));
        assertNull(ValueText.format("java.math.BigDecimal",
                Map.of("intCompact", String.valueOf(Long.MIN_VALUE), "scale", "0", "intVal", " (id=99)")),
                "a large value kept as BigInteger: not guessed");
    }

    @Test @DisplayName("VT-U2 number wrappers, dates and times, Instant, UUID")
    void vtU2() {
        assertEquals("42", ValueText.format("java.lang.Integer", Map.of("value", "42")));
        assertEquals("true", ValueText.format("java.lang.Boolean", Map.of("value", "true")));
        assertEquals("2.5", ValueText.format("java.lang.Double", Map.of("value", "2.5")));
        assertEquals("2026-11-19", ValueText.format("java.time.LocalDate", Map.of("year", "2026", "month", "11", "day", "19")));
        assertEquals("09:30", ValueText.format("java.time.LocalTime",
                Map.of("hour", "9", "minute", "30", "second", "0", "nano", "0")));
        assertEquals("2026-11-19T09:30", ValueText.format("java.time.LocalDateTime",
                Map.of("date", "2026-11-19", "time", "09:30")));
        assertEquals("2026-11-25T06:58:51Z", ValueText.format("java.time.Instant", Map.of("seconds", "1795589931", "nanos", "0")));
        assertEquals("00000000-0000-0001-0000-000000000002",
                ValueText.format("java.util.UUID", Map.of("mostSigBits", "1", "leastSigBits", "2")));
    }

    @Test @DisplayName("VT-U3 anything uncertain gives null, so Eclipse's own text is shown")
    void vtU3() {
        assertNull(ValueText.format("org.example.Order", Map.of("id", "7")), "not a known type");
        assertNull(ValueText.format("java.time.LocalDate", Map.of("year", "2026")), "a field missing");
        assertNull(ValueText.format("java.lang.Integer", Map.of("value", " (id=12)")), "not a plain value");
        assertNull(ValueText.format("java.time.LocalDateTime", Map.of("date", " (id=3)", "time", "09:30")),
                "a nested object that could not be read");
    }
}
