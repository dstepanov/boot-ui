package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Companion to {@link BootUiInactiveShellIntegrationTests} for issue #856: the URL that exposes the
 * packaged bundle is not fixed at {@code /bootui/**}.
 *
 * <p>Spring Boot's static-resource handler sits behind the DispatcherServlet mapping
 * ({@code spring.mvc.servlet.path}) and behind a configurable pattern
 * ({@code spring.mvc.static-path-pattern}). With both moved, the shell surfaces at
 * {@code /app/static/bootui/index.html}, which a guard pinned to {@code /bootui/*} would sail past.
 * {@code BootUiShellGuardMounts} derives that prefix, and this test proves the derivation matches the
 * real URL on a real embedded Tomcat.</p>
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.enabled=OFF",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-inactive-shell-mapping-test-overrides.properties",
            "spring.mvc.servlet.path=/app",
            "spring.mvc.static-path-pattern=/static/**"
        })
class BootUiInactiveShellStaticMappingIntegrationTests {

    @LocalServerPort
    int port;

    @Test
    void relocatedStaticHandlingStillServesTheHostBundleButNotBootUi() throws Exception {
        // The host application's own static resource proves the handler really is mounted here, so the
        // 404 below is the guard rejecting the request rather than an unmapped URL.
        assertThat(status("/app/static/index.html")).isEqualTo(200);

        assertThat(status("/app/static/bootui/index.html")).isEqualTo(404);
        assertThat(status("/app/static/bootui/assets/" + BootUiInactiveShellIntegrationTests.packagedAssetName()))
                .isEqualTo(404);
        // The reserved namespace stays claimed at its canonical location as well.
        assertThat(status("/bootui/index.html")).isEqualTo(404);
    }

    private int status(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }
}
