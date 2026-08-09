package io.github.jdubois.bootui.core.dto;

/**
 * Top-level report for the Hibernate Statistics panel.
 *
 * <p>{@code available} is {@code true} only when a Hibernate {@code SessionFactory} was resolved
 * <em>and</em> {@code hibernate.generate_statistics} (or the Spring/Quarkus equivalent) is enabled;
 * {@code statistics} is {@code null} whenever {@code available} is {@code false}, and
 * {@code unavailableReason} then explains why (no session factory, or statistics disabled).</p>
 */
public record HibernateStatisticsReport(
        boolean available, String unavailableReason, HibernateStatisticsDto statistics) {}
