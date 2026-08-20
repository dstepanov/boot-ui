package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Supplies live WebSocket session and subscription state to the engine.
 *
 * <p>Implemented in each adapter over its native registry (Spring's own session bookkeeping, or Quarkus
 * WebSockets Next {@code OpenConnections}). Implementations must be read-only and must never throw;
 * an implementation that cannot resolve its registry returns an empty list.</p>
 */
public interface WebSocketSessionProvider {

    /** Currently known sessions, in any order. Never {@code null}. */
    List<WebSocketSessionSnapshot> sessions();

    /** Currently known destination subscriptions, in any order. Never {@code null}. */
    default List<WebSocketSubscriptionSnapshot> subscriptions() {
        return List.of();
    }

    /**
     * Drops retained history for sessions that are no longer open.
     *
     * <p>Called when the user clears the panel. Implementations must keep every still-open session and its
     * subscriptions — clearing the panel is a view operation and must never make a live connection
     * disappear from the inventory. Implementations that retain no history do nothing.</p>
     */
    default void clearRetainedSessions() {}
}
