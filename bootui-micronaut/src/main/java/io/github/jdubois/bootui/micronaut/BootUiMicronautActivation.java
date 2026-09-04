package io.github.jdubois.bootui.micronaut;

import java.util.List;

/**
 * Resolved BootUI activation state for the Micronaut adapter — the analogue of the Spring adapter's
 * {@code BootUiActivation}, carrying the same fields so {@code GET /bootui/api/overview} reports
 * identically on both stacks.
 */
public record BootUiMicronautActivation(boolean enabled, String reason, List<String> warnings) {

    public BootUiMicronautActivation {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
