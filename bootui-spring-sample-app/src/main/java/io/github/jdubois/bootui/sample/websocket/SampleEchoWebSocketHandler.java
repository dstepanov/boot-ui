package io.github.jdubois.bootui.sample.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * A native (non-STOMP) WebSocket handler, so the BootUI WebSockets panel shows the second endpoint shape it has
 * to report honestly: full topology, but no frame-capture seam, because decorating a plain handler endpoint
 * would require reaching into non-public Spring state.
 */
public class SampleEchoWebSocketHandler extends TextWebSocketHandler {

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.sendMessage(new TextMessage("echo: " + message.getPayload()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Nothing to release; the sample handler is stateless.
    }
}
