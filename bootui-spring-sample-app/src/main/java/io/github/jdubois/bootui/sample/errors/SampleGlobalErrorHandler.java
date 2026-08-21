package io.github.jdubois.bootui.sample.errors;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global error contract for the sample application, giving BootUI's error-contract catalogue realistic
 * rows to read: a fully resolved {@code ProblemDetail} handler with a declared status, and a
 * {@code ResponseEntity} handler whose status is built at runtime and whose media type is declared.
 *
 * <p>The catalogue reads this class's declarations only — it never instantiates the advice or throws an
 * exception to observe a response.</p>
 */
@RestControllerAdvice
@Order(10)
public class SampleGlobalErrorHandler {

    @ExceptionHandler(SampleOrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleOrderNotFound(SampleOrderNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Order not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    @ExceptionHandler(value = SampleOrderConflictException.class, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SampleErrorBody> handleOrderConflict(SampleOrderConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new SampleErrorBody("order_conflict", exception.getMessage()));
    }
}
