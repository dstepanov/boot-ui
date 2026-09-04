package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Exercises the console's two access filters against a <em>real</em> server, so what is pinned is the
 * behavior a browser or a script actually sees — not the engine policy in isolation (which
 * {@code bootui-engine}'s own tests cover), but that the Micronaut bindings put that policy in front of
 * every request, in the right order, with the canonical bodies and headers intact.
 *
 * <p>Each test boots its own context because the settings under test ({@code micronaut.server.context-path},
 * {@code bootui.panels.*}, {@code bootui.read-only}) change how the console mounts and what it refuses.
 */
class BootUiMicronautSafetyEndToEndTest {

    // -----------------------------------------------------------------------
    // The three localhost-guard defenses, through the Micronaut binding
    // -----------------------------------------------------------------------

    /**
     * DNS-rebinding defense: a {@code Host} header that is neither a built-in loopback name nor on
     * {@code bootui.allowed-hosts} is refused with the engine's canonical message, before any controller.
     */
    @Test
    void rejectsADisallowedHostHeaderWithTheCanonicalBody() {
        withServer(Map.of(), (client) -> {
            HttpResponse<?> response =
                    exchange(client, HttpRequest.GET("/bootui/api/panels").header("Host", "attacker.example"));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(response)).isEqualTo("{\"error\":\"" + LocalhostGuard.MESSAGE_DISALLOWED_HOST + "\"}");
        });
    }

    /** A host explicitly allow-listed by the operator is let through again, so the defense is configurable. */
    @Test
    void acceptsAHostHeaderTheOperatorAllowListed() {
        withServer(Map.of(BootUiMicronautSafetyFilter.ALLOWED_HOSTS_KEY, "console.internal"), (client) -> {
            HttpResponse<?> response =
                    exchange(client, HttpRequest.GET("/bootui/api/panels").header("Host", "console.internal"));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        });
    }

    /** CSRF defense, {@code Origin} form: a write whose Origin host differs from the request host is refused. */
    @Test
    void rejectsACrossSiteWriteSignalledByOriginWithTheCanonicalBody() {
        withServer(Map.of(), (client) -> {
            HttpResponse<?> response = exchange(
                    client,
                    HttpRequest.POST("/bootui/api/sql-trace/clear", "{}").header("Origin", "http://evil.example"));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(response)).isEqualTo("{\"error\":\"" + LocalhostGuard.MESSAGE_CROSS_SITE_WRITE + "\"}");
        });
    }

    /** CSRF defense, fetch-metadata form: {@code Sec-Fetch-Site: cross-site} alone is enough to refuse a write. */
    @Test
    void rejectsACrossSiteWriteSignalledBySecFetchSite() {
        withServer(Map.of(), (client) -> {
            HttpResponse<?> response = exchange(
                    client,
                    HttpRequest.POST("/bootui/api/sql-trace/clear", "{}").header("Sec-Fetch-Site", "cross-site"));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(response)).isEqualTo("{\"error\":\"" + LocalhostGuard.MESSAGE_CROSS_SITE_WRITE + "\"}");
        });
    }

    /**
     * The CSRF defense covers state-changing methods only: a cross-site {@code GET} is a read, and refusing
     * it would break the supported Vite dev-proxy setup without adding protection.
     */
    @Test
    void leavesCrossSiteReadsAlone() {
        withServer(Map.of(), (client) -> {
            HttpResponse<?> response =
                    exchange(client, HttpRequest.GET("/bootui/api/panels").header("Sec-Fetch-Site", "cross-site"));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        });
    }

    // -----------------------------------------------------------------------
    // Security headers
    // -----------------------------------------------------------------------

    /**
     * The shared header policy is applied to the whole console surface: the same security headers on the UI
     * shell and the API, with the cache directive differentiated by response class
     * ({@code no-cache} for the shell, {@code no-store} for data).
     */
    @Test
    void appliesTheSharedSecurityHeaderPolicyToTheUiAndTheApi() {
        withServer(Map.of(), (client) -> {
            for (String path : new String[] {"/bootui", "/bootui/api/panels"}) {
                var headers = exchange(client, HttpRequest.GET(path)).getHeaders();

                assertThat(headers.get(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                        .as("CSP on %s", path)
                        .isEqualTo(BootUiSecurityHeaders.CSP_VALUE);
                assertThat(headers.get(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS))
                        .as("nosniff on %s", path)
                        .isEqualTo(BootUiSecurityHeaders.NOSNIFF);
                assertThat(headers.get(BootUiSecurityHeaders.X_FRAME_OPTIONS))
                        .as("frame options on %s", path)
                        .isEqualTo(BootUiSecurityHeaders.DENY);
                assertThat(headers.get(BootUiSecurityHeaders.REFERRER_POLICY))
                        .as("referrer policy on %s", path)
                        .isEqualTo(BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
                assertThat(headers.get(BootUiSecurityHeaders.PERMISSIONS_POLICY))
                        .as("permissions policy on %s", path)
                        .isEqualTo(BootUiSecurityHeaders.PERMISSIONS_POLICY_VALUE);
                assertThat(headers.get(BootUiSecurityHeaders.PRAGMA))
                        .as("pragma on %s", path)
                        .isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
            }

            assertThat(exchange(client, HttpRequest.GET("/bootui"))
                            .getHeaders()
                            .get(BootUiSecurityHeaders.CACHE_CONTROL))
                    .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
            assertThat(exchange(client, HttpRequest.GET("/bootui/api/panels"))
                            .getHeaders()
                            .get(BootUiSecurityHeaders.CACHE_CONTROL))
                    .isEqualTo(BootUiSecurityHeaders.NO_STORE);
        });
    }

    /** A rejection is a console response too, so it carries the same headers as a served one. */
    @Test
    void appliesTheSecurityHeadersToRejections() {
        withServer(Map.of(), (client) -> {
            var headers = exchange(client, HttpRequest.GET("/bootui/api/panels").header("Host", "attacker.example"))
                    .getHeaders();

            assertThat(headers.get(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                    .isEqualTo(BootUiSecurityHeaders.CSP_VALUE);
            assertThat(headers.get(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_STORE);
        });
    }

    // -----------------------------------------------------------------------
    // A non-default micronaut.server.context-path
    // -----------------------------------------------------------------------

    /**
     * With {@code micronaut.server.context-path=/app} the controllers mount under the prefix, and the
     * filters strip it before matching — so the console is both <em>served</em> and <em>guarded</em> at its
     * prefixed location. Without the strip the guard would silently fail open there.
     */
    @Test
    void servesAndGuardsTheConsoleUnderANonDefaultContextPath() {
        withServer(Map.of(MicronautContextPath.CONTEXT_PATH_KEY, "/app"), (client) -> {
            assertThat((Object) exchange(client, HttpRequest.GET("/app/bootui")).getStatus())
                    .isEqualTo(HttpStatus.OK);
            assertThat((Object) exchange(client, HttpRequest.GET("/app/bootui/api/panels"))
                            .getStatus())
                    .isEqualTo(HttpStatus.OK);

            HttpResponse<?> rejected = exchange(
                    client,
                    HttpRequest.POST("/app/bootui/api/sql-trace/clear", "{}").header("Origin", "http://evil.example"));
            assertThat((Object) rejected.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(rejected)).isEqualTo("{\"error\":\"" + LocalhostGuard.MESSAGE_CROSS_SITE_WRITE + "\"}");

            assertThat(exchange(client, HttpRequest.GET("/app/bootui/api/panels"))
                            .getHeaders()
                            .get(BootUiSecurityHeaders.CACHE_CONTROL))
                    .as("the API cache policy must follow the prefixed API mount")
                    .isEqualTo(BootUiSecurityHeaders.NO_STORE);
        });
    }

    // -----------------------------------------------------------------------
    // Panel access gating
    // -----------------------------------------------------------------------

    /** A disabled panel answers the canonical panel-denied body, identical to Spring and Quarkus. */
    @Test
    void rejectsADisabledPanelWithTheCanonicalBody() {
        withServer(Map.of("bootui.panels.beans.enabled", "false"), (client) -> {
            HttpResponse<?> response = exchange(client, HttpRequest.GET("/bootui/api/beans"));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(response))
                    .isEqualTo("{\"error\":\"BootUI panel access denied\",\"panel\":\"beans\","
                            + "\"reason\":\"Panel is disabled via bootui.panels.beans.enabled=false\"}");

            assertThat((Object) exchange(client, HttpRequest.GET("/bootui/api/mappings"))
                            .getStatus())
                    .as("disabling one panel must not touch the others")
                    .isEqualTo(HttpStatus.OK);
        });
    }

    /**
     * {@code bootui.read-only=true} refuses every action while leaving reads working — the mode an operator
     * uses to hand out a look-but-don't-touch console.
     */
    @Test
    void refusesActionsButKeepsReadsWorkingWhenGloballyReadOnly() {
        withServer(Map.of(MicronautPanelAccessConfig.GLOBAL_READ_ONLY_KEY, "true"), (client) -> {
            HttpResponse<?> loggerWrite =
                    exchange(client, HttpRequest.POST("/bootui/api/loggers/ROOT", Map.of("level", "DEBUG")));
            assertThat((Object) loggerWrite.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(loggerWrite))
                    .isEqualTo("{\"error\":\"BootUI panel access denied\",\"panel\":\"loggers\","
                            + "\"reason\":\"BootUI is read-only via bootui.read-only=true\"}");

            HttpResponse<?> sqlTraceClear = exchange(client, HttpRequest.POST("/bootui/api/sql-trace/clear", "{}"));
            assertThat((Object) sqlTraceClear.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(sqlTraceClear)).contains("\"reason\":\"BootUI is read-only via bootui.read-only=true\"");

            assertThat((Object) exchange(client, HttpRequest.GET("/bootui/api/loggers"))
                            .getStatus())
                    .as("reads stay available in read-only mode")
                    .isEqualTo(HttpStatus.OK);
            assertThat((Object) exchange(client, HttpRequest.GET("/bootui/api/sql-trace"))
                            .getStatus())
                    .isEqualTo(HttpStatus.OK);
        });
    }

    /**
     * Without the read-only switch the very same action succeeds, so the test above pins gating, not
     * breakage.
     *
     * <p>The level is restored afterwards, because this write is not scoped to the test: it reconfigures the
     * root logger of the whole surefire fork. Left at {@code DEBUG} it made every test that ran after this
     * one emit Micronaut's bean-resolution chatter — tens of thousands of lines that buried the actual
     * results and defeated {@code logback-test.xml}.
     */
    @Test
    void allowsTheSameActionWhenNotReadOnly() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();
        try {
            withServer(
                    Map.of(),
                    (client) -> assertThat((Object) exchange(
                                            client,
                                            HttpRequest.POST("/bootui/api/loggers/ROOT", Map.of("level", "DEBUG")))
                                    .getStatus())
                            .isEqualTo(HttpStatus.OK));
            assertThat(root.getLevel())
                    .as("the write must really have reached Logback")
                    .isEqualTo(Level.DEBUG);
        } finally {
            root.setLevel(original);
        }
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private static void withServer(Map<String, Object> properties, Consumer<HttpClient> assertions) {
        Map<String, Object> effective = new java.util.LinkedHashMap<>(properties);
        effective.put("micronaut.server.port", -1);
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, effective, "test");
                HttpClient client = HttpClient.create(server.getURL())) {
            assertions.accept(client);
        }
    }

    /** Performs the exchange, unwrapping the exception Micronaut's blocking client raises for a 4xx. */
    private static HttpResponse<?> exchange(HttpClient client, HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException failure) {
            return failure.getResponse();
        }
    }

    private static String bodyOf(HttpResponse<?> response) {
        return response.getBody(String.class).orElse(null);
    }
}
