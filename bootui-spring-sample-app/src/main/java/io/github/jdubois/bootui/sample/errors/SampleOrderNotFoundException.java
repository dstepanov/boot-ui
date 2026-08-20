package io.github.jdubois.bootui.sample.errors;

/**
 * Sample domain exception handled by a global {@code @RestControllerAdvice} with a statically declared
 * status, so the BootUI error-contract catalogue can show a fully resolved row.
 */
public class SampleOrderNotFoundException extends RuntimeException {

    public SampleOrderNotFoundException(String message) {
        super(message);
    }
}
