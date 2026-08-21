package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral description of one WebSocket callback declared by an application endpoint.
 *
 * @param type callback kind, matching {@code WebSocketCallbackDto#type()}
 * @param destination the declared destination for a routed message mapping, otherwise {@code null}
 * @param declaringClass the application class declaring the callback
 * @param method the callback method name
 * @param messageType the declared message type, or {@code null} when the framework exposes none
 */
public record WebSocketCallbackSnapshot(
        String type, String destination, String declaringClass, String method, String messageType) {

    public static WebSocketCallbackSnapshot lifecycle(String type, String declaringClass, String method) {
        return new WebSocketCallbackSnapshot(type, null, declaringClass, method, null);
    }

    public static List<WebSocketCallbackSnapshot> none() {
        return List.of();
    }
}
