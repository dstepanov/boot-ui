package io.github.jdubois.bootui.micronaut.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@code bootui.mcp.max-payload-bytes} is the bound the MCP transport actually enforces.
 *
 * <p>Two things need pinning, and they need different assertions.
 *
 * <p><strong>That the limit is enforced at all.</strong> The limit is set very low here and the assertions
 * bracket it: a body under it is read and dispatched, one over it is refused with {@code 413} — both far
 * below any server-level cap, so neither result can come from the server's own limit.
 *
 * <p><strong>That the reader is what enforces it.</strong> The behavioural assertions above cannot tell:
 * {@link McpPayloadReader} bounds what it reads, but handing it a materialised {@code byte[]} rejects the
 * same bodies with the same status, having already buffered them in full — the effective ceiling then
 * silently becoming the host's {@code micronaut.server.max-request-size} (10 MiB by default, and whatever an
 * application that accepts uploads raised it to). The difference is memory, not response, so what is
 * asserted instead is the binding: the body parameter must be an {@link InputStream}, the same one the
 * Quarkus twin {@code McpBridgeResource} takes, and the reason this route runs on the blocking executor.
 */
class McpBridgePayloadBoundTest {

    private static final String PAYLOAD_LIMIT_KEY = "bootui.mcp.max-payload-bytes";

    private static final int LIMIT = 256;

    @Test
    void refusesAPayloadOverTheConfiguredBoundRatherThanTheServersOwnLimit() {
        withServer(Map.of(PAYLOAD_LIMIT_KEY, LIMIT), client -> {
            HttpResponse<?> response = post(client, request(LIMIT * 4));

            assertThat((Object) response.getStatus())
                    .as("a body over bootui.mcp.max-payload-bytes but far under micronaut.server.max-request-size")
                    .isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        });
    }

    /**
     * The structural half of the pair above: a body bound as an array is a payload bound that was already
     * exceeded before it was checked, and no response-level assertion can see the difference.
     */
    @Test
    void bindsTheRequestBodyAsAStreamSoTheReaderIsWhatBoundsIt() throws Exception {
        Method rpc = Stream.of(McpBridgeController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("rpc"))
                .findFirst()
                .orElseThrow();
        List<Class<?>> bodyParameters = Stream.of(rpc.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(Body.class))
                .map(Parameter::getType)
                .toList();

        assertThat(bodyParameters)
                .as("the MCP body must be bound as a stream, so McpPayloadReader can stop at "
                        + "bootui.mcp.max-payload-bytes instead of checking an array the server already buffered")
                .containsExactly(InputStream.class);
    }

    @Test
    void readsAPayloadUnderTheConfiguredBound() {
        withServer(Map.of(PAYLOAD_LIMIT_KEY, LIMIT), client -> {
            HttpResponse<?> response = post(client, request(16));

            assertThat((Object) response.getStatus())
                    .as("a body under the bound must reach the dispatcher, not be refused by the reader")
                    .isNotEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        });
    }

    /** A syntactically valid JSON-RPC request padded to {@code size} bytes with a filler member. */
    private static String request(int size) {
        String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"padding\":\"";
        String tail = "\"}";
        int padding = Math.max(0, size - head.length() - tail.length());
        return head + "x".repeat(padding) + tail;
    }

    private static HttpResponse<?> post(HttpClient client, String body) {
        try {
            return client.toBlocking()
                    .exchange(HttpRequest.POST("/bootui/api/mcp", body).contentType(MediaType.APPLICATION_JSON));
        } catch (HttpClientResponseException ex) {
            return ex.getResponse();
        }
    }

    private static void withServer(Map<String, Object> properties, Consumer<HttpClient> assertions) {
        Map<String, Object> effective = new java.util.LinkedHashMap<>(properties);
        effective.put("micronaut.server.port", -1);
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, effective, "test");
                HttpClient client = HttpClient.create(server.getURL())) {
            assertions.accept(client);
        }
    }
}
