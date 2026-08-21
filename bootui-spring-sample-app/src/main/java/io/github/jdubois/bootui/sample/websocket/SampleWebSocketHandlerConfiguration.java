package io.github.jdubois.bootui.sample.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the native echo handler alongside the STOMP endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class SampleWebSocketHandlerConfiguration implements WebSocketConfigurer {

    @Bean
    SampleEchoWebSocketHandler sampleEchoWebSocketHandler() {
        return new SampleEchoWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sampleEchoWebSocketHandler(), "/echo").setAllowedOriginPatterns("http://localhost:*");
    }
}
