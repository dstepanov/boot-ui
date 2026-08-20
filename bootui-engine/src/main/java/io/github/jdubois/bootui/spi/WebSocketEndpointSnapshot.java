package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral description of one WebSocket endpoint declared by the running application.
 *
 * <p>Adapters translate their native registry (Spring's {@code WebSocketHandlerMapping} and
 * {@code SimpAnnotationMethodMessageHandler}, or the build-time Quarkus WebSockets Next endpoint index)
 * into this record, so the engine never imports a WebSocket API.</p>
 *
 * @param id stable endpoint identifier
 * @param path normalized handshake path or endpoint path template
 * @param kind {@code HANDLER}, {@code STOMP}, or {@code QUARKUS}
 * @param handlerClass the implementing application class, or {@code null}
 * @param subprotocols declared subprotocols
 * @param sockJs whether SockJS fallback transports are enabled
 * @param allowedOrigins declared handshake origin policy entries
 * @param interceptors handshake/channel interceptor class names
 * @param callbacks declared callbacks and message mappings
 * @param inboundProcessingMode Quarkus inbound processing mode, or {@code null}
 * @param captureInstalled whether BootUI has a sanctioned observation seam on this endpoint
 */
public record WebSocketEndpointSnapshot(
        String id,
        String path,
        String kind,
        String handlerClass,
        List<String> subprotocols,
        boolean sockJs,
        List<String> allowedOrigins,
        List<String> interceptors,
        List<WebSocketCallbackSnapshot> callbacks,
        String inboundProcessingMode,
        boolean captureInstalled) {

    public WebSocketEndpointSnapshot {
        subprotocols = subprotocols == null ? List.of() : List.copyOf(subprotocols);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }
}
