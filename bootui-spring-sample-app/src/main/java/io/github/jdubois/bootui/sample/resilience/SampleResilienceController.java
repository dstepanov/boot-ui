package io.github.jdubois.bootui.sample.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exercises the sample application's resilience policies on demand so the BootUI Resilience panel (and
 * the Playwright suite) can show real captured events instead of an empty buffer. Nothing runs on
 * startup or on page load: every event below is the direct result of an explicit request.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleResilienceController {

    private final FlakyInventoryClient inventoryClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public SampleResilienceController(
            FlakyInventoryClient inventoryClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.inventoryClient = inventoryClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/resilience")
    public Map<String, Object> exerciseResilience() {
        String reservation = inventoryClient.reserve("BOOTUI-1");
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
                "circuitBreakerFailures",
                failures,
                "circuitBreakerRejections",
                rejections,
                "circuitBreakerState",
                breaker.getState().name());
    }
}
