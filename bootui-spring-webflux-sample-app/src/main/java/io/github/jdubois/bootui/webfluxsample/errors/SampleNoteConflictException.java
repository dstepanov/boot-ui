package io.github.jdubois.bootui.webfluxsample.errors;

/**
 * Sample domain exception handled by a {@code Mono<ResponseEntity<...>>} handler, so the BootUI
 * error-contract catalogue can prove it unwraps reactive return types and still reports the status as
 * built at runtime.
 */
public class SampleNoteConflictException extends RuntimeException {

    public SampleNoteConflictException(String message) {
        super(message);
    }
}
