package io.github.jdubois.bootui.micronaut.hibernate;

import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.micronaut.context.env.Environment;
import java.util.Map;
import java.util.function.Function;

/**
 * Maps the Hibernate configuration keys the shared advisor rules ask for onto the namespace Micronaut
 * actually stores them in.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusHibernatePropertyLookup}. The engine's
 * rules are written against Hibernate's own property names (and their Spring {@code spring.jpa.properties.}
 * spellings); Micronaut Data configures a persistence unit under {@code jpa.<name>.properties.hibernate.*},
 * so a key is resolved by stripping any Spring prefix and looking under the configured persistence units.
 *
 * <p>Two rules are answered directly rather than looked up, because the concept does not exist here:
 * Open Session in View is a Spring-only pattern that Micronaut Data does not implement, so it is reported as
 * inapplicable and disabled instead of letting the rule assume Spring Boot's enabled-by-default. Bytecode
 * enhancement is deliberately <em>not</em> claimed as verified: unlike Quarkus, Micronaut Data does not
 * enhance entities at build time, so the rule applies exactly as it does on Spring.
 */
public final class MicronautHibernatePropertyLookup implements Function<String, String> {

    static final String SPRING_JPA_PROPERTIES_PREFIX = "spring.jpa.properties.";

    private static final String OPEN_IN_VIEW_KEY = "spring.jpa.open-in-view";

    /** The persistence units Micronaut Data configures, in the order they are consulted. */
    private static final String[] PERSISTENCE_UNIT_PREFIXES = {"jpa.default.properties.", "jpa.properties."};

    /**
     * Spring spellings the rules use that have no direct Hibernate-property equivalent, mapped onto the
     * Hibernate property Micronaut would actually carry.
     */
    private static final Map<String, String> KEY_ALIASES = Map.of(
            "spring.jpa.hibernate.ddl-auto", "hibernate.hbm2ddl.auto",
            "spring.jpa.show-sql", "hibernate.show_sql");

    private final Environment environment;

    public MicronautHibernatePropertyLookup(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String apply(String key) {
        if (key == null) {
            return null;
        }
        if (HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY.equals(key) || OPEN_IN_VIEW_KEY.equals(key)) {
            return "false";
        }
        String hibernateKey = KEY_ALIASES.getOrDefault(key, stripSpringPrefix(key));
        for (String prefix : PERSISTENCE_UNIT_PREFIXES) {
            String value = raw(prefix + hibernateKey);
            if (value != null) {
                return value;
            }
        }
        return raw(key);
    }

    private static String stripSpringPrefix(String key) {
        return key.startsWith(SPRING_JPA_PROPERTIES_PREFIX)
                ? key.substring(SPRING_JPA_PROPERTIES_PREFIX.length())
                : key;
    }

    private String raw(String key) {
        try {
            return environment
                    .getProperty(key, String.class)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
