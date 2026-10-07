package com.inputactivitytracker;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec shared by update and anonymous analytics clients. */
public final class JsonCodec {
    private JsonCodec() {}

    public static Map<String, Object> parseObject(String json) {
        Object value = new Parser(json).parse();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("JSON object required");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting too deep");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String text) { quote(out, text); return; }
        if (value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Number number) {
            String text = number.toString();
            if (!text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
                throw new IllegalArgumentException("Invalid JSON number");
            out.append(text); return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys must be strings");
                if (!first) out.append(','); first = false;
                quote(out, key); out.append(':'); append(out, entry.getValue(), depth + 1);
            }
            out.append('}'); return;
        }
        if (value instanceof Iterable<?> items) {
            out.append('['); boolean first = true;
            for (Object item : items) {
                if (!first) out.append(','); first = false; append(out, item, depth + 1);
            }
            out.append(']'); return;
        }
        if (value.getClass().isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) out.append(','); append(out, Array.get(value, i), depth + 1);
            }
            out.append(']'); return;
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        out.append("0".repeat(4 - hex.length())).append(hex);
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int at;
        Parser(String text) {
            if (text == null || text.length() > 5 * 1024 * 1024) throw new IllegalArgumentException("Invalid JSON size");
            this.text = text;
        }
        Object parse() {
            Object value = value(0); whitespace();
            if (at != text.length()) throw error();
            return value;
        }
        private Object value(int depth) {
            if (depth > 64) throw error();
            whitespace(); if (at >= text.length()) throw error();
            return switch (text.charAt(at)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        private Map<String, Object> object(int depth) {
            at++; whitespace(); Map<String, Object> map = new LinkedHashMap<>();
            if (take('}')) return map;
            do {
                whitespace(); if (at >= text.length() || text.charAt(at) != '"') throw error();
                String key = string(); whitespace(); require(':');
                if (map.containsKey(key)) throw error();
                map.put(key, value(depth)); whitespace();
                if (take('}')) return map;
                require(',');
            } while (true);
        }
        private List<Object> array(int depth) {
            at++; whitespace(); List<Object> list = new ArrayList<>();
            if (take(']')) return list;
            do {
                list.add(value(depth)); whitespace();
                if (take(']')) return list;
                require(',');
            } while (true);
        }
        private String string() {
            require('"'); StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error();
                if (c != '\\') { out.append(c); continue; }
                if (at >= text.length()) throw error();
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw error();
                        String hex = text.substring(at, at + 4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) throw error();
                        out.append((char) Integer.parseInt(hex, 16)); at += 4;
                    }
                    default -> throw error();
                }
            }
            throw error();
        }
        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) throw error();
            at += word.length(); return value;
        }
        private Number number() {
            int begin = at;
            if (take('-') && at == text.length()) throw error();
            if (take('0')) {
                if (at < text.length() && digit(text.charAt(at))) throw error();
            } else {
                if (at >= text.length() || text.charAt(at) < '1' || text.charAt(at) > '9') throw error();
                while (at < text.length() && digit(text.charAt(at))) at++;
            }
            if (take('.')) {
                int digits = at; while (at < text.length() && digit(text.charAt(at))) at++;
                if (at == digits) throw error();
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++; if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                int digits = at; while (at < text.length() && digit(text.charAt(at))) at++;
                if (at == digits) throw error();
            }
            String number = text.substring(begin, at);
            try {
                if (!number.contains(".") && !number.contains("e") && !number.contains("E")) return Long.valueOf(number);
                return new BigDecimal(number);
            } catch (NumberFormatException ex) { throw error(); }
        }
        private static boolean digit(char c) { return c >= '0' && c <= '9'; }
        private void whitespace() {
            while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\n' || text.charAt(at) == '\r' || text.charAt(at) == '\t')) at++;
        }
        private boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        private void require(char c) { if (!take(c)) throw error(); }
        private IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON at position " + at); }
    }
}
