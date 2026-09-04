package io.github.jdubois.bootui.micronaut.datasource;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import io.micronaut.context.BeanContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Supplies the application's datasources to the Database advisor, each under the name it is configured with.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusDatabaseAdvisorDataSourceProvider}. Every
 * {@code DataSource} bean is resolved by its configured name — the same name the Connection Pools and SQL
 * Trace panels use — so a finding can be attributed to a specific datasource in a multi-datasource
 * application.
 *
 * <p>The same underlying datasource can be reachable through more than one bean (Micronaut wraps datasources
 * for transaction awareness, and BootUI's own SQL-trace proxy wraps them again), so duplicates are collapsed
 * by identity of the resolved bean. A datasource that cannot be resolved is skipped rather than reported as
 * broken: the application's own startup already surfaced that.
 */
public final class MicronautDataSourceProvider implements DatabaseAdvisorDataSourceProvider {

    private final BeanContext beanContext;

    public MicronautDataSourceProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public List<NamedDataSource> dataSources() {
        if (beanContext == null) {
            return List.of();
        }
        List<NamedDataSource> named = new ArrayList<>();
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BeanDefinition<DataSource> definition : beanContext.getBeanDefinitions(DataSource.class)) {
            String name = name(definition);
            DataSource dataSource = resolve(definition, name);
            if (dataSource == null || !seen.add(dataSource)) {
                continue;
            }
            named.add(new NamedDataSource(name, dataSource));
        }
        return List.copyOf(named);
    }

    private DataSource resolve(BeanDefinition<DataSource> definition, String name) {
        try {
            return beanContext.getBean(DataSource.class, Qualifiers.byName(name));
        } catch (RuntimeException ex) {
            try {
                return beanContext.getBean(definition);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static String name(BeanDefinition<DataSource> definition) {
        return definition
                .getAnnotationMetadata()
                .stringValue(io.micronaut.context.annotation.EachProperty.class, "value")
                .orElseGet(
                        () -> definition.stringValue(jakarta.inject.Named.class).orElse("default"));
    }
}
