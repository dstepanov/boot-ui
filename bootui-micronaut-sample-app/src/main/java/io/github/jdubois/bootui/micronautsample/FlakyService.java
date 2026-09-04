package io.github.jdubois.bootui.micronautsample;

import io.micronaut.retry.annotation.CircuitBreaker;
import io.micronaut.retry.annotation.Retryable;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deliberately unreliable bean, so the console's Fault Tolerance panel has real policies to inventory and
 * real retry and circuit events to show once something calls it.
 */
@Singleton
public class FlakyService {

    private final AtomicInteger calls = new AtomicInteger();

    @Retryable(attempts = "3", delay = "100ms")
    public String flaky() {
        if (calls.incrementAndGet() % 3 != 0) {
            throw new IllegalStateException("Transient failure #" + calls.get());
        }
        return "succeeded after " + calls.get() + " calls";
    }

    @CircuitBreaker(attempts = "2", delay = "50ms", reset = "5s")
    public String protectedCall() {
        return "ok";
    }
}
