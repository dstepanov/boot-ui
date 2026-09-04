package io.github.jdubois.bootui.micronaut.errorcontract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.inventorytest.InventoryTestExceptionHandler;
import io.github.jdubois.bootui.inventorytest.InventoryTestFixtures;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins whose error contract the REST API panel catalogues.
 *
 * <p>Unlike Spring's {@code @ControllerAdvice} and Quarkus' application-archive index, Micronaut ships its
 * own error contract as ordinary {@code ExceptionHandler} beans, so a plain application hands the container
 * a dozen framework handlers alongside its own. The catalogue is the application's, so only a real context
 * — with Micronaut's handlers actually registered — can show that they are excluded and the application's
 * is not.
 */
class MicronautErrorContractProviderTest {

    private static final String MICRONAUT_PACKAGE = "io.micronaut.";

    @Test
    void cataloguesTheApplicationsOwnHandler() {
        assertThat(handlers())
                .extracting(ErrorHandlerDescriptor::componentClassName)
                .contains(InventoryTestExceptionHandler.class.getName());
    }

    @Test
    void excludesMicronautsOwnExceptionHandlers() {
        assertThat(handlers())
                .as("the panel describes the contract this application declares, not the framework's")
                .noneMatch(handler -> handler.componentClassName().startsWith(MICRONAUT_PACKAGE));
    }

    @Test
    void excludesBootUisOwnHandlers() {
        assertThat(handlers())
                .noneMatch(handler -> handler.componentClassName().startsWith("io.github.jdubois.bootui.micronaut."));
    }

    private static List<ErrorHandlerDescriptor> handlers() {
        try (ApplicationContext context =
                ApplicationContext.run(Map.<String, Object>of(InventoryTestFixtures.PROPERTY, "true"), "test")) {
            return new MicronautErrorContractProvider(context).handlers();
        }
    }
}
