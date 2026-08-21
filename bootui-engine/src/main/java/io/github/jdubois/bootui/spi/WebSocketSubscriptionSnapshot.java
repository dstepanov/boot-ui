package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral snapshot of one destination subscription.
 *
 * <p>{@code rawId} and {@code rawSessionId} are the framework's own identifiers; the engine hashes both
 * before serialization. Subscribing principals and arbitrary subscription headers are never carried.</p>
 *
 * @param rawId the framework subscription identifier, hashed by the engine
 * @param rawSessionId the framework session identifier, hashed by the engine
 * @param endpointId the endpoint the subscription was made through
 * @param destination the subscribed destination
 * @param subscribedAt epoch milliseconds when the subscription was observed
 */
public record WebSocketSubscriptionSnapshot(
        String rawId, String rawSessionId, String endpointId, String destination, long subscribedAt) {}
