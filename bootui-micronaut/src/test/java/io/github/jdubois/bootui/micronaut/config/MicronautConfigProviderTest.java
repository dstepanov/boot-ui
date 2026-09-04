package io.github.jdubois.bootui.micronaut.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.ConfigEntry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the precedence direction the Configuration panel relies on.
 *
 * <p>Micronaut's property-source numbers read backwards: the <em>highest</em> {@code getOrder()} value wins,
 * because the environment processes sources in ascending order and lets each later one overwrite the earlier
 * ones. A provider that assumed the opposite would still report the right effective value (it reads it through
 * the resolver) but would attribute it to the wrong source whenever two sources carry the same value, and would
 * list sources lowest-precedence first. Both are exactly the kind of quiet error that makes a developer trust the
 * wrong file, so they are pinned here against a real context.
 */
class MicronautConfigProviderTest {

    private static final String KEY = "bootui.test.precedence";

    @Test
    void attributesAValueToTheSourceThatActuallyWon() {
        try (ApplicationContext context = context("from-floor", "from-winner")) {
            ConfigEntry entry = entry(new MicronautConfigProvider(context.getEnvironment()));

            assertThat(entry.value()).isEqualTo("from-winner");
            assertThat(entry.source()).isEqualTo("winner");
        }
    }

    /**
     * The case a value comparison cannot settle: both sources carry the same value, so only the precedence
     * order decides, and it must name the source Micronaut actually read the value from.
     */
    @Test
    void attributesAnIdenticalValueToTheHigherPrecedenceSource() {
        try (ApplicationContext context = context("same", "same")) {
            ConfigEntry entry = entry(new MicronautConfigProvider(context.getEnvironment()));

            assertThat(entry.source()).isEqualTo("winner");
        }
    }

    @Test
    void listsSourcesHighestPrecedenceFirst() {
        try (ApplicationContext context = context("from-floor", "from-winner")) {
            var sources = new MicronautConfigProvider(context.getEnvironment()).sources();

            assertThat(sources.indexOf("winner")).isLessThan(sources.indexOf("floor"));
        }
    }

    /** A context with two sources for the same key: {@code floor} at {@code -400}, {@code winner} at {@code -100}. */
    private static ApplicationContext context(String floorValue, String winnerValue) {
        return ApplicationContext.builder()
                .propertySources(
                        PropertySource.of("floor", Map.of(KEY, floorValue), -400),
                        PropertySource.of("winner", Map.of(KEY, winnerValue), -100))
                .start();
    }

    private static ConfigEntry entry(MicronautConfigProvider provider) {
        return provider.entries().stream()
                .filter(candidate -> KEY.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(KEY + " was not enumerated"));
    }
}
