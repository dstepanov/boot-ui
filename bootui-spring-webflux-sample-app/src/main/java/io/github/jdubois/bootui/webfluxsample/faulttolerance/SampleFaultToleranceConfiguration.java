package io.github.jdubois.bootui.webfluxsample.faulttolerance;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reactive counterpart of the servlet sample app's {@code SampleFaultToleranceConfiguration}: declares the
 * Resilience4j registries the BootUI Fault Tolerance panel reads, so the reactive sample demonstrates the
 * panel with a real inventory rather than an empty one.
 *
 * <p>Only the circuit breaker and retry modules are declared here (the servlet sample covers all six),
 * because the point of this app is to prove the shared read path works unchanged on WebFlux - not to
 * duplicate the servlet sample's inventory.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SampleFaultToleranceConfiguration {

    @Bean
    CircuitBreakerRegistry sampleCircuitBreakerRegistry() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(8)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
        registry.circuitBreaker("inventory-service");
        return registry;
    }

    @Bean
    RetryRegistry sampleRetryRegistry() {
        RetryRegistry registry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(20))
                .build());
        registry.retry("inventory-service");
        return registry;
    }
}
