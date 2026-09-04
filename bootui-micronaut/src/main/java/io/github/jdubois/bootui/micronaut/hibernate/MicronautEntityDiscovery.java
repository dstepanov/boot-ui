package io.github.jdubois.bootui.micronaut.hibernate;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import io.micronaut.context.BeanContext;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the application's JPA entity model for the Hibernate advisor.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusEntityDiscovery}: both hand the
 * application's {@code EntityManagerFactory} instances to the shared engine {@link JpaMetamodelReader}, so
 * the mapping rules see the same model on every stack. The same factory can be reachable through more than
 * one bean, so factories are de-duplicated by identity before the metamodel is read.
 *
 * <p>Any failure — an absent persistence unit, a metamodel that cannot be built — degrades to an empty
 * discovery carrying the reason, which the advisor reports rather than failing the scan.
 */
public final class MicronautEntityDiscovery {

    private MicronautEntityDiscovery() {}

    public static EntityDiscovery discover(BeanContext beanContext) {
        if (beanContext == null) {
            return EntityDiscovery.empty("No bean context is available to resolve a persistence unit.");
        }
        try {
            List<EntityManagerFactory> factories =
                    dedupeByIdentity(beanContext.getBeansOfType(EntityManagerFactory.class));
            if (factories.isEmpty()) {
                return EntityDiscovery.empty("Hibernate ORM is not configured on this Micronaut application.");
            }
            return JpaMetamodelReader.readEntities(factories);
        } catch (RuntimeException | LinkageError ex) {
            return EntityDiscovery.empty(ex.getMessage());
        }
    }

    static List<EntityManagerFactory> dedupeByIdentity(Iterable<EntityManagerFactory> factories) {
        Map<EntityManagerFactory, Boolean> seen = new IdentityHashMap<>();
        List<EntityManagerFactory> distinct = new ArrayList<>();
        for (EntityManagerFactory factory : factories) {
            if (factory != null && seen.put(factory, Boolean.TRUE) == null) {
                distinct.add(factory);
            }
        }
        return distinct;
    }
}
