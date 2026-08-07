package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

class TelemetryStoreTests {

    private static NormalizedSpan span(String traceId, String spanId) {
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "GET /sample",
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of(),
                List.of());
    }

    @Test
    void storeClampsConfiguredTraceCapacity() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 0, 500, 4096));

        store.add(span("trace-a", "span-a"));
        store.add(span("trace-b", "span-b"));

        assertThat(store.capacity()).isEqualTo(1);
        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.findTrace("trace-a")).isNull();
        assertThat(store.findTrace("trace-b")).isNotNull();
    }

    @Test
    void storeClampsConfiguredSpanCapacity() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, Integer.MAX_VALUE, 4096));

        for (int i = 0; i < TelemetryStore.HARD_MAX_SPANS_PER_TRACE + 5; i++) {
            store.add(span("trace-a", "span-" + i));
        }

        assertThat(store.findTrace("trace-a").spans()).hasSize(TelemetryStore.HARD_MAX_SPANS_PER_TRACE);
    }

    @Test
    void suspendForIdleClearsAndStopsIngestionUntilResumed() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        assertThat(store.add(span("trace-a", "span-a"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);

        store.suspendForIdle();
        assertThat(store.retainedTraceCount()).isZero();
        assertThat(store.add(span("trace-b", "span-b"), false)).isFalse();
        assertThat(store.retainedTraceCount()).isZero();

        store.resumeFromIdle();
        assertThat(store.add(span("trace-c", "span-c"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);
    }

    @Test
    void traceReadsReturnIsolatedImmutableSnapshots() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        store.add(span("trace-a", "span-a"));

        List<TelemetryStore.TraceBucket> recentSnapshot = store.recentTraces(10);
        TelemetryStore.TraceBucket foundSnapshot = store.findTrace("trace-a");
        store.add(span("trace-a", "span-b"));

        assertThat(recentSnapshot)
                .singleElement()
                .satisfies(bucket -> assertThat(bucket.spans())
                        .extracting(NormalizedSpan::spanId)
                        .containsExactly("span-a"));
        assertThat(foundSnapshot.spans()).extracting(NormalizedSpan::spanId).containsExactly("span-a");
        assertThat(store.findTrace("trace-a").spans())
                .extracting(NormalizedSpan::spanId)
                .containsExactly("span-a", "span-b");
        assertThatThrownBy(() -> recentSnapshot.add(foundSnapshot)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> foundSnapshot.spans().add(span("trace-a", "span-c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentIngestionAndReadsUseConsistentSnapshots() throws Exception {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 1000, 4096));
        int writerCount = 4;
        int spansPerWriter = 200;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writerCount + 1);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int writer = 0; writer < writerCount; writer++) {
                int writerId = writer;
                writers.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < spansPerWriter; i++) {
                        store.add(span("trace-a", "span-" + writerId + "-" + i));
                    }
                    return null;
                }));
            }
            Future<?> reader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    for (TelemetryStore.TraceBucket bucket : store.recentTraces(10)) {
                        for (NormalizedSpan storedSpan : bucket.spans()) {
                            assertThat(storedSpan.traceId()).isEqualTo(bucket.traceId());
                        }
                    }
                    TelemetryStore.TraceBucket found = store.findTrace("trace-a");
                    if (found != null) {
                        assertThat(found.spans())
                                .allSatisfy(storedSpan ->
                                        assertThat(storedSpan.traceId()).isEqualTo(found.traceId()));
                    }
                }
                return null;
            });

            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(10, TimeUnit.SECONDS);
            }
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(store.findTrace("trace-a").spans()).hasSize(writerCount * spansPerWriter);
    }

    @Test
    void addRechecksIdleSuspensionAfterWaitingForWriteLock() throws Exception {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        ReentrantReadWriteLock storeLock = storeLock(store);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> addThread = new AtomicReference<>();
        storeLock.readLock().lock();
        try {
            Future<Boolean> add = executor.submit(() -> {
                addThread.set(Thread.currentThread());
                return store.add(span("trace-a", "span-a"), false);
            });
            awaitCondition(() -> addThread.get() != null && storeLock.hasQueuedThread(addThread.get()));

            Future<?> suspend = executor.submit(store::suspendForIdle);
            awaitCondition(() -> storeLock.getQueueLength() == 2);

            storeLock.readLock().unlock();
            assertThat(add.get(10, TimeUnit.SECONDS)).isFalse();
            suspend.get(10, TimeUnit.SECONDS);
        } finally {
            if (storeLock.getReadHoldCount() > 0) {
                storeLock.readLock().unlock();
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(store.retainedTraceCount()).isZero();
    }

    private static ReentrantReadWriteLock storeLock(TelemetryStore store) throws Exception {
        Field lockField = TelemetryStore.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        return (ReentrantReadWriteLock) lockField.get(store);
    }

    private static void awaitCondition(Condition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.evaluate()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not met before timeout");
            }
            Thread.sleep(1);
        }
    }

    @FunctionalInterface
    private interface Condition {

        boolean evaluate();
    }
}
