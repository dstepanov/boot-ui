package io.github.jdubois.bootui.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BootUiPathNormalizer}.
 */
class BootUiPathNormalizerTests {

    // --- valid paths ---

    @Test
    void acceptsDefaultPath() {
        assertThat(BootUiPathNormalizer.normalize("/bootui")).isEqualTo("/bootui");
    }

    @Test
    void acceptsCustomPath() {
        assertThat(BootUiPathNormalizer.normalize("/my-console")).isEqualTo("/my-console");
    }

    @Test
    void acceptsNestedPath() {
        assertThat(BootUiPathNormalizer.normalize("/admin/bootui")).isEqualTo("/admin/bootui");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(BootUiPathNormalizer.normalize("/bootui/")).isEqualTo("/bootui");
    }

    @Test
    void stripsMultipleTrailingSlashes() {
        assertThat(BootUiPathNormalizer.normalize("/bootui///")).isEqualTo("/bootui");
    }

    @Test
    void stripsLeadingAndTrailingWhitespace() {
        assertThat(BootUiPathNormalizer.normalize("  /bootui  ")).isEqualTo("/bootui");
    }

    // --- invalid paths ---

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsEmptyString() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsRootPath() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root");
    }

    @Test
    void rejectsPathWithoutLeadingSlash() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'/'");
    }

    @Test
    void rejectsPathWithDotDotTraversal() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin/../bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    void rejectsPathWithQueryComponent() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/bootui?foo=bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("?");
    }

    @Test
    void rejectsPathWithFragmentComponent() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/bootui#section"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#");
    }

    @Test
    void rejectsPathWithEncodedSeparatorLowercase() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/boot%2fui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%2F");
    }

    @Test
    void rejectsPathWithEncodedSeparatorUppercase() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/boot%2Fui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%2F");
    }

    @Test
    void rejectsPathWithDoubleSlash() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin//bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("//");
    }

    // --- DEFAULT_PATH constant ---

    @Test
    void defaultPathConstantIsBootui() {
        assertThat(BootUiPathNormalizer.DEFAULT_PATH).isEqualTo("/bootui");
    }
}
