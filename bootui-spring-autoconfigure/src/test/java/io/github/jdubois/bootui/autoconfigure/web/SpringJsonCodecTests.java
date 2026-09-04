package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.spi.json.JsonTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins that Spring Boot's Jackson 3 {@link SpringJsonCodec} agrees with the {@link JsonTree} contract the shared
 * engine GitHub client and OSV scanner are written against.
 *
 * <p>The clients themselves are tested once, framework-free, in {@code bootui-engine} against its
 * dependency-free test codec. What can still differ per stack is exactly this wrapper — Jackson 2 and Jackson 3
 * spell several of these accessors differently — so this suite covers the semantics the shared code actually
 * depends on: missing versus null versus absent, the defaulting {@code as*} coercions, container iteration and
 * field enumeration, and reporting malformed input as an {@link IOException} rather than an unchecked
 * exception.</p>
 */
class SpringJsonCodecTests {

    private static final String PAYLOAD = """
            {
              "login": "alice",
              "count": 42,
              "draft": false,
              "labels": [{"name": "bug"}, {"name": "feature"}],
              "nested": {"value": null},
              "resources": {"core": {"remaining": 4950}, "search": {"remaining": 28}}
            }
            """;

    private final SpringJsonCodec codec = new SpringJsonCodec();

    @Test
    void navigatesObjectsArraysAndScalars() throws Exception {
        JsonTree root = codec.read(PAYLOAD);

        assertThat(root.isObject()).isTrue();
        assertThat(root.path("login").isString()).isTrue();
        assertThat(root.path("login").asString()).isEqualTo("alice");
        assertThat(root.path("count").asLong(-1)).isEqualTo(42);
        assertThat(root.path("count").asInt(-1)).isEqualTo(42);
        assertThat(root.path("draft").asBoolean(true)).isFalse();
        assertThat(root.path("labels").isArray()).isTrue();
        assertThat(root.path("labels").size()).isEqualTo(2);
        assertThat(root.path("labels").path(0).path("name").asString()).isEqualTo("bug");
    }

    @Test
    void distinguishesMissingFromNullFromAbsent() throws Exception {
        JsonTree root = codec.read(PAYLOAD);

        assertThat(root.path("nope").isMissing()).isTrue();
        assertThat(root.path("nope").path("deeper").isMissing()).isTrue();
        assertThat(root.path("labels").path(9).isMissing()).isTrue();
        assertThat(root.get("nope")).isNull();
        assertThat(root.path("nested").path("value").isNull()).isTrue();
        assertThat(root.path("nested").path("value").isMissing()).isFalse();
        assertThat(root.get("nested").get("value")).isNotNull();
    }

    @Test
    void fallsBackToTheSuppliedDefaultWhenAValueIsNotConvertible() throws Exception {
        JsonTree root = codec.read(PAYLOAD);

        assertThat(root.path("nope").asLong(-1)).isEqualTo(-1);
        assertThat(root.path("nope").asInt(7)).isEqualTo(7);
        assertThat(root.path("nope").asBoolean(true)).isTrue();
        assertThat(root.path("login").asLong(-1)).isEqualTo(-1);
        // Containers, missing nodes and JSON nulls all coerce to the empty string, which every caller
        // treats as "absent" -- the one place the two Jackson generations disagree on their own.
        assertThat(root.path("labels").asString()).isEmpty();
        assertThat(root.path("resources").asString()).isEmpty();
        assertThat(root.path("nope").asString()).isEmpty();
        assertThat(root.path("nested").path("value").asString()).isEmpty();
    }

    @Test
    void enumeratesFieldsAndIteratesArrayElements() throws Exception {
        JsonTree root = codec.read(PAYLOAD);

        assertThat(root.path("resources").properties())
                .extracting(Map.Entry::getKey)
                .containsExactly("core", "search");
        assertThat(root.path("resources")
                        .properties()
                        .get(0)
                        .getValue()
                        .path("remaining")
                        .asLong(-1))
                .isEqualTo(4950);

        List<String> names = new ArrayList<>();
        for (JsonTree label : root.path("labels")) {
            names.add(label.path("name").asString());
        }
        assertThat(names).containsExactly("bug", "feature");
    }

    @Test
    void writesNeutralValuesAsJson() throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("package", Map.of("ecosystem", "Maven"));
        query.put("version", "1.0.0");
        query.put("page_token", null);

        String written = codec.write(Map.of("queries", List.of(query)));

        JsonTree round = codec.read(written);
        assertThat(round.path("queries")
                        .path(0)
                        .path("package")
                        .path("ecosystem")
                        .asString())
                .isEqualTo("Maven");
        assertThat(round.path("queries").path(0).path("version").asString()).isEqualTo("1.0.0");
        assertThat(round.path("queries").path(0).path("page_token").isNull()).isTrue();
    }

    @Test
    void reportsMalformedJsonAsAnIoException() {
        assertThatThrownBy(() -> codec.read("{not valid json")).isInstanceOf(IOException.class);
    }
}
