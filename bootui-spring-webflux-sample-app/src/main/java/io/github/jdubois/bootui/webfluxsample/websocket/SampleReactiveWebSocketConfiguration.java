package io.github.jdubois.bootui.webfluxsample.websocket;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

/**
 * Maps the reactive echo handler so the BootUI WebSockets panel has a real reactive endpoint to report.
 *
 * <p>WebFlux has no {@code @EnableWebSocketMessageBroker} equivalent, so BootUI reads this handler mapping for
 * topology and states {@code frameCaptureSupported=false} with that reason rather than pretending it can see
 * frames on this stack.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SampleReactiveWebSocketConfiguration {

    @Bean
    SampleReactiveEchoHandler sampleReactiveEchoHandler() {
        return new SampleReactiveEchoHandler();
    }

    @Bean
    HandlerMapping sampleWebSocketHandlerMapping(SampleReactiveEchoHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.<String, WebSocketHandler>of("/echo", handler));
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }
}
