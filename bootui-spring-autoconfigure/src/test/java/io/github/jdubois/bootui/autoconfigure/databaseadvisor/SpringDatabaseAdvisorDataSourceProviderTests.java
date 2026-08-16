package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Discovery rules for the Spring binding: a wrapped datasource must be introspected exactly once, under one
 * name, because every duplicate would double every finding the Database Advisor reports.
 */
class SpringDatabaseAdvisorDataSourceProviderTests {

    private static SpringDatabaseAdvisorDataSourceProvider providerFor(DefaultListableBeanFactory factory) {
        return new SpringDatabaseAdvisorDataSourceProvider(new SimpleObjectProvider(factory));
    }

    @Test
    void discoversEveryDistinctDataSourceBean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("primaryDataSource", new StubDataSource());
        factory.registerSingleton("reportingDataSource", new StubDataSource());

        List<NamedDataSource> dataSources = providerFor(factory).dataSources();

        assertThat(dataSources)
                .extracting(NamedDataSource::name)
                .containsExactlyInAnyOrder("primaryDataSource", "reportingDataSource");
    }

    @Test
    void skipsSpringDelegatingWrappers() {
        DataSource real = new StubDataSource();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", real);
        factory.registerSingleton("delegatingDataSource", new DelegatingDataSource(real));

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("dataSource");
    }

    @Test
    void introspectsATracedDataSourceAndItsUnderlyingPoolOnlyOnce() {
        DataSource real = new StubDataSource();
        DataSource traced =
                SqlTracingProxies.wrap(real, new SqlTraceRecorder(false, false, false, false, 100, 100, 256, 128, 5));
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", traced);
        factory.registerSingleton("rawDataSource", real);

        List<NamedDataSource> dataSources = providerFor(factory).dataSources();

        assertThat(dataSources).hasSize(1);
        assertThat(dataSources.get(0).name()).isEqualTo("dataSource");
    }

    /** Minimal {@link ObjectProvider} over a fixed bean factory. */
    private record SimpleObjectProvider(DefaultListableBeanFactory factory)
            implements ObjectProvider<org.springframework.beans.factory.ListableBeanFactory> {

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getObject(Object... args) {
            return factory;
        }

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getObject() {
            return factory;
        }

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getIfAvailable() {
            return factory;
        }

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getIfUnique() {
            return factory;
        }
    }

    private static class StubDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("not used in this test");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return iface.cast(this);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
