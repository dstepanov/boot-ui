package io.github.jdubois.bootui.micronaut.websocket;

import io.github.jdubois.bootui.spi.WebSocketSessionProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Micronaut {@link WebSocketSessionProvider}: the sessions the panel lists, both open and recently closed.
 *
 * <p>Micronaut has no registry of live WebSocket sessions a library can query, so both halves come from
 * {@link MicronautWebSocketConnectionCapture}, which tracks them from the framework's own session events.
 */
public final class MicronautWebSocketSessionProvider implements WebSocketSessionProvider {

    private final MicronautWebSocketConnectionCapture capture;

    public MicronautWebSocketSessionProvider(MicronautWebSocketConnectionCapture capture) {
        this.capture = capture;
    }

    @Override
    public List<WebSocketSessionSnapshot> sessions() {
        List<WebSocketSessionSnapshot> sessions = new ArrayList<>(capture.closedSessions());
        sessions.addAll(capture.openSessions());
        return List.copyOf(sessions);
    }

    @Override
    public void clearRetainedSessions() {
        capture.clear();
    }
}
