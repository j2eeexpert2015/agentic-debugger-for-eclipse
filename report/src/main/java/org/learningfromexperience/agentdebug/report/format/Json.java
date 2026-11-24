package org.learningfromexperience.agentdebug.report.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, lenient JSON reader shared by the MCP server (bridge replies, fixture checks, the session report) and the
 * Eclipse plugin (Agent Activity events). It replaces three copies: {@code MiniJson} (MCP server), {@code ActivityJson}
 * (plugin) and the reader inside {@code FixtureHttpTools} (OPEN-38 step 3). No dependencies.
 *
 * <p>Objects become {@code Map<String,Object>} (in order), arrays {@code List<Object>}, numbers {@code Double}.
 * Anything it cannot read gives {@code null}; it never throws.
 *
 * @author Ayan Dutta
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** The parsed value, or null when the text is not valid JSON. */
    public static Object parse(String text) {
        if (text == null) return null;
        try {
            Json p = new Json(text.strip());
            Object value = p.value();
            p.ws();
            return p.i == p.s.length() ? value : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }

    /** Text of a value: strings as they are, whole numbers without ".0", null as null. */
    public static String str(Object o) {
        if (o == null) return null;
        if (o instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return Long.toString(d.longValue());
        return o.toString();
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("Unexpected " + c);
        return Double.valueOf(s.substring(start, i));
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return map; }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            map.put(key, value());
            ws();
            if (s.charAt(i) == ',') { i++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return list; }
        while (true) {
            list.add(value());
            ws();
            if (s.charAt(i) == ',') { i++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private void expect(char c) {
        if (s.charAt(i) != c) throw new IllegalArgumentException("Expected " + c);
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}
