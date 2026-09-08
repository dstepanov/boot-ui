/**
 * WebSocket session capture, which fills the WebSockets panel's connection history.
 *
 * <p>The capture listener binds to {@code micronaut-websocket}, a {@code provided} dependency of this
 * adapter, so the package-level {@code @Requires} below keeps it out of an application with no WebSocket
 * endpoints. See the Kafka package for the same rationale in full.
 *
 * <p>The metadata and session providers in this package are plain classes rather than beans: the engine
 * factory builds them behind its own classpath guard, so they are unaffected by this declaration.
 */
@Configuration
@Requires(classes = WebSocketSessionOpenEvent.class)
package io.github.jdubois.bootui.micronaut.websocket;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.event.WebSocketSessionOpenEvent;
