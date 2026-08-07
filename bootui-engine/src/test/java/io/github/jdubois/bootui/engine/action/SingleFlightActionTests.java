package io.github.jdubois.bootui.engine.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SingleFlightActionTests {

    @Test
    void duplicateFailsFastWithoutInvokingItsSupplier() throws Exception {
        SingleFlightAction singleFlight = new SingleFlightAction();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<String> result = new AtomicReference<>();

        Thread winner = new Thread(() -> result.set(singleFlight.run("architecture.scan", () -> {
            invocations.incrementAndGet();
            entered.countDown();
            await(release);
            return "done";
        })));
        winner.start();

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> singleFlight.run("architecture.scan", () -> {
                    invocations.incrementAndGet();
                    return "duplicate";
                }))
                .isInstanceOfSatisfying(ActionBusyException.class, failure -> {
                    assertThat(failure.result().error()).isEqualTo(SingleFlightAction.BUSY_ERROR);
                    assertThat(failure.result().operation()).isEqualTo("architecture.scan");
                    assertThat(failure.result().activeOperation()).isEqualTo("architecture.scan");
                    assertThat(failure.result().message())
                            .isEqualTo(
                                    "Operation 'architecture.scan' cannot start while 'architecture.scan' is in progress.");
                });

        release.countDown();
        winner.join(5000);
        assertThat(result.get()).isEqualTo("done");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void identifiesCrossOperationConflict() throws Exception {
        SingleFlightAction singleFlight = new SingleFlightAction();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread winner = new Thread(() -> singleFlight.run("heap-dump.capture", () -> {
            entered.countDown();
            await(release);
            return null;
        }));
        winner.start();

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> singleFlight.run("heap-dump.analyze", () -> null))
                .isInstanceOfSatisfying(ActionBusyException.class, failure -> {
                    assertThat(failure.result().operation()).isEqualTo("heap-dump.analyze");
                    assertThat(failure.result().activeOperation()).isEqualTo("heap-dump.capture");
                });

        release.countDown();
        winner.join(5000);
    }

    @Test
    void releasesAdmissionAfterFailure() {
        SingleFlightAction singleFlight = new SingleFlightAction();

        assertThatThrownBy(() -> singleFlight.run("memory.scan", () -> {
                    throw new IllegalStateException("failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(singleFlight.run("memory.scan", () -> "retried")).isEqualTo("retried");
    }

    @Test
    void releasesAdmissionAfterError() {
        SingleFlightAction singleFlight = new SingleFlightAction();

        assertThatThrownBy(() -> singleFlight.run("memory.scan", () -> {
                    throw new AssertionError("failed");
                }))
                .isInstanceOf(AssertionError.class);

        assertThat(singleFlight.run("memory.scan", () -> "retried")).isEqualTo("retried");
    }

    @Test
    void independentInstancesRunConcurrently() throws Exception {
        SingleFlightAction first = new SingleFlightAction();
        SingleFlightAction second = new SingleFlightAction();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Thread firstThread = new Thread(() -> first.run("architecture.scan", () -> {
            entered.countDown();
            await(release);
            return null;
        }));
        Thread secondThread = new Thread(() -> second.run("architecture.scan", () -> {
            entered.countDown();
            await(release);
            return null;
        }));
        firstThread.start();
        secondThread.start();

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        firstThread.join(5000);
        secondThread.join(5000);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
