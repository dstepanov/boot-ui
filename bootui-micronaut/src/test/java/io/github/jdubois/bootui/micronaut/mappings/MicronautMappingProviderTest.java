package io.github.jdubois.bootui.micronaut.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.inventorytest.InventoryTestController;
import io.github.jdubois.bootui.inventorytest.InventoryTestFixtures;
import io.micronaut.context.ApplicationContext;
import io.micronaut.web.router.Router;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins which HEAD routes the Mappings panel lists, against a real router.
 *
 * <p>Micronaut registers a generated HEAD route beside every route that answers GET, so the panel used to
 * report each endpoint twice. The distinction the provider draws — generated counterpart versus declared
 * {@code @Head} — cannot be tested against a stub: it is exactly the route table Micronaut's own route
 * builders produce that has to be read correctly.
 */
class MicronautMappingProviderTest {

    @Test
    void listsAGetEndpointOnceAndDropsItsGeneratedHeadCounterpart() {
        withFixtureRoutes(mappings -> {
            assertThat(mappings)
                    .filteredOn(mapping -> InventoryTestController.PATH.equals(mapping.pattern()))
                    .as("a declared @Get is one endpoint, not a GET row plus a generated HEAD row")
                    .singleElement()
                    .satisfies(mapping -> assertThat(mapping.method()).isEqualTo("GET"));
        });
    }

    @Test
    void keepsAnExplicitlyDeclaredHeadRoute() {
        withFixtureRoutes(mappings -> assertThat(mappings)
                .filteredOn(mapping -> (InventoryTestController.PATH + "/head-only").equals(mapping.pattern()))
                .as("an @Head method is a declaration, so the panel must still describe it")
                .singleElement()
                .satisfies(mapping -> assertThat(mapping.method()).isEqualTo("HEAD")));
    }

    /**
     * The generated counterpart is registered by two different route builders — {@code @Get} in
     * {@code AnnotatedMethodRouteBuilder}, {@code @Read} in the management endpoints' own builder — so the
     * rule is checked over the whole live table, not only over the fixture controller.
     */
    @Test
    void reportsNoGeneratedHeadRouteAnywhereInTheLiveTable() {
        withFixtureRoutes(mappings -> assertThat(mappings)
                .filteredOn(mapping -> "HEAD".equals(mapping.method()))
                .as("the only HEAD row the panel may report is the one the fixture declares")
                .extracting(MappingDto::pattern)
                .containsExactly(InventoryTestController.PATH + "/head-only"));
    }

    private static void withFixtureRoutes(java.util.function.Consumer<List<MappingDto>> assertion) {
        try (ApplicationContext context =
                ApplicationContext.run(Map.<String, Object>of(InventoryTestFixtures.PROPERTY, "true"), "test")) {
            assertion.accept(new MicronautMappingProvider(context.getBean(Router.class)).mappings());
        }
    }
}
