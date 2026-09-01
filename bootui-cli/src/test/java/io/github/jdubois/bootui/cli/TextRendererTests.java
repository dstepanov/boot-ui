package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import org.junit.jupiter.api.Test;

/**
 * Pins the readable rendering.
 *
 * <p>The renderer has to work on payloads it knows nothing about, since the CLI treats responses as opaque so
 * it keeps working across BootUI versions. These tests fix the inference rules it uses to decide what a shape
 * means.
 */
class TextRendererTests {

    private final TextRenderer renderer = new TextRenderer(false);

    @Test
    void scalarMembersRenderAsKeyValueLines() {
        String text = render("{\"uptime\":\"3h\",\"pid\":42,\"healthy\":true}");

        assertThat(text).isEqualTo("uptime: 3h\npid: 42\nhealthy: true");
    }

    @Test
    void anArrayOfLikeShapedObjectsBecomesATable() {
        String text = render("{\"beans\":[{\"name\":\"a\",\"scope\":\"singleton\"},"
                + "{\"name\":\"bb\",\"scope\":\"prototype\"}]}");

        assertThat(text.lines().toList())
                .containsExactly(
                        "beans (2)", "  name  scope", "  ----  ---------", "  a     singleton", "  bb    prototype");
    }

    @Test
    void anArrayWithANestedObjectFallsBackToATreeRatherThanLyingInATable() {
        String text = render("{\"items\":[{\"name\":\"a\",\"detail\":{\"x\":1}}]}");

        assertThat(text.lines().toList())
                .containsExactly("items (1)", "  -", "    name: a", "    detail", "      x: 1");
    }

    @Test
    void anArrayOfScalarsRendersAsAList() {
        String text = render("{\"profiles\":[\"dev\",\"test\"]}");

        assertThat(text).isEqualTo("profiles (2)\n  - dev\n  - test");
    }

    @Test
    void nullAndAbsentValuesReadAsADashRatherThanTheWordNull() {
        String text = render("{\"cause\":null}");

        assertThat(text).isEqualTo("cause: -");
    }

    @Test
    void emptyCollectionsStayVisibleInsteadOfDisappearing() {
        String text = render("{\"findings\":[],\"details\":{}}");

        assertThat(text).isEqualTo("findings: []\ndetails: {}");
    }

    @Test
    void numbersKeepTheFormTheServerSentThem() {
        // Re-formatting would turn a version like 1.10 into 1.1 and a byte count into scientific notation.
        String text = render("{\"ratio\":1.50,\"bytes\":12345678901}");

        assertThat(text).isEqualTo("ratio: 1.50\nbytes: 12345678901");
    }

    @Test
    void longCellsAreTruncatedSoATableStaysReadable() {
        String stack = "a".repeat(200);
        String text = render("{\"rows\":[{\"trace\":\"" + stack + "\"}]}");

        assertThat(text).contains("\u2026");
        assertThat(text.lines().toList())
                .allSatisfy(line -> assertThat(line.length()).isLessThan(80));
    }

    @Test
    void newlinesInsideACellDoNotBreakTheTable() {
        String text = render("{\"rows\":[{\"message\":\"first\\nsecond\"}]}");

        assertThat(text.lines().toList()).containsExactly("rows (1)", "  message", "  ------------", "  first second");
    }

    @Test
    void nestedObjectsAreIndentedUnderTheirName() {
        String text = render("{\"jvm\":{\"vendor\":\"Eclipse\",\"version\":\"21\"}}");

        assertThat(text).isEqualTo("jvm\n  vendor: Eclipse\n  version: 21");
    }

    @Test
    void aTopLevelArrayIsRenderedWithoutAWrapper() {
        String text = renderer.render(JsonValue.parse("[{\"a\":1},{\"a\":2}]"));

        assertThat(text.lines().toList()).containsExactly("a", "-", "1", "2");
    }

    @Test
    void colourIsOnlyAddedWhenAskedFor() {
        String plain = new TextRenderer(false).render(JsonValue.parse("{\"a\":1}"));
        String coloured = new TextRenderer(true).render(JsonValue.parse("{\"a\":1}"));

        assertThat(plain).doesNotContain("\u001B[");
        assertThat(coloured).contains("\u001B[");
    }

    private String render(String json) {
        return renderer.render(JsonValue.parse(json));
    }
}
