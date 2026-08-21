package io.github.jdubois.bootui.sample.faulttolerance;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * A deliberately flaky call protected by Spring Retry, so the sample application demonstrates a retry
 * policy that BootUI discovers from the annotation and retry attempts that BootUI observes through the
 * {@code RetryListener} SPI.
 *
 * <p>The failure is simulated locally: no network call is ever made.</p>
 */
@Service
public class FlakyInventoryClient {

    private static final Logger logger = LoggerFactory.getLogger(FlakyInventoryClient.class);

    private final AtomicInteger calls = new AtomicInteger();

    /**
     * Fails on the first attempt of every invocation and succeeds on the retry, so a single request
     * produces exactly one retry event.
     */
    @Retryable(retryFor = IllegalStateException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
    public String reserve(String sku) {
        if (calls.incrementAndGet() % 2 == 1) {
            throw new IllegalStateException("inventory service temporarily unavailable");
        }
        return "reserved " + sku;
    }

    @Recover
    String reserveFailed(IllegalStateException failure, String sku) {
        logger.info("giving up on reserving {}", sku);
        return "unavailable " + sku;
    }
}
