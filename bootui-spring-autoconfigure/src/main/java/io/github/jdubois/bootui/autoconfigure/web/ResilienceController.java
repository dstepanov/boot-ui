package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.ResilienceReport;
import io.github.jdubois.bootui.engine.resilience.ResilienceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral Resilience controller. It serves {@code GET /bootui/api/resilience} by delegating to the
 * engine {@link ResilienceService}, which merges the declared policies reported by every available provider
 * with the bounded, metadata-only capture buffer.
 *
 * <p>The panel is read-only by design, so this controller exposes no write mapping at all: BootUI never
 * opens, closes, resets or otherwise mutates a resilience policy. Unlike the scheduling panel it carries no
 * class-level {@code @ConditionalOnClass}, because three different libraries (any of which may be absent)
 * can back it — the service answers {@code resiliencePresent: false} with an explicit reason when none is
 * there, which is what the panel renders as an unavailable state.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/resilience")
public class ResilienceController {

    private final ResilienceService resilienceService;

    public ResilienceController(ResilienceService resilienceService) {
        this.resilienceService = resilienceService;
    }

    @GetMapping
    public ResilienceReport resilience() {
        return resilienceService.report();
    }
}
