package io.github.jdubois.bootui.webfluxsample.faulttolerance;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive counterpart of the servlet sample app's {@code SampleFaultToleranceController}: exercises the
 * sample's Resilience4j policies on demand so the BootUI Fault Tolerance panel shows real captured events on
 * WebFlux too. Nothing runs on startup or on page load - every event below is the direct result of an
 * explicit request.
 *
 * <p>The protected work is deliberately blocking and therefore runs on
 * {@code Schedulers.boundedElastic()}, the same off-event-loop pattern the rest of this sample uses.
 * That is also the interesting case for BootUI: the events are published from a scheduler thread rather
 * than the request thread, and the shared recorder must still capture them.</p>
 */
@RestController
@RequestMapping("/api/sample")
public class SampleFaultToleranceController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public SampleFaultToleranceController(CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    @GetMapping("/fault-tolerance")
    public Mono<Map<String, Object>> exerciseFaultTolerance() {
        return Mono.fromCallable(this::exercise).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> exercise() {
        Retry retry = retryRegistry.retry("inventory-service");
        AtomicInteger attempts = new AtomicInteger();
        String reservation;
        try {
            reservation = retry.executeSupplier(() -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("inventory service unreachable");
                }
                return "reserved";
            });
        } catch (RuntimeException exhausted) {
            reservation = "unavailable";
        }

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("inventory-service");
        int failures = 0;
        int rejections = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                breaker.executeRunnable(() -> {
                    throw new IllegalStateException("inventory service unreachable");
                });
            } catch (CallNotPermittedException rejected) {
                rejections++;
            } catch (RuntimeException failure) {
                failures++;
            }
        }
        return Map.of(
                "reservation",
                reservation,
                "retryAttempts",
                attempts.get(),
                "circuitBreakerFailures",
                failures,
                "circuitBreakerRejections",
                rejections,
                "circuitBreakerState",
                breaker.getState().name());
    }
}
