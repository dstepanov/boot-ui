package io.github.jdubois.bootui.quarkus.it;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.quarkus.rabbit.QuarkusRabbitConsumerCapture;
import io.github.jdubois.bootui.quarkus.rabbit.QuarkusRabbitProducerCapture;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.Test;

/**
 * Capability-present, broker-free Quarkus RabbitMQ integration coverage.
 *
 * <p>The RabbitMQ extension is present so the deployment build step must register both interceptors and
 * mark the panel available. Synthetic SmallRye metadata drives those injected interceptors directly; no
 * channel is configured, so no connector, Dev Service, broker port, network call, or timing wait is used.
 */
@QuarkusTest
class BootUiQuarkusRabbitCaptureTest {

    private static final String RAW_CORRELATION_ID = "customer-secret";
    private static final String SENSITIVE_PAYLOAD = "payload-secret";
    private static final String SENSITIVE_METADATA = "header-secret";

    @TestHTTPResource
    URL baseUrl;

    @Inject
    QuarkusRabbitProducerCapture producerCapture;

    @Inject
    QuarkusRabbitConsumerCapture consumerCapture;

    @Inject
    RabbitActivityRecorder recorder;

    @Test
    void capturesBothDirectionsThroughTheSharedReportAndKeepsJmsUnavailable() {
        producerCapture.onMessageAck(outgoingMessage());
        Message<?> consumed = consumerCapture.afterMessageReceive(incomingMessage());
        consumerCapture.onMessageNack(consumed, new IllegalStateException(SENSITIVE_PAYLOAD));
        assertThat(recorder.recent()).hasSize(2);

        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).isEqualTo(200);
        JsonNode rabbitPanel = findPanel(panels.json(), "rabbitmq");
        assertThat(rabbitPanel).isNotNull();
        assertThat(rabbitPanel.path("available").asBoolean(false)).isTrue();
        JsonNode jmsPanel = findPanel(panels.json(), "jms");
        assertThat(jmsPanel).isNotNull();
        assertThat(jmsPanel.path("available").asBoolean(true)).isFalse();
        assertThat(jmsPanel.path("unavailableReason").asText()).startsWith("Not yet available on Quarkus");

        Response report = probe().get("/bootui/api/rabbitmq");
        assertThat(report.status()).isEqualTo(200);
        JsonNode root = report.json();
        assertThat(root.path("available").asBoolean(false)).isTrue();
        assertThat(root.path("capturing").asBoolean(false)).isTrue();
        assertThat(root.path("captureCorrelationIdEnabled").asBoolean(false)).isTrue();
        assertThat(root.path("total").asInt()).isEqualTo(2);
        assertThat(root.path("totalCaptured").asLong()).isEqualTo(2);

        JsonNode consumedDto = root.path("messages").path(0);
        assertThat(consumedDto.path("direction").asText()).isEqualTo("CONSUME");
        assertThat(consumedDto.path("exchange").asText()).isEqualTo("orders");
        assertThat(consumedDto.path("routingKey").asText()).isEqualTo("created");
        assertThat(consumedDto.path("success").asBoolean(true)).isFalse();
        assertThat(consumedDto.path("errorMessage").asText()).isEqualTo("Message processing failed");
        assertThat(consumedDto.path("durationMillis").isNumber()).isTrue();

        JsonNode publishedDto = root.path("messages").path(1);
        assertThat(publishedDto.path("direction").asText()).isEqualTo("PUBLISH");
        assertThat(publishedDto.path("routingKey").asText()).isEqualTo("created");
        assertThat(publishedDto.path("success").asBoolean(false)).isTrue();
        assertThat(publishedDto.path("correlationId").asText()).hasSize(16).isNotEqualTo(RAW_CORRELATION_ID);
        assertThat(report.body())
                .doesNotContain(RAW_CORRELATION_ID)
                .doesNotContain(SENSITIVE_PAYLOAD)
                .doesNotContain(SENSITIVE_METADATA);

        Response clear = probe().request("DELETE", "/bootui/api/rabbitmq", Map.of(), null);
        assertThat(clear.status()).isEqualTo(204);
        JsonNode afterClear = probe().get("/bootui/api/rabbitmq").json();
        assertThat(afterClear.path("total").asInt(-1)).isZero();
        assertThat(afterClear.path("totalCaptured").asLong()).isEqualTo(2);
    }

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    private static Message<String> outgoingMessage() {
        OutgoingRabbitMQMetadata metadata = OutgoingRabbitMQMetadata.builder()
                .withRoutingKey("created")
                .withCorrelationId(RAW_CORRELATION_ID)
                .build();
        return Message.of(
                SENSITIVE_PAYLOAD, Metadata.of(metadata, new ArbitraryMetadata(SENSITIVE_METADATA.getBytes(UTF_8))));
    }

    private static Message<String> incomingMessage() {
        IncomingRabbitMQMetadata metadata = mock(IncomingRabbitMQMetadata.class);
        when(metadata.getExchange()).thenReturn("orders");
        when(metadata.getRoutingKey()).thenReturn("created");
        when(metadata.getCorrelationId()).thenReturn(Optional.of(RAW_CORRELATION_ID));
        return Message.of(
                SENSITIVE_PAYLOAD, Metadata.of(metadata, new ArbitraryMetadata(SENSITIVE_METADATA.getBytes(UTF_8))));
    }

    private static JsonNode findPanel(JsonNode manifest, String id) {
        for (JsonNode panel : manifest.path("panels")) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        return null;
    }

    private record ArbitraryMetadata(byte[] value) {}
}
