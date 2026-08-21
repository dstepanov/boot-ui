package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import java.util.List;

/**
 * Framework-neutral seam behind the Fault Tolerance panel: one implementation per supported fault tolerance library,
 * reporting that library's configured policies already mapped to {@link FaultTolerancePolicyDto}.
 *
 * <p>Mapping stays in the adapter on purpose. Resilience4j registry entries, Spring Retry
 * {@code @Retryable} metadata and SmallRye Fault Tolerance annotations model timeouts, windows and delays
 * with library-specific types that are lost once a row is flattened to the stable DTO, so the translation
 * happens where those types still exist. The engine {@code FaultToleranceService} owns only the neutral
 * concerns: ordering, bounds, aggregation and the availability wrapping.</p>
 *
 * <p>Implementations must be fully guarded: an inaccessible registry, a partially initialized bean or a
 * library that does not expose a value must yield an omitted setting or a {@code null} counter, never an
 * exception and never a guess. Implementations must not instantiate lazy beans, invoke protected
 * operations or mutate policy state.</p>
 */
public interface FaultTolerancePolicyProvider {

    /**
     * Stable provider id used in the DTO and in {@code FaultToleranceReport.providers()}: {@code resilience4j},
     * {@code spring-retry} or {@code smallrye-fault-tolerance}.
     */
    String providerId();

    /**
     * Whether this library is present and reporting. {@code false} means the library is absent or its
     * registry/bean backing is not available; the engine then omits the provider entirely.
     */
    boolean available();

    /**
     * The mapped, <em>unsorted</em> policies for this library. The engine applies the stable ordering and
     * caps on top. Returns an empty list when {@link #available()} is {@code false}.
     */
    List<FaultTolerancePolicyDto> policies();
}
