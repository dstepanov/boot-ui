package io.github.jdubois.bootui.sample.faulttolerance;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exercises the sample application's fault tolerance policies on demand so the BootUI Fault Tolerance panel (and
 * the Playwright suite) can show real captured events instead of an empty buffer. Nothing runs on
 * startup or on page load: every event below is the direct result of an explicit request.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleFaultToleranceController {

    private final FlakyInventoryClient inventoryClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public SampleFaultToleranceController(
            FlakyInventoryClient inventoryClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.inventoryClient = inventoryClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/fault-tolerance")
    public Map<String, Object> exerciseFaultTolerance() {
        String reservation = triggerRetry();
        CircuitBreakerExercise circuitBreaker = triggerCircuitBreaker();
        return Map.of(
                "reservation",
                reservation,
                "circuitBreakerFailures",
                circuitBreaker.failures(),
                "circuitBreakerRejections",
                circuitBreaker.rejections(),
                "circuitBreakerState",
                circuitBreaker.state());
    }

    @GetMapping("/fault-tolerance/retry")
    public Map<String, Object> exerciseRetry() {
        return Map.of("reservation", triggerRetry(), "policy", "FlakyInventoryClient#reserve");
    }

    @GetMapping("/fault-tolerance/circuit-breaker")
    public Map<String, Object> exerciseCircuitBreaker() {
        CircuitBreakerExercise exercise = triggerCircuitBreaker();
        return Map.of(
                "policy",
                "inventory-service",
                "failures",
                exercise.failures(),
                "rejections",
                exercise.rejections(),
                "state",
                exercise.state());
    }

    private String triggerRetry() {
        return inventoryClient.reserve("BOOTUI-1");
    }

    private CircuitBreakerExercise triggerCircuitBreaker() {
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
        return new CircuitBreakerExercise(
                failures, rejections, breaker.getState().name());
    }

    private record CircuitBreakerExercise(int failures, int rejections, String state) {}
}
