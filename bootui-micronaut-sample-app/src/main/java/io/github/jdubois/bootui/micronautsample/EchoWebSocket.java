package io.github.jdubois.bootui.micronautsample;

import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

/** A trivial echo endpoint, so the console's WebSockets panel has a real endpoint and sessions to show. */
@ServerWebSocket("/echo")
public class EchoWebSocket {

    @OnOpen
    public void onOpen(WebSocketSession session) {
        session.sendSync("connected");
    }

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        session.sendSync("echo: " + message);
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        // Nothing to clean up; the method exists so the panel shows a close callback.
    }
}
