package io.github.jdubois.bootui.sample.errors;

/**
 * Sample domain exception handled by a {@code ResponseEntity}-returning handler, so the BootUI
 * error-contract catalogue can show a row whose status is honestly reported as built at runtime.
 */
public class SampleOrderConflictException extends RuntimeException {

    public SampleOrderConflictException(String message) {
        super(message);
    }
}
