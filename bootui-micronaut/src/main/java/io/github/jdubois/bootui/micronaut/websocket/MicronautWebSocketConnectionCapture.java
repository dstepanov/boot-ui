package io.github.jdubois.bootui.micronaut.websocket;

import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.event.WebSocketSessionClosedEvent;
import io.micronaut.websocket.event.WebSocketSessionOpenEvent;
import jakarta.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks WebSocket sessions for the WebSockets panel by observing Micronaut's session open and close
 * events.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's connection-event observer. Two things are recorded that
 * a live session list alone cannot answer: when each open session was <em>opened</em> (Micronaut's
 * {@code WebSocketSession} does not carry that), and a bounded history of sessions that have since
 * <em>closed</em> — without which a connection that opens and drops between two panel refreshes would be
 * invisible, which is exactly the case a developer is usually debugging.
 *
 * <p>Both are bounded: open sessions are tracked while they live, and the closed history is a fixed-size
 * ring, so a reconnect loop cannot grow memory without limit.
 */
@RequiresBootUi
@Requires(classes = WebSocketSessionOpenEvent.class)
@Singleton
public class MicronautWebSocketConnectionCapture implements ApplicationEventListener<Object> {

    /** Bound on the retained closed-session history, matching the other adapters. */
    static final int MAX_CLOSED_SESSIONS = 200;

    private final Map<String, TrackedSession> open = new LinkedHashMap<>();
    private final Deque<WebSocketSessionSnapshot> closed = new ArrayDeque<>();

    @Override
    public boolean supports(Object event) {
        return event instanceof WebSocketSessionOpenEvent || event instanceof WebSocketSessionClosedEvent;
    }

    @Override
    public void onApplicationEvent(Object event) {
        try {
            if (event instanceof WebSocketSessionOpenEvent opened) {
                onOpen(opened.getSource());
            } else if (event instanceof WebSocketSessionClosedEvent closedEvent) {
                onClose(closedEvent.getSource());
            }
        } catch (RuntimeException ex) {
            // Capture is best-effort and must never disturb the application's WebSocket handling.
        }
    }

    private synchronized void onOpen(WebSocketSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        open.put(session.getId(), new TrackedSession(session, System.currentTimeMillis()));
    }

    private synchronized void onClose(WebSocketSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        TrackedSession tracked = open.remove(session.getId());
        long openedAt = tracked == null ? System.currentTimeMillis() : tracked.openedAt();
        closed.addLast(snapshot(session, openedAt, false));
        while (closed.size() > MAX_CLOSED_SESSIONS) {
            closed.removeFirst();
        }
    }

    /** The sessions currently open, as the panel's neutral snapshot. */
    public synchronized List<WebSocketSessionSnapshot> openSessions() {
        List<WebSocketSessionSnapshot> sessions = new ArrayList<>(open.size());
        for (TrackedSession tracked : open.values()) {
            sessions.add(snapshot(tracked.session(), tracked.openedAt(), true));
        }
        return List.copyOf(sessions);
    }

    /** The bounded history of sessions that have closed since the last clear. */
    public synchronized List<WebSocketSessionSnapshot> closedSessions() {
        return List.copyOf(closed);
    }

    /** Drops the closed-session history. Open sessions are kept: they describe live connections. */
    public synchronized void clear() {
        closed.clear();
    }

    private static WebSocketSessionSnapshot snapshot(WebSocketSession session, long openedAt, boolean isOpen) {
        return new WebSocketSessionSnapshot(
                session.getId(),
                null,
                path(session),
                isOpen && session.isOpen(),
                openedAt,
                subprotocol(session),
                null,
                null,
                null);
    }

    private static String path(WebSocketSession session) {
        try {
            return session.getRequestURI() == null
                    ? null
                    : session.getRequestURI().getPath();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String subprotocol(WebSocketSession session) {
        try {
            return session.getSubprotocol().orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** One open session plus the instant BootUI first saw it, which the session itself does not record. */
    private record TrackedSession(WebSocketSession session, long openedAt) {}
}
