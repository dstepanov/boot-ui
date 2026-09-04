package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import io.github.jdubois.bootui.autoconfigure.datasource.DelegatingDataSources;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring binding of the {@link DatabaseAdvisorDataSourceProvider} SPI: discovers the application's
 * {@code DataSource} beans, resolving Spring's delegating and routing {@code DataSource} wrappers down to the
 * physical pools behind them so the same database is introspected exactly once, under one name.
 *
 * <p>Discovery runs in two passes. Plain {@code DataSource} beans are taken first, so a wrapper never claims a
 * name a real bean could have carried. Wrapper beans are then resolved:</p>
 *
 * <ul>
 *   <li>A single-target wrapper ({@code DelegatingDataSource}, most often Spring Boot's
 *       {@code LazyConnectionDataSourceProxy} for {@code spring.datasource.connection-fetch=lazy}) is
 *       <em>skipped only when the pool it forwards to is a bean of its own</em>, which is the case it was always
 *       meant to cover. When the wrapper owns the only reference to that pool the wrapper itself is introspected,
 *       because opening a connection and reading {@code DatabaseMetaData} through it reaches the same physical
 *       database. Reporting nothing there was issue #924.</li>
 *   <li>A routing wrapper ({@code AbstractRoutingDataSource}) is expanded into its already-resolved targets, named
 *       {@code beanName[lookupKey]}, since the wrapper itself cannot answer {@code getConnection()} without a live
 *       lookup key. Targets that are beans in their own right are de-duplicated away.</li>
 * </ul>
 *
 * <p>De-duplication is by <em>physical</em> identity: a candidate is reduced through BootUI's own SQL Trace proxy
 * (via the JDBC {@code unwrap} contract the proxy delegates) and through nested single-target wrappers before being
 * compared, so a traced datasource and the same pool exposed under a second bean name are introspected once, not
 * twice — which would otherwise double every finding in the report.</p>
 *
 * <p>When a wrapper refuses to describe its target, it is reported rather than dropped, so the scan says the
 * physical schema could not be read instead of claiming the application has no {@code DataSource} bean at all.</p>
 */
public final class SpringDatabaseAdvisorDataSourceProvider implements DatabaseAdvisorDataSourceProvider {

    private static final String TRACED_DATA_SOURCE_MARKER =
            "io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource";

    /** Bound on wrapper nesting, so a mis-configured cyclic wrapper chain cannot spin here. */
    private static final int MAX_WRAPPER_DEPTH = 16;

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public SpringDatabaseAdvisorDataSourceProvider(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @Override
    public List<NamedDataSource> dataSources() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        List<Candidate> candidates = candidates(factory);
        List<NamedDataSource> dataSources = new ArrayList<>();
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Candidate candidate : candidates) {
            if (!candidate.wrapper()) {
                add(dataSources, seen, candidate.name(), candidate.dataSource());
            }
        }
        for (Candidate candidate : candidates) {
            if (candidate.wrapper()) {
                addWrapper(dataSources, seen, candidate.name(), candidate.dataSource());
            }
        }
        return dataSources;
    }

    /** One {@code DataSource} bean, pre-classified as a Spring wrapper or a plain datasource. */
    private record Candidate(String name, DataSource dataSource, boolean wrapper) {}

    private static List<Candidate> candidates(ListableBeanFactory factory) {
        List<Candidate> candidates = new ArrayList<>();
        for (String beanName : beanNamesForType(factory)) {
            DataSource dataSource = bean(factory, beanName);
            if (dataSource != null) {
                candidates.add(new Candidate(
                        strip(beanName), dataSource, DelegatingDataSources.isWrapper(dataSource.getClass())));
            }
        }
        return candidates;
    }

    private static void addWrapper(
            List<NamedDataSource> dataSources, Set<DataSource> seen, String beanName, DataSource wrapper) {
        if (DelegatingDataSources.isRouting(wrapper.getClass())) {
            Map<Object, DataSource> targets = DelegatingDataSources.routingTargets(wrapper);
            if (targets.isEmpty()) {
                add(dataSources, seen, beanName, wrapper);
                return;
            }
            for (Map.Entry<Object, DataSource> target : targets.entrySet()) {
                add(dataSources, seen, beanName + "[" + target.getKey() + "]", target.getValue());
            }
            return;
        }
        DataSource target = DelegatingDataSources.target(wrapper);
        if (target == null) {
            add(dataSources, seen, beanName, wrapper);
            return;
        }
        // The wrapper is what gets introspected, but the pool behind it is what decides whether it is a duplicate.
        if (seen.add(physicalDataSource(target))) {
            dataSources.add(new NamedDataSource(beanName, wrapper));
        }
    }

    private static void add(
            List<NamedDataSource> dataSources, Set<DataSource> seen, String name, DataSource dataSource) {
        if (seen.add(physicalDataSource(dataSource))) {
            dataSources.add(new NamedDataSource(name, dataSource));
        }
    }

    private static String[] beanNamesForType(ListableBeanFactory factory) {
        try {
            String[] beanNames = factory.getBeanNamesForType(DataSource.class);
            return beanNames == null ? new String[0] : beanNames;
        } catch (BeansException ex) {
            return new String[0];
        }
    }

    private static DataSource bean(ListableBeanFactory factory, String beanName) {
        try {
            return factory.getBean(beanName, DataSource.class);
        } catch (BeansException ex) {
            return null;
        }
    }

    /**
     * The physical datasource behind a BootUI SQL Trace proxy and any nesting of Spring's single-target wrappers;
     * anything else (including a routing wrapper, which has no single physical target) is its own identity.
     */
    private static DataSource physicalDataSource(DataSource dataSource) {
        DataSource current = dataSource;
        for (int depth = 0; depth < MAX_WRAPPER_DEPTH; depth++) {
            DataSource next = unwrapOnce(current);
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private static DataSource unwrapOnce(DataSource dataSource) {
        if (isTracingProxy(dataSource)) {
            try {
                return dataSource.unwrap(DataSource.class);
            } catch (SQLException | RuntimeException ex) {
                return null;
            }
        }
        if (DelegatingDataSources.isSingleTarget(dataSource.getClass())) {
            return DelegatingDataSources.target(dataSource);
        }
        return null;
    }

    private static boolean isTracingProxy(DataSource dataSource) {
        for (Class<?> interfaceType : dataSource.getClass().getInterfaces()) {
            if (TRACED_DATA_SOURCE_MARKER.equals(interfaceType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }
}
