package io.github.jdubois.bootui.client;

import java.util.Map;

/** Renders JSON text: quoting for the tree model, and pretty-printing for terminal output. */
public final class JsonWriter {

    private static final String INDENT = "  ";

    private JsonWriter() {}

    /** The value as a JSON string literal, with the escapes RFC 8259 requires. */
    public static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '\b':
                    quoted.append("\\b");
                    break;
                case '\f':
                    quoted.append("\\f");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) current));
                    } else {
                        quoted.append(current);
                    }
            }
        }
        return quoted.append('"').toString();
    }

    /** A flat object as compact JSON, used to build request bodies. */
    public static String object(Map<String, JsonValue> members) {
        return JsonValue.object(members).toJson();
    }

    /** The value as indented JSON, for reading rather than piping. */
    public static String pretty(JsonValue value) {
        StringBuilder json = new StringBuilder();
        write(value, json, 0);
        return json.toString();
    }

    private static void write(JsonValue value, StringBuilder json, int depth) {
        if (value.isObject()) {
            if (value.size() == 0) {
                json.append("{}");
                return;
            }
            json.append("{\n");
            int index = 0;
            for (String name : value.names()) {
                indent(json, depth + 1).append(quote(name)).append(": ");
                write(value.get(name), json, depth + 1);
                json.append(++index < value.size() ? ",\n" : "\n");
            }
            indent(json, depth).append('}');
            return;
        }
        if (value.isArray()) {
            if (value.size() == 0) {
                json.append("[]");
                return;
            }
            json.append("[\n");
            int index = 0;
            for (JsonValue element : value.values()) {
                indent(json, depth + 1);
                write(element, json, depth + 1);
                json.append(++index < value.size() ? ",\n" : "\n");
            }
            indent(json, depth).append(']');
            return;
        }
        json.append(value.toJson());
    }

    private static StringBuilder indent(StringBuilder json, int depth) {
        return json.append(INDENT.repeat(depth));
    }
}
