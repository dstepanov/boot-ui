package io.github.jdubois.bootui.micronaut.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.mcp.BootUiMcpFactory;
import io.github.jdubois.bootui.micronaut.mcp.McpServerState;
import io.github.jdubois.bootui.micronaut.mcp.MicronautMcpEnvelope;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.ByteArrayInputStream;

/**
 * The MCP JSON-RPC transport ({@code POST /bootui/api/mcp}) an AI agent connects to.
 *
 * <p>A thin transport shell over the shared engine dispatcher: this class owns only the protocol-version
 * check, the payload bound, and the JSON-RPC envelope shapes. Every decision that matters — which tools
 * exist, whether a panel allows the call, the result and concurrency bounds — belongs to the engine, so an
 * agent sees identical behavior on every stack.
 *
 * <p>The server answers a disabled error rather than a 404 when it is switched off, so an agent that
 * connects learns the server exists and is off rather than that the endpoint is missing. Batched requests
 * are refused deliberately: the bounds BootUI enforces are per-call, and a batch would let one request
 * multiply them.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/mcp")
@ExecuteOn(TaskExecutors.BLOCKING)
public class McpBridgeController {

    private static final String PAYLOAD_LIMIT_MESSAGE = "Request payload exceeds limit";

    private final McpServerState state;
    private final MicronautMcpEnvelope envelope;
    private final int maxPayloadBytes;

    public McpBridgeController(McpServerState state, MicronautMcpEnvelope envelope, Environment environment) {
        this.state = state;
        this.envelope = envelope;
        this.maxPayloadBytes = BootUiMcpFactory.maxPayloadBytes(environment);
    }

    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> rpc(
            @Body @Nullable byte[] requestBody,
            @Header(value = McpProtocol.PROTOCOL_VERSION_HEADER, defaultValue = "") @Nullable String protocolVersion) {
        if (protocolVersion != null
                && !protocolVersion.isBlank()
                && !McpProtocol.KNOWN_VERSIONS.contains(protocolVersion)) {
            return json(
                    400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_MESSAGE));
        }
        byte[] payload;
        try {
            payload = McpPayloadReader.read(
                    new ByteArrayInputStream(requestBody == null ? new byte[0] : requestBody), maxPayloadBytes);
        } catch (McpPayloadReader.PayloadTooLargeException ex) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        JsonNode request;
        try {
            request = envelope.readTree(payload);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        if (request != null && request.isArray()) {
            return json(400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE));
        }
        if (!state.isEnabled()) {
            if (isNotification(request)) {
                return HttpResponse.accepted();
            }
            return json(200, envelope.disabledError(request));
        }
        JsonNode response = envelope.handle(request);
        if (response == null) {
            return HttpResponse.accepted();
        }
        return json(200, response);
    }

    /** BootUI's MCP transport is request/response only; there is no server-initiated stream to open. */
    @Get
    public HttpResponse<?> getStream() {
        return HttpResponse.status(HttpStatus.METHOD_NOT_ALLOWED);
    }

    private static boolean isNotification(JsonNode request) {
        return request != null
                && request.isObject()
                && !request.hasNonNull("id")
                && McpProtocol.JSONRPC_VERSION.equals(request.path("jsonrpc").asText())
                && !request.path("method").asText().isBlank();
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        response.set("error", error);
        return response;
    }

    /**
     * Writes an already-rendered envelope as raw bytes with an explicit JSON content type.
     *
     * <p>Deliberately not {@code body(JsonNode)}: that would leave the encoding to the application's JSON
     * stack, and a Jackson {@code JsonNode} is a Jackson-databind type that Micronaut Serde cannot write.
     * The envelope serializes with BootUI's own mapper, so the JSON-RPC wire format is the same on every
     * host regardless of which JSON stack it runs.
     */
    private HttpResponse<byte[]> json(int status, JsonNode body) {
        return HttpResponse.status(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body(envelope.toBytes(body));
    }
}
