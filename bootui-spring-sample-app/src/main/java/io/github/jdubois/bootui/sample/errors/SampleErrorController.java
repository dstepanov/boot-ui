package io.github.jdubois.bootui.sample.errors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that fail on purpose so the sample application demonstrates a real error contract: two
 * globally handled domain failures, and one handled by a controller-local {@code @ExceptionHandler} that
 * takes precedence over any global advice.
 *
 * <p>Nothing fails on page load — each failure is triggered explicitly by calling its endpoint, which is
 * also what makes the Exceptions panel's cross-link to the declared handler observable.</p>
 */
@RestController
@RequestMapping("/api/errors")
public class SampleErrorController {

    @GetMapping("/not-found")
    public String notFound() {
        throw new SampleOrderNotFoundException("No order with id 4711");
    }

    @GetMapping("/conflict")
    public String conflict() {
        throw new SampleOrderConflictException("Order 4711 has already been shipped");
    }

    @GetMapping("/local")
    public String local() {
        throw new SampleOrderRejectedException("Order 4711 was rejected by the payment provider");
    }

    @ExceptionHandler(SampleOrderRejectedException.class)
    public ProblemDetail handleLocally(SampleOrderRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setTitle("Order rejected");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}
