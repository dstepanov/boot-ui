package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral snapshot of one live or recently closed WebSocket session.
 *
 * <p>{@code rawId} is the framework's own session identifier. The engine hashes it before serialization,
 * so adapters may pass it through unchanged but it never reaches the browser.</p>
 *
 * @param rawId the framework session identifier, hashed by the engine before serialization
 * @param endpointId the endpoint the session belongs to
 * @param path the handshake path, without any query string
 * @param open whether the session is still open
 * @param openedAt epoch milliseconds when the session opened
 * @param subprotocol negotiated subprotocol, or {@code null}
 * @param remoteAddress remote transport address, or {@code null}
 * @param localAddress local transport address, or {@code null}
 * @param closeStatus close status code when closed, or {@code null}
 */
public record WebSocketSessionSnapshot(
        String rawId,
        String endpointId,
        String path,
        boolean open,
        long openedAt,
        String subprotocol,
        String remoteAddress,
        String localAddress,
        Integer closeStatus) {}
