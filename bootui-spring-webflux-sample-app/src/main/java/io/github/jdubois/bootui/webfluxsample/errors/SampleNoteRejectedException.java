package io.github.jdubois.bootui.webfluxsample.errors;

/**
 * Sample domain exception handled by a controller-local {@code @ExceptionHandler}, so the BootUI
 * error-contract catalogue can show a controller-scoped row that outranks the global advice.
 */
public class SampleNoteRejectedException extends RuntimeException {

    public SampleNoteRejectedException(String message) {
        super(message);
    }
}
