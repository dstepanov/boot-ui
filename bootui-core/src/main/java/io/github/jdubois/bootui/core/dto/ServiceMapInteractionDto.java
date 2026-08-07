package io.github.jdubois.bootui.core.dto;

/**
 * One retained, already-completed interaction with a mapped dependency, reduced to the smallest safe
 * shape the Live Flow map needs.
 *
 * <p>Every field is deliberately non-identifying. No remote path, query string, SQL text, message key,
 * payload, header, or credential is ever carried here: {@link #operation()} is a coarse, safe verb
 * (an HTTP method, a SQL category, or a publish direction) and nothing more.</p>
 *
 * <p>{@link #id()} is stable across refreshes because it is derived from the originating bounded
 * buffer's monotonic sequence number. That stability is what lets the browser tell a genuinely new
 * completed interaction apart from one it has already drawn, so the map can animate only new evidence
 * instead of looping perpetually.</p>
 *
 * @param id stable identifier of the form {@code <protocol>:<sequence>}, unique within one report
 * @param timestamp epoch milliseconds when the interaction completed
 * @param operation coarse, safe operation label such as {@code GET}, {@code SELECT}, or {@code PUBLISH}
 * @param outcome {@code OK} when the interaction completed successfully, {@code FAILED} otherwise
 * @param durationMs wall-clock duration in milliseconds, or {@code null} when the source cannot report one
 */
public record ServiceMapInteractionDto(String id, long timestamp, String operation, String outcome, Long durationMs) {}
