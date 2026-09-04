package io.github.jdubois.bootui.micronautsample;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;

/**
 * Turns a bad catalog lookup into a 400 instead of a 500, so the sample has a real, declared error
 * contract for the console's REST API panel to catalogue — the Micronaut analogue of the Spring sample's
 * {@code SampleGlobalErrorHandler} and the Quarkus sample's exception mapper.
 */
@Singleton
@Requires(classes = {IllegalArgumentException.class, ExceptionHandler.class})
public class CatalogExceptionHandler implements ExceptionHandler<IllegalArgumentException, HttpResponse<?>> {

    private final ErrorResponseProcessor<?> errorResponseProcessor;

    public CatalogExceptionHandler(ErrorResponseProcessor<?> errorResponseProcessor) {
        this.errorResponseProcessor = errorResponseProcessor;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, IllegalArgumentException exception) {
        return errorResponseProcessor.processResponse(
                io.micronaut.http.server.exceptions.response.ErrorContext.builder(request)
                        .cause(exception)
                        .errorMessage(exception.getMessage())
                        .build(),
                HttpResponse.badRequest(new JsonError(exception.getMessage())));
    }
}
