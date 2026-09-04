package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the surface the always-registered production guard suppresses: the console's own dormant mounts,
 * and nothing else. Blanking out more than that would 404 an application's own routes in production.
 */
class BootUiProdShellGuardFilterTest {

    @Test
    void coversTheConfiguredUiAndApiMounts() {
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/console", "/console", "/console/api"))
                .isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/console/assets/app.js", "/console", "/console/api"))
                .isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/console/api/beans", "/console", "/console/api"))
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

    /**
     * A console mounted elsewhere does not occupy the default mount, so a route the application serves
     * there must not be turned into a 404.
     */
    @Test
    void leavesTheDefaultMountToTheApplicationWhenTheConsoleIsMountedElsewhere() {
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui", "/console", "/console/api"))
                .isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui/whatever", "/console", "/console/api"))
                .isFalse();
    }
}
