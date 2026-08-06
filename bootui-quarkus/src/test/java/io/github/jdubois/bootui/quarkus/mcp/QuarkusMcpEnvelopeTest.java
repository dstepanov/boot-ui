package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QuarkusMcpEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolHandlerExceptionReturnsCanonicalInternalErrorWithoutSensitiveDetails() {
        IllegalStateException failure =
                new IllegalStateException("token=ghp_secret jdbc:postgresql://localhost/private /home/admin/key.pem");
        McpTool tool = tool(args -> {
            throw failure;
        });
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callRequest(41));

        assertThat(response.toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":41,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}")
                .doesNotContain("ghp_secret", "jdbc:postgresql", "/home/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void toolHandlerExceptionForNotificationProducesNoBodyAndIsReportedOnce() {
        IllegalStateException failure =
                new IllegalStateException("SENSITIVE_NOTIFICATION_SENTINEL /private/quarkus/runtime/path");
        McpTool tool = tool(args -> {
            throw failure;
        });
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callNotification());

        assertThat(response).isNull();
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.failure().getMessage())
                .as("the original detail remains available only to server diagnostics")
                .contains("SENSITIVE_NOTIFICATION_SENTINEL");
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void toolResultSerializationFailureReturnsCanonicalInternalErrorAndIsReportedOnce() {
        McpTool tool = tool(args -> new Object() {
            @SuppressWarnings("unused")
            public String getValue() {
                throw new IllegalStateException("password=hunter2 SELECT * FROM secrets /Users/admin/private.txt");
            }
        });
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callRequest(42));

        assertThat(response.toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":42,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}")
                .doesNotContain("hunter2", "SELECT", "/Users/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isNotNull();
        assertThat(diagnostics.operation()).isEqualTo("serializing a tool result");
    }

    @Test
    void expectedUnknownToolErrorRetainsItsActionableMessageWithoutDiagnostics() {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool(args -> "ok"), diagnostics);
        ObjectNode request = callRequest(43);
        ((ObjectNode) request.path("params")).put("name", "does_not_exist");

        JsonNode response = envelope.handle(request);

        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpProtocol.INVALID_PARAMS);
        assertThat(response.path("error").path("message").asText()).isEqualTo("Unknown tool: does_not_exist");
        assertThat(diagnostics.count()).isZero();
    }

    private QuarkusMcpEnvelope envelope(McpTool tool, RecordingFailureReporter diagnostics) {
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(tool), List.of(), new AllowAllPolicy(), "1.2.3", "instructions", 50, 20, diagnostics);
        return new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics);
    }

    private static McpTool tool(
            java.util.function.Function<io.github.jdubois.bootui.engine.mcp.McpArguments, Object> handler) {
        return new McpTool(
                "get_overview", "Read the overview.", McpToolSchema.NONE, BootUiPanels.OVERVIEW, false, handler);
    }

    private static ObjectNode callRequest(int id) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "tools/call");
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "get_overview");
        params.set("arguments", JsonNodeFactory.instance.objectNode());
        request.set("params", params);
        return request;
    }

    private static ObjectNode callNotification() {
        ObjectNode request = callRequest(0);
        request.remove("id");
        return request;
    }

    private static final class AllowAllPolicy implements McpPanelPolicy {

        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return "disabled";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "read-only";
        }
    }

    private static final class RecordingFailureReporter extends QuarkusMcpFailureReporter {
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicReference<String> operation = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void report(String operation, Throwable failure) {
            count.incrementAndGet();
            this.operation.set(operation);
            this.failure.set(failure);
        }

        private int count() {
            return count.get();
        }

        private String operation() {
            return operation.get();
        }

        private Throwable failure() {
            return failure.get();
        }
    }
}
