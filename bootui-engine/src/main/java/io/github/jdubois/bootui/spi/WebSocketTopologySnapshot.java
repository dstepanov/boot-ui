package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of the application's WebSocket topology.
 *
 * @param framework the detected integration id ({@code spring-websocket},
 *     {@code spring-webflux-websocket}, or {@code quarkus-websockets-next})
 * @param endpoints the declared endpoints
 * @param brokerPrefixes STOMP broker destination prefixes, empty when not applicable
 * @param applicationDestinationPrefixes STOMP application destination prefixes, empty when not applicable
 * @param userDestinationPrefix STOMP user destination prefix, or {@code null} when not applicable
 * @param frameCaptureSupported whether the integration lets BootUI observe individual frames
 * @param frameCaptureUnavailableReason why frame capture is unavailable, when it is not supported
 */
public record WebSocketTopologySnapshot(
        String framework,
        List<WebSocketEndpointSnapshot> endpoints,
        List<String> brokerPrefixes,
        List<String> applicationDestinationPrefixes,
        String userDestinationPrefix,
        boolean frameCaptureSupported,
        String frameCaptureUnavailableReason) {

    public WebSocketTopologySnapshot {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        brokerPrefixes = brokerPrefixes == null ? List.of() : List.copyOf(brokerPrefixes);
        applicationDestinationPrefixes =
                applicationDestinationPrefixes == null ? List.of() : List.copyOf(applicationDestinationPrefixes);
    }
}
