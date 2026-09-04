package io.github.jdubois.bootui.micronaut.web;

import io.micronaut.http.sse.Event;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Builds the bounded, coalescing server-sent-event streams the console's live panels subscribe to — the
 * Micronaut analogue of the Quarkus adapter's {@code SseStreams}.
 *
 * <p>Two properties matter and are shared with that adapter. Streams are <strong>bounded</strong>: a panel
 * left open in many browser tabs cannot accumulate unbounded server-side subscriptions, so a stream beyond
 * the cap completes immediately and the UI falls back to polling. And ticks are <strong>coalescing</strong>:
 * the stream carries a content-free "something changed" signal with a {@link FluxSink.OverflowStrategy#LATEST}
 * policy, so a burst of a thousand captured events becomes one notification the client answers with one
 * re-fetch, rather than a thousand payloads pushed at a browser that only re-renders once.
 */
public final class SseStreams {

    /** The event name the shared UI listens for on every tick stream. */
    static final String UPDATE_EVENT = "update";

    private SseStreams() {}

    /** A source of change notifications that hands back an unsubscribe hook. */
    @FunctionalInterface
    public interface ChangeSource {
        Runnable subscribe(Runnable onChange);
    }

    /**
     * A tick stream over {@code source}, refusing to open beyond {@code maxStreams} concurrent subscribers
     * and always releasing its slot and its subscription when the client goes away.
     */
    static Publisher<Event<String>> updates(AtomicInteger openStreams, int maxStreams, ChangeSource source) {
        return Flux.create(
                sink -> {
                    if (openStreams.incrementAndGet() > maxStreams) {
                        openStreams.decrementAndGet();
                        sink.complete();
                        return;
                    }
                    Runnable unsubscribe = source.subscribe(() -> {
                        if (!sink.isCancelled()) {
                            sink.next(tick());
                        }
                    });
                    sink.onDispose(() -> {
                        unsubscribe.run();
                        openStreams.decrementAndGet();
                    });
                },
                FluxSink.OverflowStrategy.LATEST);
    }

    /**
     * A stream that replays a backlog and then follows live items, used by the Log Tail panel where lines
     * are the payload and must not be dropped.
     */
    static <T> Publisher<Event<T>> replaying(
            AtomicInteger openStreams, int maxStreams, String eventName, ReplayingSource<T> source) {
        return Flux.create(
                sink -> {
                    if (openStreams.incrementAndGet() > maxStreams) {
                        openStreams.decrementAndGet();
                        sink.complete();
                        return;
                    }
                    Subscription<T> subscription = source.subscribe(item -> {
                        if (!sink.isCancelled()) {
                            sink.next(Event.of(item).name(eventName));
                        }
                    });
                    sink.onDispose(() -> {
                        subscription.unsubscribe().run();
                        openStreams.decrementAndGet();
                    });
                    for (T item : subscription.backlog()) {
                        if (sink.isCancelled()) {
                            break;
                        }
                        sink.next(Event.of(item).name(eventName));
                    }
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    /** A source that hands back the items already buffered plus a live subscription. */
    @FunctionalInterface
    public interface ReplayingSource<T> {
        Subscription<T> subscribe(java.util.function.Consumer<T> listener);
    }

    /** The backlog and unsubscribe hook a {@link ReplayingSource} returns. */
    public record Subscription<T>(java.util.List<T> backlog, Runnable unsubscribe) {}

    private static Event<String> tick() {
        return Event.of(UPDATE_EVENT).name(UPDATE_EVENT);
    }
}
