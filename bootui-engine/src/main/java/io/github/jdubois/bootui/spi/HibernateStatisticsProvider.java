package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral seam behind the Hibernate Statistics panel: reports whether a Hibernate
 * {@code SessionFactory} is reachable, whether it has statistics collection enabled, and — only when both
 * are true — a live {@link HibernateStatisticsSnapshot} read from {@code org.hibernate.stat.Statistics}.
 *
 * <p>The Spring adapter implements this by resolving the first {@code EntityManagerFactory} bean and
 * unwrapping it to {@code org.hibernate.SessionFactory}; the Quarkus adapter resolves the first
 * {@code EntityManagerFactory} CDI bean the same way. Multiple persistence units are a known limitation:
 * only the first resolved {@code SessionFactory} is reported (see the panel documentation).</p>
 *
 * <p>The seam can enable collection for the current runtime after an explicit user action. It deliberately
 * has no reset/clear method, so existing counters are never discarded.</p>
 */
public interface HibernateStatisticsProvider {

    /**
     * Whether a Hibernate {@code SessionFactory} could be resolved from the application's
     * {@code EntityManagerFactory} bean(s). {@code false} means no JPA/Hibernate persistence unit is
     * configured (or it could not be unwrapped to {@code SessionFactory}), and the engine then serves an
     * unavailable report without calling {@link #statisticsEnabled()} or {@link #snapshot()}.
     */
    boolean available();

    /**
     * Whether the resolved {@code SessionFactory} has statistics collection enabled
     * ({@code Statistics.isStatisticsEnabled()}), i.e. {@code hibernate.generate_statistics} (or the
     * Spring/Quarkus equivalent) is turned on. Only called when {@link #available()} is {@code true}.
     */
    boolean statisticsEnabled();

    /**
     * Enables statistics collection on the resolved {@code SessionFactory} for the current runtime. Only
     * called after an explicit, policy-guarded user action when {@link #available()} is {@code true}.
     */
    void enableStatistics();

    /**
     * Reads a live snapshot of the current statistics. Only called when {@link #available()} and
     * {@link #statisticsEnabled()} are both {@code true}.
     */
    HibernateStatisticsSnapshot snapshot();
}
