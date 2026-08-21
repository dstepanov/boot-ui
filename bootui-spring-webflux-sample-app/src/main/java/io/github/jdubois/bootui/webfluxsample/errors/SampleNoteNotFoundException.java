package io.github.jdubois.bootui.webfluxsample.errors;

/**
 * Sample domain exception handled by a global {@code @RestControllerAdvice} with a statically declared
 * status, so the BootUI error-contract catalogue can show a fully resolved row on the reactive stack.
 */
public class SampleNoteNotFoundException extends RuntimeException {

    public SampleNoteNotFoundException(String message) {
        super(message);
    }
}
