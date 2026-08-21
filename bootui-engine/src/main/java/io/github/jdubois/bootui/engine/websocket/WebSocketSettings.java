package io.github.jdubois.bootui.engine.websocket;

/**
 * Immutable, static bounds for the WebSockets panel.
 *
 * <p>Every cardinality is bounded independently so a burst of frames can never evict endpoints, sessions,
 * or subscriptions — and, because the activity buffer is owned solely by this panel, WebSocket traffic can
 * never evict another panel's retained data.</p>
 *
 * @param enabled whether BootUI installs its WebSocket capture at all
 * @param capturing whether capture starts recording immediately (runtime pause/resume is separate)
 * @param maxEndpoints maximum endpoints serialized
 * @param maxSessions maximum sessions serialized
 * @param maxSubscriptions maximum subscriptions serialized
 * @param maxActivityEntries maximum retained activity entries
 * @param maxTrackedSessions maximum sessions for which per-session counters are retained
 */
public record WebSocketSettings(
        boolean enabled,
        boolean capturing,
        int maxEndpoints,
        int maxSessions,
        int maxSubscriptions,
        int maxActivityEntries,
        int maxTrackedSessions) {

    public WebSocketSettings {
        maxEndpoints = clamp(maxEndpoints, 1, 5_000);
        maxSessions = clamp(maxSessions, 1, 10_000);
        maxSubscriptions = clamp(maxSubscriptions, 1, 10_000);
        maxActivityEntries = clamp(maxActivityEntries, 1, 50_000);
        maxTrackedSessions = clamp(maxTrackedSessions, 1, 20_000);
    }

    public static WebSocketSettings defaults() {
        return new WebSocketSettings(true, true, 200, 200, 500, 500, 2_000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
