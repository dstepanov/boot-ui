package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One normalized statement ranked over the retained SQL Trace window.
 *
 * <p>Executions whose SQL differs only in embedded literal values collapse into a single group, because
 * the statement text is normalized (literals replaced by {@code ?}, whitespace collapsed, {@code IN}
 * lists folded) before grouping. {@link #sql()} is therefore always the literal-free form: no bound
 * parameter value and no inlined literal reaches this DTO.</p>
 *
 * <p>Every metric a ranking can be ordered by travels on the same row, so the panel offers cumulative
 * duration, maximum duration, execution count, average duration, error count and the 95th/99th duration
 * percentiles without the response carrying one ranked list per criterion. The server still selects the
 * rows: it returns the union of the top entries for each criterion, so re-sorting client-side is exact
 * for all of them. {@link #topFor()} states which criteria a row was selected for, so a consumer that is
 * not the panel can tell a genuine ranking from the rest of the union.</p>
 *
 * @param id stable fingerprint of the normalized statement, used to deep-link the panel's filtered views
 * @param sql the normalized, literal-free statement text
 * @param category coarse SQL category (SELECT, INSERT, UPDATE, DELETE, DDL, OTHER)
 * @param executions retained executions in this group
 * @param totalDurationMillis summed duration of those executions
 * @param maxDurationMillis slowest single execution in the group
 * @param avgDurationMillis mean duration across the group
 * @param errorCount executions in the group that failed
 * @param p50DurationMillis median duration across the group's retained executions
 * @param p95DurationMillis 95th percentile duration across the group's retained executions
 * @param p99DurationMillis 99th percentile duration across the group's retained executions
 * @param shareOfRetainedTimePercent this group's share of the window's total database time, 0-100
 * @param topFor the ranking criteria this row is in the top group for, never empty
 * @param potentialNPlusOne whether the repetition count suggests an N+1 access pattern
 * @param callSites distinct application call sites observed, bounded and most-recently-seen first
 * @param entryIds ids of the retained executions in this group, so the panel can filter the executions
 *     table to exactly this statement; bounded
 * @param entryIdsTruncated whether {@link #entryIds()} covers only part of the group, so the panel can
 *     say so instead of presenting a capped deep link as the complete set
 */
public record SqlStatementRankingDto(
        String id,
        String sql,
        String category,
        long executions,
        long totalDurationMillis,
        long maxDurationMillis,
        double avgDurationMillis,
        long errorCount,
        long p50DurationMillis,
        long p95DurationMillis,
        long p99DurationMillis,
        double shareOfRetainedTimePercent,
        List<String> topFor,
        boolean potentialNPlusOne,
        List<String> callSites,
        List<Long> entryIds,
        boolean entryIdsTruncated) {

    public SqlStatementRankingDto {
        topFor = topFor == null ? List.of() : List.copyOf(topFor);
        callSites = callSites == null ? List.of() : List.copyOf(callSites);
        entryIds = entryIds == null ? List.of() : List.copyOf(entryIds);
    }
}
