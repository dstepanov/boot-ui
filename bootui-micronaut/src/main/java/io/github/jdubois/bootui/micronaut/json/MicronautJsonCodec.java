package io.github.jdubois.bootui.micronaut.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.spi.json.JsonCodec;
import io.github.jdubois.bootui.spi.json.JsonTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Micronaut's {@link JsonCodec} over Jackson 2 ({@code com.fasterxml.jackson}, from {@code micronaut-jackson-databind}),
 * the JSON binding the shared engine GitHub client and OSV scanner run on for this stack.
 *
 * <p>Nearly every accessor is a direct delegation to {@code JsonNode}, deliberately: the twin Spring codec
 * delegates to the Jackson 3 ({@code tools.jackson}) equivalents — {@code isTextual()}/{@code asText()} here
 * being {@code isString()}/{@code asString()} there — and keeping both sides translation-free is what makes the
 * one shared client render identical payloads on every stack. The single exception is {@code asString()}, where
 * the two generations genuinely disagree and {@link JsonTree} specifies the answer. Jackson 2's
 * {@code JsonProcessingException} already extends {@link IOException}, so malformed input needs no wrapping to
 * satisfy the SPI contract.</p>
 */
public class MicronautJsonCodec implements JsonCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public JsonTree read(String json) throws IOException {
        return new JacksonJsonTree(objectMapper.readTree(json));
    }

    @Override
    public String write(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private record JacksonJsonTree(JsonNode node) implements JsonTree {

        @Override
        public JsonTree path(String fieldName) {
            return new JacksonJsonTree(node.path(fieldName));
        }

        @Override
        public JsonTree path(int index) {
            return new JacksonJsonTree(node.path(index));
        }

        @Override
        public JsonTree get(String fieldName) {
            JsonNode value = node.get(fieldName);
            return value == null ? null : new JacksonJsonTree(value);
        }

        @Override
        public boolean isMissing() {
            return node.isMissingNode();
        }

        @Override
        public boolean isNull() {
            return node.isNull();
        }

        @Override
        public boolean isObject() {
            return node.isObject();
        }

        @Override
        public boolean isArray() {
            return node.isArray();
        }

        @Override
        public boolean isString() {
            return node.isTextual();
        }

        @Override
        public int size() {
            return node.size();
        }

        @Override
        public String asString() {
            // JsonTree specifies the empty string for a container, a missing node or a JSON null; Jackson 2
            // already returns "" for containers but "null" for a NullNode, so that one case is pinned here.
            return node.isValueNode() && !node.isNull() ? node.asText() : "";
        }

        @Override
        public boolean asBoolean(boolean defaultValue) {
            return node.asBoolean(defaultValue);
        }

        @Override
        public int asInt(int defaultValue) {
            return node.asInt(defaultValue);
        }

        @Override
        public long asLong(long defaultValue) {
            return node.asLong(defaultValue);
        }

        @Override
        public List<Map.Entry<String, JsonTree>> properties() {
            List<Map.Entry<String, JsonTree>> entries = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                entries.add(Map.entry(entry.getKey(), (JsonTree) new JacksonJsonTree(entry.getValue())));
            }
            return entries;
        }

        @Override
        public Iterator<JsonTree> iterator() {
            List<JsonTree> children = new ArrayList<>();
            for (JsonNode child : node) {
                children.add(new JacksonJsonTree(child));
            }
            return children.iterator();
        }
    }
}
