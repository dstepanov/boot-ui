package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.agent.MicronautClaudeCodeSessionStore;
import io.micronaut.http.annotation.Controller;

/** Claude Code panel endpoints ({@code /bootui/api/claude-code}); shared logic lives in the engine store. */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/claude-code")
public class ClaudeCodeController extends AbstractAgentSessionController {

    public ClaudeCodeController(MicronautClaudeCodeSessionStore store, MicronautExposurePolicy exposure) {
        super(store, exposure);
    }
}
