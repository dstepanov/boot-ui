package io.github.jdubois.bootui.sample.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Declares the sample application's STOMP endpoint over the in-memory simple broker, so the BootUI WebSockets
 * panel has a real endpoint, real subscriptions, and real frame traffic to show without any external broker.
 *
 * <p>BootUI installs its own frame-capture decorator and channel interceptors through the same public
 * {@link WebSocketMessageBrokerConfigurer} contract, at the lowest precedence, so this configuration stays a
 * plain application configuration and needs to know nothing about BootUI.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class SampleStompConfiguration implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
