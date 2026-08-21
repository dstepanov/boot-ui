package io.github.jdubois.bootui.sample.websocket;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * A minimal STOMP application controller so the WebSockets panel can show a real {@code @MessageMapping}
 * destination under the {@code /app} prefix and a broker destination under {@code /topic}.
 */
@Controller
public class SampleChatController {

    @MessageMapping("/chat")
    @SendTo("/topic/chat")
    public SampleChatMessage echo(SampleChatMessage message) {
        return new SampleChatMessage(message.sender(), "echo: " + message.text());
    }
}
