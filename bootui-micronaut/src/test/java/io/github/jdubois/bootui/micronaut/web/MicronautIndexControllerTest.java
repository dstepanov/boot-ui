package io.github.jdubois.bootui.micronaut.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the runtime-path injection into the packaged SPA shell. The browser base must end in a slash (the
 * bundle references its assets relatively) and must be composed from configuration, never from the
 * request.
 */
class MicronautIndexControllerTest {

    private static final String HTML = "<!doctype html><html><head><title>BootUI</title></head><body></body></html>";

    @Test
    void injectsTheBrowserBaseAndTheRuntimePaths() {
        String rendered =
                MicronautIndexController.injectRuntimePaths(HTML, "/app/console/", "/app/console/api", "/app/");

        assertThat(rendered).contains("<base href=\"/app/console/\" />");
        assertThat(rendered).contains("<meta content=\"/app/console/api\" name=\"bootui-api-path\" />");
        assertThat(rendered).contains("<meta content=\"/app/\" name=\"bootui-application-path\" />");
    }

    @Test
    void leavesAnExistingBaseTagAlone() {
        String html = "<html><head><base href=\"/existing/\" /></head></html>";

        assertThat(MicronautIndexController.injectBaseHref(html, "/bootui/")).isEqualTo(html);
    }

    @Test
    void leavesMarkupWithoutAHeadAlone() {
        assertThat(MicronautIndexController.injectBaseHref("<html></html>", "/bootui/"))
                .isEqualTo("<html></html>");
    }
}
