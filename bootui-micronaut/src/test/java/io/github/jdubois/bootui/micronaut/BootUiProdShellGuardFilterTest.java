package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the surface the always-registered production guard suppresses. It must cover both the configured
 * mount and the reserved internal one (where the packaged SPA assets live), and nothing else.
 */
class BootUiProdShellGuardFilterTest {

    @Test
    void coversTheConfiguredMountAndTheReservedInternalMount() {
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/console", "/console", "/console/api"))
                .isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/console/api/beans", "/console", "/console/api"))
                .isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui/assets/app.js", "/console", "/console/api"))
                .isTrue();
    }

    @Test
    void leavesApplicationTrafficAlone() {
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/catalog", "/bootui"))
                .isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui-other", "/bootui"))
                .isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath(null, "/bootui")).isFalse();
    }
}
