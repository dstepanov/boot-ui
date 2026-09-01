package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders a tool payload for a human.
 *
 * <p>Best-effort by necessity: the CLI treats payloads as opaque so it keeps working across BootUI versions,
 * which means it cannot know that {@code findings} is a table and {@code jvm} is a header. It infers instead —
 * an array of like-shaped objects becomes a table, everything else a key/value tree — and {@code --json}
 * remains the exact, contract-stable output for anything that needs to be parsed.
 */
final class TextRenderer {

    /** Wide enough for a stack frame or a bean name, narrow enough to stay in an 80-column terminal. */
    private static final int MAX_CELL_WIDTH = 60;

    private final boolean color;

    TextRenderer(boolean color) {
        this.color = color;
    }

    String render(JsonValue value) {
        StringBuilder out = new StringBuilder();
        if (value.isObject()) {
            renderObject(value, out, 0);
        } else if (value.isArray()) {
            renderArray(value, out, 0);
        } else {
            out.append(value.asDisplayText());
        }
        String text = out.toString();
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private void renderObject(JsonValue object, StringBuilder out, int depth) {
        for (String name : object.names()) {
            JsonValue member = object.get(name);
            String indent = "  ".repeat(depth);
            if (member.isObject() && member.size() > 0) {
                out.append(indent).append(bold(name)).append('\n');
                renderObject(member, out, depth + 1);
                continue;
            }
            if (member.isArray() && member.size() > 0) {
                out.append(indent)
                        .append(bold(name))
                        .append(dim(" (" + member.size() + ")"))
                        .append('\n');
                renderArray(member, out, depth + 1);
                continue;
            }
            out.append(indent)
                    .append(label(name))
                    .append(": ")
                    .append(scalar(member))
                    .append('\n');
        }
    }

    private void renderArray(JsonValue array, StringBuilder out, int depth) {
        List<String> columns = tableColumns(array);
        if (!columns.isEmpty()) {
            renderTable(array, columns, out, depth);
            return;
        }
        String indent = "  ".repeat(depth);
        for (JsonValue element : array.values()) {
            if (element.isObject()) {
                out.append(indent).append(dim("-")).append('\n');
                renderObject(element, out, depth + 1);
            } else if (element.isArray()) {
                renderArray(element, out, depth + 1);
            } else {
                out.append(indent).append(dim("- ")).append(scalar(element)).append('\n');
            }
        }
    }

    /**
     * The columns to tabulate, or empty when this array is not table-shaped.
     *
     * <p>Requires every element to be an object of scalars. One nested object in one element and the table
     * would either lie or wrap unreadably, so the tree rendering is used instead.
     */
    private List<String> tableColumns(JsonValue array) {
        if (array.size() == 0) {
            return List.of();
        }
        Set<String> columns = new LinkedHashSet<>();
        for (JsonValue element : array.values()) {
            if (!element.isObject() || element.size() == 0) {
                return List.of();
            }
            for (String name : element.names()) {
                JsonValue member = element.get(name);
                if (member.isObject() || member.isArray()) {
                    return List.of();
                }
                columns.add(name);
            }
        }
        return columns.size() > 8 ? List.of() : List.copyOf(columns);
    }

    private void renderTable(JsonValue array, List<String> columns, StringBuilder out, int depth) {
        String indent = "  ".repeat(depth);
        List<List<String>> rows = new ArrayList<>();
        for (JsonValue element : array.values()) {
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) {
                row.add(truncate(element.get(column).asDisplayText()));
            }
            rows.add(row);
        }
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
            for (List<String> row : rows) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        out.append(indent);
        for (int i = 0; i < columns.size(); i++) {
            out.append(bold(pad(columns.get(i), i == columns.size() - 1 ? 0 : widths[i])));
            if (i < columns.size() - 1) {
                out.append("  ");
            }
        }
        out.append('\n').append(indent);
        for (int i = 0; i < columns.size(); i++) {
            out.append(dim("-".repeat(widths[i])));
            if (i < columns.size() - 1) {
                out.append("  ");
            }
        }
        out.append('\n');
        for (List<String> row : rows) {
            out.append(indent);
            for (int i = 0; i < row.size(); i++) {
                out.append(pad(row.get(i), i == row.size() - 1 ? 0 : widths[i]));
                if (i < row.size() - 1) {
                    out.append("  ");
                }
            }
            out.append('\n');
        }
    }

    private String scalar(JsonValue value) {
        if (value.isNull() || value.isMissing()) {
            return dim("-");
        }
        if (value.isObject() || value.isArray()) {
            // An empty object or array; anything non-empty was handled as a nested structure.
            return dim(value.toJson());
        }
        return truncate(value.asDisplayText());
    }

    private static String truncate(String text) {
        String single = text.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        return single.length() <= MAX_CELL_WIDTH ? single : single.substring(0, MAX_CELL_WIDTH - 1) + "\u2026";
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private String bold(String text) {
        return color ? "\u001B[1m" + text + "\u001B[0m" : text;
    }

    private String dim(String text) {
        return color ? "\u001B[2m" + text + "\u001B[0m" : text;
    }

    private String label(String text) {
        return color ? "\u001B[36m" + text + "\u001B[0m" : text;
    }

    /** The payload as indented JSON, for {@code --json} when a human is still reading it. */
    static String json(JsonValue value) {
        return JsonWriter.pretty(value);
    }
}
