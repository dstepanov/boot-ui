package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.micronaut.MicronautPanelAvailability;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the BootUI panel manifest ({@code GET /bootui/api/panels}).
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code PanelsController} and the Quarkus adapter's
 * {@code PanelsResource}. It returns the shared panel registry with Micronaut-specific availability so the
 * shared Vue UI renders an identical sidebar.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/panels")
public class PanelsController {

    private final MicronautPanelAvailability availability;

    public PanelsController(MicronautPanelAvailability availability) {
        this.availability = availability;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public PanelsReport panels() {
        return availability.manifest();
    }
}
