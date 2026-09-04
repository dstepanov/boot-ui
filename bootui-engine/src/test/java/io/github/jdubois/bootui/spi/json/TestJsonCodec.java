package io.github.jdubois.bootui.spi.json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free {@link JsonCodec} used by the engine's own tests.
 *
 * <p>The engine is JSON-library-free by design, so its tests cannot reach for Jackson to exercise the shared
 * GitHub client and OSV scanner — not even in test scope, because a test-scope Jackson would quietly become the
 * thing the shared code is proven against, and the whole point of the {@link JsonCodec} SPI is that the shared
 * code is proven against <em>none</em> of them. This parser exists only to feed those tests: it implements
 * enough of RFC 8259 (objects, arrays, strings with escapes, numbers, the three literals, and strict rejection
 * of trailing garbage) to parse real GitHub and OSV payloads, and its accessors reproduce Jackson's coercion
 * and defaulting semantics as documented on {@link JsonTree}.</p>
 *
 * <p>It is not a production JSON implementation and makes no attempt to be one: adapters bind their own
 * Jackson, and each adapter's codec test pins that its wrapper agrees with this one on the same accessors.</p>
 */
public final class TestJsonCodec implements JsonCodec {

    @Override
    public JsonTree read(String json) throws IOException {
        return new Node(new Parser(json).parseDocument(), false);
    }

    @Override
    public String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeValue(list.get(i), out);
            }
            out.append(']');
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A parsed value (or the missing marker), exposing Jackson-compatible accessors. */
    private record Node(Object value, boolean missing) implements JsonTree {

        private static final Node MISSING = new Node(null, true);

        @Override
        public JsonTree path(String fieldName) {
            JsonTree child = get(fieldName);
            return child == null ? MISSING : child;
        }

        @Override
        public JsonTree path(int index) {
            if (value instanceof List<?> list && index >= 0 && index < list.size()) {
                return new Node(list.get(index), false);
            }
            return MISSING;
        }

        @Override
        public JsonTree get(String fieldName) {
            if (value instanceof Map<?, ?> map && map.containsKey(fieldName)) {
                return new Node(map.get(fieldName), false);
            }
            return null;
        }

        @Override
        public boolean isMissing() {
            return missing;
        }

        @Override
        public boolean isNull() {
            return !missing && value == null;
        }

        @Override
        public boolean isObject() {
            return value instanceof Map<?, ?>;
        }

        @Override
        public boolean isArray() {
            return value instanceof List<?>;
        }

        @Override
        public boolean isString() {
            return value instanceof String;
        }

        @Override
        public int size() {
            if (value instanceof List<?> list) {
                return list.size();
            }
            return value instanceof Map<?, ?> map ? map.size() : 0;
        }

        @Override
        public String asString() {
            if (missing || value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
                return "";
            }
            return String.valueOf(value);
        }

        @Override
        public boolean asBoolean(boolean defaultValue) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof Number number) {
                return number.intValue() != 0;
            }
            return value instanceof String string ? parseBoolean(string, defaultValue) : defaultValue;
        }

        private static boolean parseBoolean(String string, boolean defaultValue) {
            String trimmed = string.trim();
            if ("true".equals(trimmed)) {
                return true;
            }
            return "false".equals(trimmed) ? false : defaultValue;
        }

        @Override
        public int asInt(int defaultValue) {
            return (int) asLong(defaultValue);
        }

        @Override
        public long asLong(long defaultValue) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof Boolean bool) {
                return bool ? 1L : 0L;
            }
            return value instanceof String string ? parseLong(string, defaultValue) : defaultValue;
        }

        private static long parseLong(String string, long defaultValue) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        @Override
        public List<Map.Entry<String, JsonTree>> properties() {
            if (!(value instanceof Map<?, ?> map)) {
                return List.of();
            }
            List<Map.Entry<String, JsonTree>> entries = new ArrayList<>();
            map.forEach((key, child) -> entries.add(Map.entry(String.valueOf(key), new Node(child, false))));
            return entries;
        }

        @Override
        public Iterator<JsonTree> iterator() {
            List<JsonTree> children = new ArrayList<>();
            if (value instanceof List<?> list) {
                list.forEach(child -> children.add(new Node(child, false)));
            } else if (value instanceof Map<?, ?> map) {
                map.values().forEach(child -> children.add(new Node(child, false)));
            } else {
                return Collections.emptyIterator();
            }
            return children.iterator();
        }
    }

    /** Recursive-descent RFC 8259 reader producing {@link Map}/{@link List}/{@link String}/number/boolean/null. */
    private static final class Parser {

        private final String source;

        private int position;

        private Parser(String source) {
            this.source = source == null ? "" : source;
        }

        private Object parseDocument() throws IOException {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (position != source.length()) {
                throw new IOException("Trailing content at offset " + position);
            }
            return value;
        }

        private Object parseValue() throws IOException {
            if (position >= source.length()) {
                throw new IOException("Unexpected end of JSON input");
            }
            switch (source.charAt(position)) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    return parseLiteral("true", Boolean.TRUE);
                case 'f':
                    return parseLiteral("false", Boolean.FALSE);
                case 'n':
                    return parseLiteral("null", null);
                default:
                    return parseNumber();
            }
        }

        private Object parseObject() throws IOException {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw new IOException("Expected ',' or '}' at offset " + (position - 1));
                }
            }
        }

        private Object parseArray() throws IOException {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return array;
            }
            while (true) {
                skipWhitespace();
                array.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return array;
                }
                if (c != ',') {
                    throw new IOException("Expected ',' or ']' at offset " + (position - 1));
                }
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder text = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return text.toString();
                }
                if (c != '\\') {
                    text.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"', '\\', '/' -> text.append(escape);
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'u' -> {
                        if (position + 4 > source.length()) {
                            throw new IOException("Truncated \\u escape at offset " + position);
                        }
                        String hex = source.substring(position, position + 4);
                        position += 4;
                        try {
                            text.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new IOException("Invalid \\u escape '" + hex + "'", ex);
                        }
                    }
                    default -> throw new IOException("Invalid escape '\\" + escape + "'");
                }
            }
        }

        private Object parseLiteral(String literal, Object value) throws IOException {
            if (!source.startsWith(literal, position)) {
                throw new IOException("Invalid literal at offset " + position);
            }
            position += literal.length();
            return value;
        }

        private Object parseNumber() throws IOException {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            boolean decimal = false;
            while (position < source.length()) {
                char c = source.charAt(position);
                if (c >= '0' && c <= '9' || c == '+' || c == '-') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    decimal = true;
                    position++;
                } else {
                    break;
                }
            }
            String text = source.substring(start, position);
            if (text.isEmpty() || "-".equals(text)) {
                throw new IOException("Invalid number at offset " + start);
            }
            try {
                return decimal ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid number '" + text + "'", ex);
            }
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
        }

        private char peek() throws IOException {
            if (position >= source.length()) {
                throw new IOException("Unexpected end of JSON input");
            }
            return source.charAt(position);
        }

        private char next() throws IOException {
            char c = peek();
            position++;
            return c;
        }

        private void expect(char expected) throws IOException {
            char c = next();
            if (c != expected) {
                throw new IOException("Expected '" + expected + "' at offset " + (position - 1));
            }
        }
    }
}
