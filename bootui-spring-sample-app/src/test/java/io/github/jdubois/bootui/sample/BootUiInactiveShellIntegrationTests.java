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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Regression test for issue #856, on a real embedded Tomcat with the real {@code bootui-ui} jar on the
 * classpath.
 *
 * <p>{@code bootui.enabled=OFF} reproduces the production-dark state (the same
 * {@code BootUiActivationCondition} verdict a {@code prod} profile produces) while keeping the sample
 * app's own configuration untouched. Every BootUI route is then unregistered — but the compiled Vue
 * bundle ships at {@code META-INF/resources/bootui/}, which Spring Boot's default static-resource
 * handler serves regardless, so {@code GET /bootui/index.html} used to answer {@code 200} with the empty
 * shell. {@code BootUiShellGuardAutoConfiguration} turns the whole mount into a plain {@code 404}.</p>
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.enabled=OFF",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-inactive-shell-test-overrides.properties"
        })
class BootUiInactiveShellIntegrationTests {

    @LocalServerPort
    int port;

    @Test
    void packagedShellAndAssetsAreNotServedWhenBootUiIsInactive() throws Exception {
        assertThat(status("/bootui/index.html")).isEqualTo(404);
        assertThat(status("/bootui/")).isEqualTo(404);
        assertThat(status("/bootui")).isEqualTo(404);
        assertThat(status("/bootui/api/overview")).isEqualTo(404);
        // A real, content-hashed bundle entry resolved from the packaged jar: proves the 404 is the
        // guard rejecting an asset that genuinely exists, not just a missing file.
        assertThat(status("/bootui/assets/" + packagedAssetName())).isEqualTo(404);
        // Spring resolves static resources against the decoded path, so the encoded spellings reach
        // the same bundle entry and must be rejected too.
        assertThat(status("/%62ootui/index.html")).isEqualTo(404);
        assertThat(status("/bootui;version=1/index.html")).isEqualTo(404);
    }

    static String packagedAssetName() throws Exception {
        Resource[] assets = new PathMatchingResourcePatternResolver()
                .getResources("classpath:/META-INF/resources/bootui/assets/index-*.js");
        assertThat(assets).isNotEmpty();
        return assets[0].getFilename();
    }

    @Test
    void hostApplicationKeepsServingItsOwnStaticResources() throws Exception {
        assertThat(status("/")).isEqualTo(200);
        assertThat(status("/index.html")).isEqualTo(200);
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
