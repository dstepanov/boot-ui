package io.github.jdubois.bootui.micronaut.mcp;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import io.github.jdubois.bootui.engine.mcp.McpToolClientExceptions;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.exceptions.HttpStatusException;
import java.util.function.Function;

/**
 * Adapts what a Micronaut controller method returns or throws into what the framework-free MCP dispatcher
 * understands. This is the Micronaut counterpart of the Spring and Quarkus translations, so a tool that
 * refuses a request behaves identically on every stack.
 *
 * <p>MCP tools delegate to the very same controller methods the REST API uses, and those methods speak
 * Micronaut's HTTP vocabulary: many return an {@link HttpResponse} rather than a bare payload, and some
 * signal a client error by returning a 4xx response instead of throwing. Two translations are therefore
 * needed, and doing them here — at the single point where every tool is registered — is what stops a tool
 * from reporting an ordinary bad request to the agent as a detail-free internal error:
 *
 * <ul>
 *   <li>an {@link HttpResponse} is unwrapped to its body, so the agent receives the payload rather than a
 *       serialized transport envelope;</li>
 *   <li>a 4xx — whether returned as a response or thrown as an {@link HttpStatusException} — becomes an
 *       in-band {@link McpToolClientException} carrying that status and reason.</li>
 * </ul>
 *
 * <p>Only 4xx failures are translated: a 5xx is a genuine server fault and keeps its original throwable so
 * it is logged and reported as an internal error, as does every other exception.
 */
public final class MicronautMcpToolFailures {

    private MicronautMcpToolFailures() {}

    /** Wraps an MCP tool handler with both translations described in this class's documentation. */
    public static Function<McpArguments, Object> translating(Function<McpArguments, Object> handler) {
        return arguments -> {
            Object result;
            try {
                result = handler.apply(arguments);
            } catch (HttpStatusException failure) {
                int status = failure.getStatus().getCode();
                if (!McpToolClientExceptions.isClientError(status)) {
                    throw failure;
                }
                throw new McpToolClientException(status, failure.getMessage());
            }
            return unwrap(result);
        };
    }

    /**
     * Unwraps a controller's transport envelope. A 4xx response is turned into a client error rather than
     * handed back as a body the agent would have to interpret; anything else yields its body.
     */
    private static Object unwrap(Object result) {
        if (!(result instanceof HttpResponse<?> response)) {
            return result;
        }
        int status = response.getStatus().getCode();
        if (McpToolClientExceptions.isClientError(status)) {
            throw new McpToolClientException(status, response.getStatus().getReason());
        }
        return response.body();
    }
}
