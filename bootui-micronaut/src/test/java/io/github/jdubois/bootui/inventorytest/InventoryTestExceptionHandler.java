package io.github.jdubois.bootui.inventorytest;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/**
 * A stand-in for the application's own error contract, so the error-contract catalogue has something to
 * report once Micronaut's dozen framework handlers are excluded from it.
 */
@Singleton
@Requires(property = InventoryTestFixtures.PROPERTY, value = StringUtils.TRUE)
public class InventoryTestExceptionHandler implements ExceptionHandler<InventoryTestException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, InventoryTestException exception) {
        return HttpResponse.badRequest(exception.getMessage());
    }
}
