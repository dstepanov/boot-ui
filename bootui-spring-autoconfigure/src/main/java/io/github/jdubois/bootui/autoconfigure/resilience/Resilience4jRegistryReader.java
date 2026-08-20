package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * One Resilience4j module's read + capture binding.
 *
 * <p>Resilience4j ships each protection as its own Maven module, so an application may have
 * {@code resilience4j-retry} without {@code resilience4j-circuitbreaker}. Each reader therefore lives in
 * its own class file and is instantiated by {@link Resilience4jPolicyProvider} only after that module's
 * registry type is confirmed present — the classloading-safety pattern the repository uses for every
 * optional dependency. No always-loaded class imports a Resilience4j type.</p>
 */
interface Resilience4jRegistryReader {

    /** Maps this module's registry entries to neutral policies; empty when no registry bean exists. */
    List<ResiliencePolicyDto> policies();

    /**
     * Registers BootUI's metadata-only event consumers on this module's native event publishers.
     * Resilience4j's publishers are additive, so this composes with the application's own consumers and
     * never replaces them. Implementations must be idempotent and fail-open.
     */
    void registerCapture(ResilienceEventRecorder recorder);

    /** Whether a registry bean for this module is actually present. */
    boolean available();

    /**
     * Resolves the single registry bean behind {@code provider}, or {@code null} when none (or more than one,
     * which Resilience4j's own Spring integration never produces) is available.
     *
     * <p>The provider is deliberately untyped: {@link Resilience4jPolicyProvider} obtains it reflectively so
     * that no always-loaded class names a Resilience4j type.</p>
     */
    static Object uniqueBean(ObjectProvider<?> provider) {
        try {
            return provider.getIfAvailable();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
