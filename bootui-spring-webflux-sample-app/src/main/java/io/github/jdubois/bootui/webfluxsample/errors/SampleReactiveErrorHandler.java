package io.github.jdubois.bootui.webfluxsample.errors;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

/**
 * Global error contract for the reactive sample application.
 *
 * <p>It exists so BootUI's error-contract catalogue is exercised on Spring WebFlux exactly as it is on
 * Spring MVC: the same {@code @RestControllerAdvice} declarations, but with reactive return types that
 * the catalogue must unwrap before classifying the response body.</p>
 */
@RestControllerAdvice
@Order(10)
public class SampleReactiveErrorHandler {

    @ExceptionHandler(SampleNoteNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<ProblemDetail> handleNoteNotFound(SampleNoteNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Note not found");
        problem.setDetail(exception.getMessage());
        return Mono.just(problem);
    }

    @ExceptionHandler(SampleNoteConflictException.class)
    public Mono<ResponseEntity<SampleErrorBody>> handleNoteConflict(SampleNoteConflictException exception) {
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new SampleErrorBody("note_conflict", exception.getMessage())));
    }
}
