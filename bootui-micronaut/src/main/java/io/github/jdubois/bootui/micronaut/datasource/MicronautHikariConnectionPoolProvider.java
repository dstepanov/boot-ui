package io.github.jdubois.bootui.micronaut.datasource;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolSnapshot;
import io.micronaut.context.BeanContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Micronaut {@link ConnectionPoolProvider} over HikariCP, the pool {@code micronaut-jdbc-hikari} configures
 * and by far the most common one in Micronaut applications.
 *
 * <p>The Micronaut analogue of the Spring adapter's Hikari reader and the Quarkus adapter's Agroal one. Each
 * {@code DataSource} bean is inspected: a Hikari pool contributes its live configuration and its
 * {@link HikariPoolMXBean} counters; anything else is still listed — with a name and an honest
 * {@code unavailableReason} — rather than being hidden, so the panel never implies an application has no
 * datasource when it simply has a pool BootUI cannot read.
 *
 * <p>Micronaut wraps datasources for transaction awareness, so each bean is unwrapped through the JDBC
 * {@code Wrapper} API before it is inspected; that is what makes this work for a transactional datasource
 * as well as a bare one.
 */
public final class MicronautHikariConnectionPoolProvider implements ConnectionPoolProvider {

    static final String NOT_HIKARI_REASON =
            "This datasource is not a HikariCP pool, so BootUI cannot read its pool configuration or"
                    + " live counters.";

    static final String POOL_NOT_STARTED_REASON =
            "The pool has not been started yet, so it exposes no live counters. They appear once the"
                    + " application takes its first connection.";

    private final BeanContext beanContext;

    public MicronautHikariConnectionPoolProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public List<ConnectionPoolInfo> pools() {
        if (beanContext == null) {
            return List.of();
        }
        List<ConnectionPoolInfo> pools = new ArrayList<>();
        for (BeanDefinition<DataSource> definition : beanContext.getBeanDefinitions(DataSource.class)) {
            String beanName = beanName(definition);
            DataSource dataSource = resolve(definition, beanName);
            if (dataSource == null) {
                continue;
            }
            HikariDataSource hikari = unwrapHikari(dataSource);
            pools.add(hikari == null ? opaquePool(beanName, dataSource) : hikariPool(beanName, hikari));
        }
        return List.copyOf(pools);
    }

    private DataSource resolve(BeanDefinition<DataSource> definition, String beanName) {
        try {
            return beanContext.getBean(DataSource.class, Qualifiers.byName(beanName));
        } catch (RuntimeException ex) {
            try {
                return beanContext.getBean(definition);
            } catch (RuntimeException ignored) {
                // A datasource that cannot be resolved (misconfigured credentials, for instance) must not
                // fail the whole panel; it is simply omitted, and the application's own startup already
                // reported the problem.
                return null;
            }
        }
    }

    private static String beanName(BeanDefinition<DataSource> definition) {
        return definition
                .getAnnotationMetadata()
                .stringValue(io.micronaut.context.annotation.EachProperty.class, "value")
                .orElseGet(
                        () -> definition.stringValue(jakarta.inject.Named.class).orElse("default"));
    }

    /**
     * Unwraps a Micronaut-wrapped datasource down to the Hikari pool, or returns {@code null} when this is
     * not a Hikari pool at all.
     */
    static HikariDataSource unwrapHikari(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari;
        }
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class);
            }
        } catch (Exception ex) {
            // A datasource that refuses to unwrap is simply reported as opaque.
        }
        return null;
    }

    private static ConnectionPoolInfo hikariPool(String beanName, HikariDataSource hikari) {
        HikariConfigMXBean config = hikari.getHikariConfigMXBean();
        HikariPoolMXBean pool = poolMxBean(hikari);
        return new ConnectionPoolInfo(
                beanName,
                hikari.getPoolName(),
                hikari.getJdbcUrl(),
                hikari.getUsername(),
                hikari.getDriverClassName(),
                config == null ? hikari.getMinimumIdle() : config.getMinimumIdle(),
                config == null ? hikari.getMaximumPoolSize() : config.getMaximumPoolSize(),
                hikari.getConnectionTimeout(),
                hikari.getIdleTimeout(),
                hikari.getMaxLifetime(),
                hikari.getValidationTimeout(),
                hikari.getKeepaliveTime(),
                hikari.isReadOnly(),
                hikari.isAutoCommit(),
                pool != null,
                pool == null ? POOL_NOT_STARTED_REASON : null,
                pool == null ? null : snapshot(pool));
    }

    private static ConnectionPoolInfo opaquePool(String beanName, DataSource dataSource) {
        return new ConnectionPoolInfo(
                beanName,
                beanName,
                null,
                null,
                dataSource.getClass().getName(),
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                false,
                false,
                false,
                NOT_HIKARI_REASON,
                null);
    }

    /**
     * The live pool MBean, or {@code null} when the pool has not been started. Hikari creates the pool
     * lazily on the first connection and throws from this accessor until then, which is a normal state for a
     * freshly booted application rather than an error.
     */
    private static HikariPoolMXBean poolMxBean(HikariDataSource hikari) {
        try {
            return hikari.getHikariPoolMXBean();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ConnectionPoolSnapshot snapshot(HikariPoolMXBean pool) {
        return new ConnectionPoolSnapshot(
                System.currentTimeMillis(),
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection());
    }
}
