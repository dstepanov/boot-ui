package io.github.jdubois.bootui.engine.faulttolerance;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder.CapturedEvent;

/**
 * Maps captured fault tolerance metadata to {@code FAULT_TOLERANCE} Live Activity entries.
 *
 * <p>An entry nests under the {@code REQUEST} that produced it when the capture stamped a trace id that
 * uniquely identifies a retained request; background and asynchronously detached events stay top-level
 * rather than being matched heuristically.</p>
 */
public final class FaultToleranceActivityEntries {

    private static final String TYPE_FAULT_TOLERANCE = "FAULT_TOLERANCE";
    private static final String SEVERITY_OK = "OK";
    private static final String SEVERITY_WARN = "WARN";
    private static final String SEVERITY_ERROR = "ERROR";

    private FaultToleranceActivityEntries() {}

    public static ActivityEntryDto toEntry(CapturedEvent event, String parentId) {
        StringBuilder summary = new StringBuilder();
        summary.append(event.outcome() == null ? "EVENT" : event.outcome());
        summary.append(' ').append(event.policyName());
        if (event.policyType() != null && !event.policyType().isBlank()) {
            summary.append(" (").append(readableType(event.policyType())).append(')');
        }

        StringBuilder detail = new StringBuilder();
        if (event.target() != null && !event.target().isBlank()) {
            detail.append(event.target());
        }
        if (event.attempt() != null) {
            appendSeparator(detail);
            detail.append("attempt ").append(event.attempt());
        }
        if (event.state() != null && !event.state().isBlank()) {
            appendSeparator(detail);
            detail.append("state ").append(event.state());
        }
        if (event.failureCategory() != null && !event.failureCategory().isBlank()) {
            appendSeparator(detail);
            detail.append(event.failureCategory());
        }

        return new ActivityEntryDto(
                "fault-tolerance-" + event.id(),
                TYPE_FAULT_TOLERANCE,
                event.timestamp(),
                severity(event.outcome()),
                summary.toString(),
                detail.length() == 0 ? null : detail.toString(),
                event.durationMillis(),
                event.traceId(),
                null,
                null,
                null,
                null,
                false,
                parentId,
                null,
                false);
    }

    private static void appendSeparator(StringBuilder detail) {
        if (detail.length() > 0) {
            detail.append(" · ");
        }
    }

    private static String severity(String outcome) {
        if (FaultToleranceVocabulary.isFailureOutcome(outcome)) {
            return SEVERITY_ERROR;
        }
        if (FaultToleranceVocabulary.isProtectiveOutcome(outcome)) {
            return SEVERITY_WARN;
        }
        return SEVERITY_OK;
    }

    private static String readableType(String type) {
        return type.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
