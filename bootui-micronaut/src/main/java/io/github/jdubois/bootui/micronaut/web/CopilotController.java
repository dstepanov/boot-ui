package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.agent.MicronautCopilotSessionStore;
import io.micronaut.http.annotation.Controller;

/** Copilot panel endpoints ({@code /bootui/api/copilot}); all shared logic lives in the engine store. */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/copilot")
public class CopilotController extends AbstractAgentSessionController {

    public CopilotController(MicronautCopilotSessionStore store, MicronautExposurePolicy exposure) {
        super(store, exposure);
    }
}
