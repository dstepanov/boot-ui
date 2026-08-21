package io.github.jdubois.bootui.webfluxsample.errors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reactive endpoints that fail on purpose so the sample application demonstrates a real error contract:
 * two globally handled domain failures, and one handled by a controller-local {@code @ExceptionHandler}
 * that takes precedence over the global advice.
 *
 * <p>Nothing fails on page load — each failure is triggered explicitly by calling its endpoint.</p>
 */
@RestController
@RequestMapping("/api/errors")
public class SampleErrorController {

    @GetMapping("/not-found")
    public Mono<String> notFound() {
        return Mono.error(new SampleNoteNotFoundException("No note with id 4711"));
    }

    @GetMapping("/conflict")
    public Mono<String> conflict() {
        return Mono.error(new SampleNoteConflictException("Note 4711 was modified concurrently"));
    }

    @GetMapping("/local")
    public Mono<String> local() {
        return Mono.error(new SampleNoteRejectedException("Note 4711 failed validation"));
    }

    @ExceptionHandler(SampleNoteRejectedException.class)
    public Mono<ProblemDetail> handleLocally(SampleNoteRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setTitle("Note rejected");
        problem.setDetail(exception.getMessage());
        return Mono.just(problem);
    }
}
