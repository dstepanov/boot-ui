package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the JVM Tuning panel ({@code GET /bootui/api/jvm-tuning}).
 *
 * <p>Passive: it renders the same shared sizing model as {@link LiveMemoryController} from the same
 * provider, under the tuning-oriented framing the shared UI binds to at this path.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/jvm-tuning")
public class JvmTuningController {

    private final MemoryReportProvider provider;

    public JvmTuningController(MemoryReportProvider provider) {
        this.provider = provider;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public LiveMemoryReport jvmTuning(
            @QueryValue @Nullable Long totalMemoryMb,
            @QueryValue @Nullable Integer threadCount,
            @QueryValue @Nullable Integer headRoomPercent,
            @QueryValue @Nullable Boolean kubernetesBurstableEnabled,
            @QueryValue @Nullable Boolean kubernetesActuatorEnabled) {
        return provider.buildReport(
                totalMemoryMb, threadCount, headRoomPercent, kubernetesBurstableEnabled, kubernetesActuatorEnabled);
    }
}
