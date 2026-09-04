package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.spi.json.JsonCodec;
import io.github.jdubois.bootui.spi.json.JsonTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot's {@link JsonCodec} over Jackson 3 ({@code tools.jackson}), the JSON binding the shared engine
 * GitHub client and OSV scanner run on for this stack.
 *
 * <p>Nearly every accessor is a direct delegation to {@code JsonNode}, deliberately: the twin Quarkus and
 * Micronaut codecs delegate to the Jackson 2 equivalents, and keeping both sides translation-free is what makes
 * the one shared client render identical payloads on every stack. The single exception is {@code asString()},
 * where the two generations genuinely disagree and {@link JsonTree} specifies the answer.</p>
 *
 * <p>Jackson 3 signals malformed input with an <em>unchecked</em> {@link JacksonException}, unlike Jackson 2
 * whose {@code JsonProcessingException} already extends {@link IOException}. Wrapping it here is what lets the
 * engine treat a malformed third-party response through the same bounded error path on every stack instead of
 * letting an unchecked parser exception escape a refresh on Spring Boot alone.</p>
 */
public class SpringJsonCodec implements JsonCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public JsonTree read(String json) throws IOException {
        try {
            return new JacksonJsonTree(objectMapper.readTree(json));
        } catch (JacksonException ex) {
            throw new IOException("Malformed JSON response", ex);
        }
    }

    @Override
    public String write(Object value) throws IOException {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IOException("Could not serialize JSON request body", ex);
        }
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
            return node.isString();
        }

        @Override
        public int size() {
            return node.size();
        }

        @Override
        public String asString() {
            // Jackson 3's asString() throws on a container (Jackson 2's asText() returns ""); JsonTree
            // specifies the empty string, so the shared clients see one behaviour on every stack.
            return node.isValueNode() && !node.isNull() ? node.asString() : "";
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
            node.properties()
                    .forEach(entry ->
                            entries.add(Map.entry(entry.getKey(), (JsonTree) new JacksonJsonTree(entry.getValue()))));
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
