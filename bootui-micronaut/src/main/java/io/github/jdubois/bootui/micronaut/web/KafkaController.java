package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.KafkaReport;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaMessageDtos;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Read/clear API for the dedicated Kafka panel ({@code GET /bootui/api/kafka} and the clear action) — the
 * Micronaut twin of the Spring adapter's {@code KafkaController} and the Quarkus adapter's
 * {@code KafkaResource}, over the same shared engine {@link KafkaActivityRecorder} and the same wire
 * contract.
 *
 * <p>The recorder is framework-neutral and always registered (see {@code BootUiEngineFactory}), so this
 * controller exists whether or not the application uses Kafka; without {@code micronaut-kafka} it reports the
 * panel unavailable rather than showing an empty topic list that could be mistaken for "nothing was sent" —
 * the same shape as {@link EmailController}, and mirroring how the Spring controller checks for a
 * {@code KafkaTemplate} bean.
 *
 * <p>This is a view over the very buffer that feeds Live Activity's {@code MESSAGING} entries (there is only
 * ever one buffer), so the two are always in sync and {@link #clear()} clears both at once. {@code GET} is
 * passive; only {@code DELETE} mutates state, gated by the shared safety filter's write floor and the
 * {@code kafka} panel's read-only toggle exactly as on the other stacks.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/kafka")
public class KafkaController {

    static final String KAFKA_ABSENT_REASON =
            "Not available: micronaut-kafka is not on the classpath. Add it (with a @KafkaClient or a"
                    + " @KafkaListener) to see the messages this application publishes and receives.";

    private final KafkaActivityRecorder recorder;
    private final boolean kafkaPresent;
    private final int maxEntries;

    public KafkaController(KafkaActivityRecorder recorder, Environment environment) {
        this.recorder = recorder;
        this.kafkaPresent = isPresent();
        this.maxEntries = environment
                .getProperty("bootui.kafka.max-entries", Integer.class)
                .orElse(200);
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public KafkaReport list() {
        if (!kafkaPresent) {
            return KafkaReport.unavailable(KAFKA_ABSENT_REASON, maxEntries);
        }
        return KafkaMessageDtos.toReport(recorder);
    }

    @Delete
    public HttpResponse<?> clear() {
        recorder.clear();
        return HttpResponse.noContent();
    }

    /** Whether micronaut-kafka is on the classpath at all; without it nothing can ever be captured. */
    private static boolean isPresent() {
        try {
            Class.forName(
                    "io.micronaut.configuration.kafka.KafkaProducerFactory",
                    false,
                    KafkaController.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
