package io.github.jdubois.bootui.sample.errors;

/**
 * Sample domain exception handled by a controller-local {@code @ExceptionHandler}, so the BootUI
 * error-contract catalogue can show a controller-scoped row that outranks any global advice.
 */
public class SampleOrderRejectedException extends RuntimeException {

    public SampleOrderRejectedException(String message) {
        super(message);
    }
}
