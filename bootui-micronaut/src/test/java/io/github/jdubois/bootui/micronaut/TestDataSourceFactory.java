package io.github.jdubois.bootui.micronaut;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * An opt-in, in-memory H2 {@code DataSource} bean named {@code default} for the adapter's own tests.
 *
 * <p>This module has no sample application and no JDBC pool on its classpath, yet the Flyway and Liquibase
 * panels are gated on a migration configuration that is <em>backed by a datasource bean</em>. A test that
 * wants to prove those panels light up sets {@link #PROPERTY} to {@code true} alongside the
 * {@code flyway.datasources.default.*} / {@code liquibase.datasources.default.*} configuration, and both
 * libraries then find the datasource exactly as they would find one published by {@code micronaut-jdbc-hikari}.
 *
 * <p>The bean is opt-in so that every other test in this module keeps booting the bare adapter it documents,
 * and so the production-dark assertion (no BootUI bean enabled in {@code prod}) is unaffected.
 */
@Factory
@Requires(property = TestDataSourceFactory.PROPERTY, value = StringUtils.TRUE)
public class TestDataSourceFactory {

    /** Set to {@code true} to publish the {@code default} datasource. */
    public static final String PROPERTY = "bootui.test.datasource";

    @Singleton
    @Named("default")
    DataSource defaultDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:bootui-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}
