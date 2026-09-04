package io.github.jdubois.bootui.micronaut.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the asset controller's path handling: it may only ever reach inside the packaged bundle, so every
 * traversal or absolute-path shape must be rejected before a resource lookup happens.
 */
class BootUiAssetsControllerTest {

    @Test
    void acceptsAPathInsideTheBundle() {
        assertThat(BootUiAssetsController.safeResourceName("assets/index-abc.js"))
                .isEqualTo("assets/index-abc.js");
        assertThat(BootUiAssetsController.safeResourceName("favicon.svg")).isEqualTo("favicon.svg");
    }

    @Test
    void rejectsAnythingThatCouldEscapeTheBundle() {
        assertThat(BootUiAssetsController.safeResourceName("../application.properties"))
                .isNull();
        assertThat(BootUiAssetsController.safeResourceName("assets/../../secrets"))
                .isNull();
        assertThat(BootUiAssetsController.safeResourceName("/etc/passwd")).isNull();
        assertThat(BootUiAssetsController.safeResourceName("..\\windows")).isNull();
        assertThat(BootUiAssetsController.safeResourceName("file:/etc/passwd")).isNull();
        assertThat(BootUiAssetsController.safeResourceName("")).isNull();
        assertThat(BootUiAssetsController.safeResourceName(null)).isNull();
    }
}
