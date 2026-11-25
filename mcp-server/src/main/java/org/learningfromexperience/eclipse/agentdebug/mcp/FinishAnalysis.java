package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.learningfromexperience.agentdebug.report.format.Json;

/**
 * The agent's structured findings at the finish (OPEN-30 R4 phase 2, step 1), for the program flow of the report and
 * the live view: the optional arguments {@code title}, {@code trackedValue}, {@code findings}, {@code rootCause},
 * {@code fix}, {@code notObserved} and {@code assumedPath} of {@code finish_debug_investigation}, as one JSON object.
 *
 * <p>The one thing the tool adds itself: the source lines the root cause names, read from the investigation's
 * project ({@code source}: path relative to the project, first line number, the lines, the named range), or why they
 * could not be read ({@code sourceNote}). Only files inside the project are read, at most {@value #MAX_FILE_BYTES}
 * bytes, a window of at most {@value #MAX_LINES} lines. Everything else is the agent's text, cut to a bounded length;
 * the page shows it tagged FROM THE AGENT and escapes it.
 *
 * <p>Never throws: a malformed argument is left out.
 *
 * @author Ayan Dutta
 */
final class FinishAnalysis {

    static final int MAX_TEXT = 2_000, MAX_ITEMS = 20, MAX_LINES = 30, CONTEXT = 2;
    static final long MAX_FILE_BYTES = 2_000_000;
    private static final Set<String> SKIPPED = Set.of("target", "bin", "build", "out", ".git", ".metadata",
            "node_modules", ".gradle", ".idea", ".settings");
    /** "23", "22-25", "22 to 25", "L23". */
    private static final Pattern LINES = Pattern.compile("(\\d{1,6})(?:\\s*(?:-|–|to|\\.\\.)\\s*(\\d{1,6}))?");

    private FinishAnalysis() {
    }

    /** The JSON object, or null when the agent gave none of the fields. */
    static String json(Map<String, Object> args, Path project) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "title", text(args.get("title"), 200));
            Map<String, Object> tracked = obj(args.get("trackedValue"));
            if (!tracked.isEmpty()) {
                Map<String, Object> t = new LinkedHashMap<>();
                put(t, "name", text(tracked.get("name"), 200));
                put(t, "expected", text(tracked.get("expected"), 200));
                put(t, "actual", text(tracked.get("actual"), 200));
                if (!t.isEmpty()) out.put("trackedValue", t);
            }
            List<Object> findings = new ArrayList<>();
            for (Object item : arr(args.get("findings"))) {
                Map<String, Object> f = obj(item);
                String text = text(f.isEmpty() ? item : f.get("text"), MAX_TEXT);
                if (text == null || findings.size() >= MAX_ITEMS) continue;
                Map<String, Object> one = new LinkedHashMap<>();
                put(one, "at", text(f.get("at"), 200));
                one.put("text", text);
                findings.add(one);
            }
            if (!findings.isEmpty()) out.put("findings", findings);
            Map<String, Object> root = obj(args.get("rootCause"));
            if (!root.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>();
                put(r, "file", text(root.get("file"), 300));
                put(r, "lines", text(root.get("lines"), 50));
                put(r, "explanation", text(root.get("explanation"), MAX_TEXT));
                if (!r.isEmpty()) {
                    out.put("rootCause", r);
                    if (r.get("file") != null) source((String) r.get("file"), (String) r.get("lines"), project, out);
                }
            }
            put(out, "fix", text(args.get("fix"), MAX_TEXT));
            List<Object> notObserved = new ArrayList<>();
            for (Object item : arr(args.get("notObserved"))) {
                String text = text(item, 500);
                if (text != null && notObserved.size() < MAX_ITEMS) notObserved.add(text);
            }
            if (!notObserved.isEmpty()) out.put("notObserved", notObserved);
            put(out, "assumedPath", text(args.get("assumedPath"), 500));
            return out.isEmpty() ? null : write(out);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    // ---- the source lines ---------------------------------------------------------------------------------------

    private static void source(String file, String lines, Path project, Map<String, Object> out) {
        if (project == null) {
            out.put("sourceNote", "Source not read: this investigation launched no project, so its folder is unknown.");
            return;
        }
        int[] range = range(lines);
        if (range == null) {
            out.put("sourceNote", "Source not read: no line number given.");
            return;
        }
        Path found = find(project, file);
        if (found == null) {
            out.put("sourceNote", "Source not read: " + file + " was not found in the project.");
            return;
        }
        List<String> all;
        try {
            if (Files.size(found) > MAX_FILE_BYTES) {
                out.put("sourceNote", "Source not read: the file is too large.");
                return;
            }
            try {
                all = Files.readAllLines(found, StandardCharsets.UTF_8);
            } catch (MalformedInputException notUtf8) {
                all = Files.readAllLines(found, StandardCharsets.ISO_8859_1);
            }
        } catch (IOException unreadable) {
            out.put("sourceNote", "Source not read: " + unreadable.getMessage());
            return;
        }
        if (range[0] > all.size()) {
            out.put("sourceNote", "Source not read: the file has only " + all.size() + " lines, not line " + range[0] + ".");
            return;
        }
        int hiTo = Math.min(range[1], all.size());
        int from = Math.max(1, range[0] - CONTEXT);
        int to = Math.min(all.size(), Math.min(hiTo + CONTEXT, from + MAX_LINES - 1));
        List<Object> shown = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            String line = all.get(i - 1);
            shown.add(line.length() > 300 ? line.substring(0, 300) + "…" : line);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("path", project.relativize(found).toString().replace('\\', '/'));
        s.put("from", from);
        s.put("highlightFrom", range[0]);
        s.put("highlightTo", Math.min(hiTo, to));
        s.put("lines", shown);
        out.put("source", s);
    }

    /** {first, last} line of "23", "22-25"; null without a number. */
    static int[] range(String lines) {
        if (lines == null) return null;
        Matcher m = LINES.matcher(lines);
        if (!m.find()) return null;
        int a = Integer.parseInt(m.group(1));
        int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
        if (a < 1) return null;
        return b < a ? new int[]{a, a} : new int[]{a, Math.min(b, a + MAX_LINES)};
    }

    /**
     * The file in the project: the path as given (relative to the project) when it is there, otherwise the first file
     * with the same name, preferring one whose path ends with what was given. Never outside the project.
     */
    static Path find(Path project, String given) {
        try {
            Path root = project.toAbsolutePath().normalize();
            String clean = given.replace('\\', '/').replaceFirst("^\\./", "");
            if (clean.isBlank()) return null;
            Path direct = root.resolve(clean).normalize();
            if (direct.startsWith(root) && Files.isRegularFile(direct)) return direct;
            String name = clean.substring(clean.lastIndexOf('/') + 1);
            List<Path> matches = new ArrayList<>();
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 16, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && SKIPPED.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(name)) matches.add(file);
                    return matches.size() >= 50 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path p : matches) if (p.toString().replace('\\', '/').endsWith(clean)) return p;
            return matches.isEmpty() ? null : matches.get(0);
        } catch (IOException | RuntimeException unusable) {
            return null;
        }
    }

    // ---- reading the arguments ----------------------------------------------------------------------------------

    /** An object argument; also one the agent passed as a JSON string. */
    private static Map<String, Object> obj(Object value) {
        if (value instanceof String s && s.strip().startsWith("{")) value = parse(s);
        return Json.obj(value);
    }

    private static List<Object> arr(Object value) {
        if (value instanceof String s && s.strip().startsWith("[")) value = parse(s);
        if (value instanceof String s && !s.isBlank()) return List.of(s);
        return Json.arr(value);
    }

    private static Object parse(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException notJson) {
            return text;
        }
    }

    private static String text(Object value, int max) {
        if (value == null || value instanceof Map || value instanceof List) return null;
        String s = Json.str(value);
        if (s == null || s.isBlank()) return null;
        s = s.strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    // ---- writing ------------------------------------------------------------------------------------------------

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(value, b);
        return b.toString();
    }

    private static void write(Object value, StringBuilder b) {
        if (value instanceof Map<?, ?> map) {
            b.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) b.append(',');
                first = false;
                string(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (value instanceof List<?> list) {
            b.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) b.append(',');
                write(list.get(i), b);
            }
            b.append(']');
        } else if (value instanceof Number n) {
            b.append(n);
        } else if (value == null) {
            b.append("null");
        } else {
            string(String.valueOf(value), b);
        }
    }

    private static void string(String text, StringBuilder b) {
        b.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
