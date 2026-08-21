package io.github.jdubois.bootui.engine.safety;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the shared definition of the reserved {@code /bootui} classpath mount that the Spring MVC and
 * Spring WebFlux shell guards both match on (issue #856).
 */
class BootUiInternalMountTests {

    @Test
    void coversTheMountAndEverythingBelowIt() {
        assertThat(BootUiInternalMount.covers("/bootui")).isTrue();
        assertThat(BootUiInternalMount.covers("/bootui/")).isTrue();
        assertThat(BootUiInternalMount.covers("/bootui/index.html")).isTrue();
        assertThat(BootUiInternalMount.covers("/bootui/assets/index-abc123.js")).isTrue();
        assertThat(BootUiInternalMount.covers("/bootui/api/overview")).isTrue();
    }

    @Test
    void doesNotCoverAdjacentOrUnrelatedPaths() {
        assertThat(BootUiInternalMount.covers("/bootuix")).isFalse();
        assertThat(BootUiInternalMount.covers("/bootui-console/index.html")).isFalse();
        assertThat(BootUiInternalMount.covers("/console")).isFalse();
        assertThat(BootUiInternalMount.covers("/")).isFalse();
        assertThat(BootUiInternalMount.covers("")).isFalse();
        assertThat(BootUiInternalMount.covers(null)).isFalse();
    }
}
