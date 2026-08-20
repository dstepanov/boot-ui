package io.github.jdubois.bootui.quarkus.websocket;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.quarkus.websockets.next.Closed;
import io.quarkus.websockets.next.Open;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observes WebSockets Next connection lifecycle events so the panel can show when connections opened and
 * closed, and retains recently-closed connections that {@code OpenConnections} no longer lists.
 *
 * <p>WebSockets Next fires {@code @Observes @Open}/{@code @Closed WebSocketConnection} CDI events. These are
 * lifecycle events only — no message ever reaches this class, which is precisely why frame capture is
 * reported unsupported on Quarkus instead of being silently approximated.</p>
 *
 * <p>Imports {@code io.quarkus.websockets.next}, so it is registered only by the class-presence-gated build
 * step and excluded from bean discovery when the extension is absent.</p>
 */
@ApplicationScoped
public class QuarkusWebSocketConnectionCapture {

    private final WebSocketActivityRecorder recorder;
    private final int maxClosed;
    private final int maxOpenedTimestamps;

    private final Map<String, Long> openedAt;
    private final Map<String, WebSocketSessionSnapshot> closed;
    private final Object lock = new Object();

    public QuarkusWebSocketConnectionCapture(WebSocketActivityRecorder recorder, WebSocketSettings settings) {
        this.recorder = recorder;
        // The same configured caps the Spring registry honors, so bootui.websockets.* means the same thing
        // on both stacks instead of silently doing nothing here.
        this.maxClosed = settings.maxSessions();
        this.maxOpenedTimestamps = Math.max(settings.maxSessions(), settings.maxTrackedSessions());
        this.openedAt = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > QuarkusWebSocketConnectionCapture.this.maxOpenedTimestamps;
            }
        };
        this.closed = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, WebSocketSessionSnapshot> eldest) {
                return size() > QuarkusWebSocketConnectionCapture.this.maxClosed;
            }
        };
    }

    void onOpen(@Observes @Open WebSocketConnection connection) {
        try {
            long now = System.currentTimeMillis();
            synchronized (lock) {
                openedAt.put(connection.id(), now);
                closed.remove(connection.id());
            }
            recorder.recordFrame(
                    connection.endpointId(),
                    connection.id(),
                    WebSocketActivityRecorder.Direction.INBOUND,
                    WebSocketActivityRecorder.FrameType.OPEN,
                    null,
                    null,
                    null,
                    true,
                    null);
        } catch (RuntimeException ignored) {
            // Observation must never break the application's connection handling.
        }
    }

    void onClose(@Observes @Closed WebSocketConnection connection) {
        try {
            Integer closeStatus = connection.closeReason() == null
                    ? null
                    : connection.closeReason().getCode();
            WebSocketSessionSnapshot snapshot = new WebSocketSessionSnapshot(
                    connection.id(),
                    connection.endpointId(),
                    QuarkusWebSocketSessionProvider.path(connection),
                    false,
                    openedAt(connection),
                    connection.subprotocol(),
                    QuarkusWebSocketSessionProvider.remoteAddress(connection),
                    QuarkusWebSocketSessionProvider.localAddress(connection),
                    closeStatus);
            synchronized (lock) {
                closed.put(connection.id(), snapshot);
            }
            recorder.recordFrame(
                    connection.endpointId(),
                    connection.id(),
                    WebSocketActivityRecorder.Direction.INBOUND,
                    WebSocketActivityRecorder.FrameType.CLOSE,
                    null,
                    null,
                    null,
                    true,
                    null);
        } catch (RuntimeException ignored) {
            // Observation must never break the application's connection handling.
        }
    }

    /**
     * Returns when BootUI first observed the connection. Falls back to the connection's own creation time so
     * connections opened before BootUI's observer was resolved still report an honest timestamp.
     */
    long openedAt(WebSocketConnection connection) {
        synchronized (lock) {
            Long observed = openedAt.get(connection.id());
            if (observed != null) {
                return observed;
            }
        }
        try {
            return connection.creationTime() == null
                    ? System.currentTimeMillis()
                    : connection.creationTime().toEpochMilli();
        } catch (RuntimeException ex) {
            return System.currentTimeMillis();
        }
    }

    /** Recently-closed connections, which {@code OpenConnections} no longer lists. */
    Collection<WebSocketSessionSnapshot> closedConnections() {
        synchronized (lock) {
            return new ArrayList<>(closed.values());
        }
    }

    /** Drops the retained closed-connection inventory. Never touches a live connection. */
    public void clear() {
        synchronized (lock) {
            closed.clear();
        }
    }
}
