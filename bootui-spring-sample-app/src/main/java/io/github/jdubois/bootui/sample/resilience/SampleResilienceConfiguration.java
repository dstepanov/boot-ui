package io.github.jdubois.bootui.sample.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Declares one Resilience4j policy of each kind the BootUI Resilience panel understands, plus Spring
 * Retry, so the panel has a representative inventory to show in the sample application and in the
 * Playwright suite.
 *
 * <p>The registries are created programmatically rather than through the Resilience4j Spring Boot
 * starter on purpose: that is the harder case for BootUI to read, and it keeps the sample free of an
 * extra starter whose own auto-configuration would mask whether BootUI itself found the policies.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableRetry
public class SampleResilienceConfiguration {

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
                .waitDuration(Duration.ofMillis(50))
                .build());
        registry.retry("inventory-service");
        return registry;
    }

    @Bean
    RateLimiterRegistry sampleRateLimiterRegistry() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(250))
                .build());
        registry.rateLimiter("catalog-api");
        return registry;
    }

    @Bean
    BulkheadRegistry sampleBulkheadRegistry() {
        BulkheadRegistry registry = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(4)
                .maxWaitDuration(Duration.ofMillis(10))
                .build());
        registry.bulkhead("report-export");
        return registry;
    }

    @Bean
    TimeLimiterRegistry sampleTimeLimiterRegistry() {
        TimeLimiterRegistry registry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2))
                .build());
        registry.timeLimiter("slow-backend");
        return registry;
    }
}
