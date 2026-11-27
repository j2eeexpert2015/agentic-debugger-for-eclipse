package org.learningfromexperience.eclipse.agentdebug.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer for the few settings of GitHub Copilot that the plugin reads or changes
 * ({@link CopilotSettings}): objects, arrays, strings, booleans, numbers and null. Objects keep their key order, so a
 * value the plugin writes back differs from Copilot's only where the plugin changed it. Anything that is not valid JSON
 * throws {@link IllegalArgumentException}; callers then leave Copilot's setting untouched.
 * <p>Plain Java on purpose (no Gson dependency on the Copilot side): unit tests in {@code CopilotSettingsTest}.
 *
 * @author Ayan Dutta
 */
final class MiniJson {

    private final String text;
    private int at;

    private MiniJson(String text) { this.text = text; }

    /** Objects become {@code LinkedHashMap<String, Object>}, arrays {@code ArrayList<Object>}, numbers {@code Double}. */
    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("no JSON");
        MiniJson reader = new MiniJson(text);
        reader.space();
        Object value = reader.value();
        reader.space();
        if (reader.at != text.length()) throw reader.error("text after the JSON value");
        return value;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String s) quote(s, out);
        else if (value instanceof Boolean b) out.append(b);
        else if (value instanceof Double d) {
            if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) out.append(d.longValue());
            else out.append(d);
        } else if (value instanceof Number n) out.append(n);
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else throw new IllegalArgumentException("cannot write " + value.getClass().getSimpleName());
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private Object value() {
        if (at >= text.length()) throw error("unexpected end");
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw error("unexpected '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) { at++; return map; }
        while (true) {
            space();
            if (!peek('"')) throw error("expected a key");
            String key = string();
            space();
            expect(':');
            space();
            map.put(key, value());
            space();
            if (peek(',')) { at++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek(']')) { at++; return list; }
        while (true) {
            space();
            list.add(value());
            space();
            if (peek(',')) { at++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error("unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw error("unterminated escape");
            char e = text.charAt(at++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (at + 4 > text.length()) throw error("short \\u escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException bad) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("unexpected text");
        at += word.length();
        return value;
    }

    private boolean peek(char c) { return at < text.length() && text.charAt(at) == c; }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        at++;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Not valid JSON at " + at + ": " + what);
    }
}
