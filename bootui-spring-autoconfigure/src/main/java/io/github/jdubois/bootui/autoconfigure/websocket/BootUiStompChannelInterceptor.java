package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * Records STOMP-level activity metadata on the client inbound and outbound channels.
 *
 * <p>The raw transport decorator sees WebSocket frames but not STOMP semantics, so it records session
 * lifecycle and control frames only and leaves every data frame to this interceptor, which records it once
 * with its destination and command. That split means an inbound application message is counted exactly
 * once, and always with the routing metadata a developer actually needs.</p>
 *
 * <p>Destinations are structural routing metadata, exactly like an HTTP path, and are the primary thing a
 * developer needs to debug a STOMP application. The message body is never read: only the payload's
 * already-known length is recorded, and nothing else from the payload is retained. Native headers,
 * principals, and session attributes are ignored.</p>
 */
public class BootUiStompChannelInterceptor implements ChannelInterceptor {

    private final WebSocketActivityRecorder recorder;
    private final BootUiWebSocketSessionRegistry registry;
    private final WebSocketActivityRecorder.Direction direction;

    public BootUiStompChannelInterceptor(
            WebSocketActivityRecorder recorder,
            BootUiWebSocketSessionRegistry registry,
            WebSocketActivityRecorder.Direction direction) {
        this.recorder = recorder;
        this.registry = registry;
        this.direction = direction;
    }

    private final ThreadLocal<Long> startedAt = new ThreadLocal<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        try {
            startedAt.set(System.nanoTime());
        } catch (RuntimeException ignored) {
            // Observation must never break message delivery.
        }
        return message;
    }

    /**
     * Records the frame once delivery has completed, so the entry carries how long the application's own
     * dispatch took and whether it failed. Spring invokes this on the same thread that ran {@code preSend}.
     *
     * <p>Only the exception's type is recorded. An exception message can quote the payload it failed on, and
     * this panel never lets message content reach BootUI's memory or JSON.</p>
     */
    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        Long started = startedAt.get();
        startedAt.remove();
        try {
            Long durationMillis = started == null ? null : Math.max(0, (System.nanoTime() - started) / 1_000_000);
            record(
                    message,
                    durationMillis,
                    ex == null && sent,
                    ex == null ? null : ex.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observation must never break message delivery.
        }
    }

    private void record(Message<?> message, Long durationMillis, boolean success, String errorCategory) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        SimpMessageType messageType = accessor.getMessageType();
        if (messageType == null) {
            return;
        }
        String sessionId = accessor.getSessionId();
        String destination = accessor.getDestination();
        String endpointId = registry.endpointIdFor(sessionId);
        WebSocketActivityRecorder.FrameType frameType =
                switch (messageType) {
                    case CONNECT, CONNECT_ACK -> WebSocketActivityRecorder.FrameType.CONNECT;
                    case SUBSCRIBE -> WebSocketActivityRecorder.FrameType.SUBSCRIBE;
                    case UNSUBSCRIBE -> WebSocketActivityRecorder.FrameType.UNSUBSCRIBE;
                    case DISCONNECT -> WebSocketActivityRecorder.FrameType.CLOSE;
                    case HEARTBEAT -> WebSocketActivityRecorder.FrameType.PING;
                    default -> WebSocketActivityRecorder.FrameType.TEXT;
                };
        if (messageType == SimpMessageType.SUBSCRIBE) {
            registry.subscribed(sessionId, accessor.getSubscriptionId(), endpointId, destination);
        } else if (messageType == SimpMessageType.UNSUBSCRIBE) {
            registry.unsubscribed(sessionId, accessor.getSubscriptionId());
        }
        recorder.recordFrame(
                endpointId,
                sessionId,
                direction,
                frameType,
                destination,
                payloadBytes(message),
                durationMillis,
                success,
                errorCategory);
    }

    /**
     * Returns the payload size in bytes without reading the payload's content. Only the array length is
     * touched; the bytes themselves are never inspected, copied, or retained. A payload that has already
     * been converted to text reports no size rather than a character count, because the contract documents
     * bytes and encoding a string to measure it would mean copying the message body.
     */
    private Long payloadBytes(Message<?> message) {
        Object payload = message.getPayload();
        if (payload instanceof byte[] bytes) {
            return (long) bytes.length;
        }
        return null;
    }
}
