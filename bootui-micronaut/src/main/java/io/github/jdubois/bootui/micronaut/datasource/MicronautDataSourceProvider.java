package io.github.jdubois.bootui.micronaut.datasource;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import io.micronaut.context.BeanContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Resolution is delegated to {@link MicronautDataSources}, so the advisor and the Connection Pools panel
 * can never label the same datasource differently.
 *
 * <p>The same underlying datasource can be reachable under more than one name (Micronaut wraps datasources
 * for transaction awareness, and BootUI's own SQL-trace proxy wraps them again), so duplicates are collapsed
 * by identity of the resolved bean and the first name wins.
 */
public final class MicronautDataSourceProvider implements DatabaseAdvisorDataSourceProvider {

    private final BeanContext beanContext;

    public MicronautDataSourceProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public List<NamedDataSource> dataSources() {
        List<NamedDataSource> named = new ArrayList<>();
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, DataSource> entry :
                MicronautDataSources.byName(beanContext).entrySet()) {
            if (seen.add(entry.getValue())) {
                named.add(new NamedDataSource(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(named);
    }
}
