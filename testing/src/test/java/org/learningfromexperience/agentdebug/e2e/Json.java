package org.learningfromexperience.agentdebug.e2e;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Small JSON codec for the bridge protocol, avoiding third-party runtime dependencies. */
final class Json {
    private final String s;
    private int p;
    private Json(String s) { this.s = s; }
    static Object parse(String s) { Json j = new Json(s); Object v = j.value(); j.ws(); if (j.p != s.length()) throw new IllegalArgumentException("Unexpected JSON suffix"); return v; }
    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
    private char ch() { if (p >= s.length()) throw new IllegalArgumentException("Unexpected JSON end"); return s.charAt(p); }
    private boolean take(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw new IllegalArgumentException("Expected '" + c + "' at " + p); }
    private Object value() {
        ws(); char c = ch();
        if (c == '"') return string();
        if (c == '{') { p++; Map<String,Object> result = new LinkedHashMap<>(); if (take('}')) return result;
            do { String key = string(); expect(':'); result.put(key, value()); } while (take(',')); expect('}'); return result; }
        if (c == '[') { p++; List<Object> result = new ArrayList<>(); if (take(']')) return result;
            do { result.add(value()); } while (take(',')); expect(']'); return result; }
        if (s.startsWith("true", p)) { p += 4; return true; }
        if (s.startsWith("false", p)) { p += 5; return false; }
        if (s.startsWith("null", p)) { p += 4; return null; }
        int start = p;
        while (p < s.length() && "-+0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        if (start == p) throw new IllegalArgumentException("Invalid JSON value at " + p);
        String n = s.substring(start,p);
        return n.contains(".") || n.contains("e") || n.contains("E") ? Double.parseDouble(n) : Long.parseLong(n);
    }
    private String string() {
        expect('"'); StringBuilder b = new StringBuilder();
        while (true) {
            char c = ch(); p++;
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char escape = ch(); p++;
            switch (escape) {
                case '"' -> b.append('"'); case '\\' -> b.append('\\'); case '/' -> b.append('/');
                case 'b' -> b.append('\b'); case 'f' -> b.append('\f'); case 'n' -> b.append('\n');
                case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                case 'u' -> { if (p + 4 > s.length()) throw new IllegalArgumentException("Invalid unicode escape");
                    b.append((char)Integer.parseInt(s.substring(p,p+4),16)); p += 4; }
                default -> throw new IllegalArgumentException("Invalid JSON escape: " + escape);
            }
        }
    }
    static String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof String x) return "\"" + x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t") + "\"";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?,?> m) { StringJoiner j = new StringJoiner(",","{","}"); m.forEach((k,x) -> j.add(stringify(String.valueOf(k)) + ":" + stringify(x))); return j.toString(); }
        if (v instanceof Iterable<?> it) { StringJoiner j = new StringJoiner(",","[","]"); for (Object x:it) j.add(stringify(x)); return j.toString(); }
        throw new IllegalArgumentException("Unsupported JSON value " + v.getClass());
    }
}
