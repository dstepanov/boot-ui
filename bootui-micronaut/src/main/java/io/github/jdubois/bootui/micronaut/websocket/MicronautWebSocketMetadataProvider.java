package io.github.jdubois.bootui.micronaut.websocket;

import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import io.micronaut.context.BeanContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * Micronaut {@link WebSocketMetadataProvider}: inventories the application's {@code @ServerWebSocket}
 * endpoints and their lifecycle callbacks.
 *
 * <p>The topology is read from compile-time bean metadata, so an endpoint appears whether or not anyone has
 * ever connected to it — which is the point of a topology view.
 *
 * <p>Frame capture is honestly reported as unsupported: Micronaut binds WebSocket messages directly to the
 * annotated method at compile time and exposes no message-interception seam, so BootUI records session
 * open/close only. Per-frame metadata is available on the Spring MVC stack, where STOMP provides a decorator
 * seam. Claiming otherwise would leave the panel silently empty.
 */
public final class MicronautWebSocketMetadataProvider implements WebSocketMetadataProvider {

    public static final String FRAMEWORK = "micronaut-websocket";

    static final String CAPTURE_UNAVAILABLE =
            "Micronaut binds WebSocket messages directly to the annotated handler method and exposes no"
                    + " message-interception SPI, so BootUI records connection open/close events only."
                    + " Per-frame metadata is available on the Spring MVC stack, where STOMP provides a"
                    + " decorator seam.";

    private final BeanContext beanContext;

    public MicronautWebSocketMetadataProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public WebSocketTopologySnapshot topology() {
        List<WebSocketEndpointSnapshot> endpoints = endpoints();
        if (endpoints.isEmpty()) {
            return null;
        }
        return new WebSocketTopologySnapshot(
                FRAMEWORK, endpoints, List.of(), List.of(), null, false, CAPTURE_UNAVAILABLE);
    }

    private List<WebSocketEndpointSnapshot> endpoints() {
        if (beanContext == null) {
            return List.of();
        }
        List<WebSocketEndpointSnapshot> endpoints = new ArrayList<>();
        for (BeanDefinition<?> definition :
                beanContext.getBeanDefinitions(io.micronaut.core.type.Argument.OBJECT_ARGUMENT)) {
            if (!definition.hasAnnotation(ServerWebSocket.class)) {
                continue;
            }
            Class<?> beanType = definition.getBeanType();
            String path = definition.stringValue(ServerWebSocket.class).orElse(null);
            endpoints.add(new WebSocketEndpointSnapshot(
                    beanType.getName(),
                    path,
                    "ENDPOINT",
                    beanType.getName(),
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    callbacks(definition, beanType),
                    null,
                    false));
        }
        return List.copyOf(endpoints);
    }

    private static List<WebSocketCallbackSnapshot> callbacks(BeanDefinition<?> definition, Class<?> beanType) {
        List<WebSocketCallbackSnapshot> callbacks = new ArrayList<>();
        for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
            String type = callbackType(method);
            if (type != null) {
                callbacks.add(WebSocketCallbackSnapshot.lifecycle(type, beanType.getName(), method.getMethodName()));
            }
        }
        return List.copyOf(callbacks);
    }

    private static String callbackType(ExecutableMethod<?, ?> method) {
        if (method.hasAnnotation(OnOpen.class)) {
            return "OPEN";
        }
        if (method.hasAnnotation(OnMessage.class)) {
            return "MESSAGE";
        }
        if (method.hasAnnotation(OnClose.class)) {
            return "CLOSE";
        }
        if (method.hasAnnotation(OnError.class)) {
            return "ERROR";
        }
        return null;
    }
}
