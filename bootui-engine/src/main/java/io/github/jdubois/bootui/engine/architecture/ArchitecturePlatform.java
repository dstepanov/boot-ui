package io.github.jdubois.bootui.engine.architecture;

/** Runtime platform whose proxy semantics framework-sensitive architecture rules must apply. */
public enum ArchitecturePlatform {
    SPRING,
    QUARKUS,
    /**
     * Micronaut, whose AOP interception is generated at compile time as a subclass of the intercepted
     * bean. That gives it the same proxyability bar as Spring — a {@code private}, {@code static} or
     * {@code final} method cannot be intercepted — rather than Arc's bytecode-transformation semantics,
     * so it is deliberately <em>not</em> grouped with {@link #QUARKUS} in the visibility rules.
     */
    MICRONAUT
}
