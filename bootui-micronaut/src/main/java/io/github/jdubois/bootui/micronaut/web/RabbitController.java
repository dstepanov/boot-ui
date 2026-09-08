package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.RabbitReport;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitMessageDtos;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Read/clear API for the dedicated RabbitMQ panel ({@code GET /bootui/api/rabbitmq} and the clear action) —
 * the Micronaut twin of the Spring adapter's {@code RabbitController} and the Quarkus adapter's
 * {@code RabbitResource}, over the same shared engine {@link RabbitActivityRecorder} and the same wire
 * contract.
 *
 * <p>The recorder is framework-neutral and always registered (see {@code BootUiEngineFactory}), so this
 * controller exists whether or not the application uses RabbitMQ; without {@code micronaut-rabbitmq} it
 * reports the panel unavailable rather than showing an empty message list that could be mistaken for
 * "nothing was published" — the same shape as {@link EmailController}.
 *
 * <p>This is a view over the very buffer that feeds Live Activity's {@code MESSAGING} entries (there is only
 * ever one buffer), so the two are always in sync and {@link #clear()} clears both at once. {@code GET} is
 * passive; only {@code DELETE} mutates state, gated by the shared safety filter's write floor and the
 * {@code rabbitmq} panel's read-only toggle exactly as on the other stacks.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/rabbitmq")
public class RabbitController {

    static final String RABBIT_ABSENT_REASON =
            "Not available: micronaut-rabbitmq is not on the classpath. Add it (with a @RabbitClient or a"
                    + " @RabbitListener) to see the messages this application publishes and receives.";

    private final RabbitActivityRecorder recorder;
    private final boolean rabbitPresent;
    private final int maxEntries;

    public RabbitController(RabbitActivityRecorder recorder, Environment environment) {
        this.recorder = recorder;
        this.rabbitPresent = isPresent();
        this.maxEntries = environment
                .getProperty("bootui.rabbitmq.max-entries", Integer.class)
                .orElse(200);
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public RabbitReport list() {
        if (!rabbitPresent) {
            return RabbitReport.unavailable(RABBIT_ABSENT_REASON, maxEntries);
        }
        return RabbitMessageDtos.toReport(recorder);
    }

    @Delete
    public HttpResponse<?> clear() {
        recorder.clear();
        return HttpResponse.noContent();
    }

    /** Whether micronaut-rabbitmq is on the classpath at all; without it nothing can ever be captured. */
    private static boolean isPresent() {
        try {
            Class.forName("io.micronaut.rabbitmq.connect.ChannelPool", false, RabbitController.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
