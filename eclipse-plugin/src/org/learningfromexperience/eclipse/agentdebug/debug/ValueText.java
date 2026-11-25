package org.learningfromexperience.eclipse.agentdebug.debug;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The readable value of common JDK value objects (numbers, dates and times, UUID), made from their fields as the
 * debugger reads them, without running any code in the debugged program. Eclipse shows such an object only as
 * {@code (id=75)}; an agent then has to expand it, and a {@code BigDecimal} lists 30 static constants before its value
 * (trial 25 Nov 12:29: two calls only to learn that unitPrice was 60). Plain Java: unit-tested without Eclipse.
 *
 * <p>Returns null whenever the fields do not give a certain value (unknown type, a field missing, a BigDecimal kept in
 * its BigInteger form); the caller then shows what Eclipse shows.
 *
 * @author Ayan Dutta
 */
final class ValueText {

    /** The types this class can read. */
    static final Set<String> TYPES = Set.of("java.math.BigDecimal", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.lang.Character", "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.Instant", "java.util.UUID");

    private ValueText() {
    }

    /**
     * @param type   the object's class name
     * @param fields its own (not static) fields: name to the value text Eclipse gives (for a nested
     *               {@code LocalDate}/{@code LocalTime} of a {@code LocalDateTime}, the readable text of that object)
     * @return the value, e.g. "60", "2026-11-19", or null
     */
    static String format(String type, Map<String, String> fields) {
        try {
            return switch (type) {
                case "java.math.BigDecimal" -> {
                    // Small values keep their digits in intCompact; intVal is then null. Larger ones need intVal.
                    String intVal = fields.get("intVal");
                    long compact = Long.parseLong(fields.get("intCompact"));
                    int scale = Integer.parseInt(fields.get("scale"));
                    yield "null".equals(intVal) && compact != Long.MIN_VALUE
                            ? BigDecimal.valueOf(compact, scale).toPlainString() : null;
                }
                case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Boolean" ->
                        plain(fields.get("value"));
                case "java.lang.Double", "java.lang.Float" -> {
                    String v = plain(fields.get("value"));
                    Double.parseDouble(v);
                    yield v;
                }
                case "java.lang.Character" -> plain(fields.get("value"));
                case "java.time.LocalDate" -> LocalDate.of(Integer.parseInt(fields.get("year")),
                        Integer.parseInt(fields.get("month")), Integer.parseInt(fields.get("day"))).toString();
                case "java.time.LocalTime" -> LocalTime.of(Integer.parseInt(fields.get("hour")),
                        Integer.parseInt(fields.get("minute")), Integer.parseInt(fields.get("second")),
                        Integer.parseInt(fields.get("nano"))).toString();
                case "java.time.LocalDateTime" -> {
                    String date = fields.get("date"), time = fields.get("time");
                    yield date == null || time == null || date.contains("id=") || time.contains("id=") ? null
                            : date + "T" + time;
                }
                case "java.time.Instant" -> Instant.ofEpochSecond(Long.parseLong(fields.get("seconds")),
                        Long.parseLong(fields.get("nanos"))).toString();
                case "java.util.UUID" -> new UUID(Long.parseLong(fields.get("mostSigBits")),
                        Long.parseLong(fields.get("leastSigBits"))).toString();
                default -> null;
            };
        } catch (RuntimeException notAsExpected) {
            return null;
        }
    }

    private static String plain(String value) {
        if (value == null || value.isBlank() || value.contains("id=")) throw new IllegalArgumentException("no value");
        return value.strip();
    }
}
