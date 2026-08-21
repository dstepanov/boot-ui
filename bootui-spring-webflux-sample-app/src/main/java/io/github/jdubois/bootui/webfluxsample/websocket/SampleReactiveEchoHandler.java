package io.github.jdubois.bootui.webfluxsample.websocket;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/** Reactive echo endpoint used by the WebSockets panel's WebFlux coverage. */
public class SampleReactiveEchoHandler implements WebSocketHandler {

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return session.send(session.receive()
                .map(message -> session.textMessage("echo: " + message.getPayloadAsText()))
                .cache(0));
    }
}
