package io.github.jdubois.bootui.micronaut.datasource;

import io.micronaut.context.BeanContext;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * The one place the Micronaut adapter answers "which datasources does this application have, and what is
 * each one called?".
 *
 * <p>The answer comes from {@link BeanContext#mapOfType(Class)} — the very container call that backs a
 * {@code Map<String, DataSource>} injection point, so a name here is the name the application itself would
 * be injected under: the {@code @EachProperty} name for a {@code datasources.<name>} configuration, the
 * {@code @Named} qualifier for a hand-declared bean. Deriving it any other way would risk the Database
 * advisor and the Connection Pools panel labelling the same datasource differently.
 *
 * <p>Sorted by name so both panels render datasources in a stable order across refreshes.
 */
final class MicronautDataSources {

    private MicronautDataSources() {}

    /** The application's datasources by configured name, empty when there is no container to ask. */
    static Map<String, DataSource> byName(BeanContext beanContext) {
        if (beanContext == null) {
            return Map.of();
        }
        return new TreeMap<>(beanContext.mapOfType(DataSource.class));
    }
}
