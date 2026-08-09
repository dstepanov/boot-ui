package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral seam behind the Hibernate Session Monitoring panel: reports whether a Hibernate
 * {@code SessionFactory} is reachable, whether it has statistics collection enabled, and — only when both
 * are true — a live {@link HibernateStatisticsSnapshot} read from {@code org.hibernate.stat.Statistics}.
 *
 * <p>The Spring adapter implements this by resolving the first {@code EntityManagerFactory} bean and
 * unwrapping it to {@code org.hibernate.SessionFactory}; the Quarkus adapter resolves the first
 * {@code EntityManagerFactory} CDI bean the same way. Multiple persistence units are a known limitation:
 * only the first resolved {@code SessionFactory} is reported (see the panel documentation).</p>
 *
 * <p>This seam is strictly read-only: there is deliberately no reset/clear method, since Hibernate
 * statistics reset is a mutating action out of scope for this panel.</p>
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
     * Reads a live snapshot of the current statistics. Only called when {@link #available()} and
     * {@link #statisticsEnabled()} are both {@code true}.
     */
    HibernateStatisticsSnapshot snapshot();
}
