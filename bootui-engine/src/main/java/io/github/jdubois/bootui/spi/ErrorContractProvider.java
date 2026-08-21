package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral seam behind the REST API panel's error-contract catalogue: it reports the host
 * application's declared exception handlers without instantiating or invoking any of them.
 *
 * <p>The Spring Boot adapter implements this by reflecting over the application context's
 * {@code @ControllerAdvice}/{@code @RestControllerAdvice} and controller beans (the same seam serves
 * Spring MVC and Spring WebFlux, because {@code @ExceptionHandler} and its companions live in
 * {@code spring-web}). The Quarkus adapter captures Jakarta REST {@code @Provider}
 * {@code ExceptionMapper} implementations and Quarkus REST {@code @ServerExceptionMapper} methods from
 * the build-time Jandex index and replays them through a recorder, because Quarkus exposes no reliable
 * runtime enumeration of resolved exception mappers.</p>
 *
 * <p>Implementations must be side-effect free and must never make the application throw, resolve, or
 * render an error in order to answer.</p>
 */
public interface ErrorContractProvider {

    /**
     * Whether an error-contract backend is currently available. {@code false} means the running stack has
     * no usable declaration source at all, and the engine serves an explicitly unavailable report; it does
     * <em>not</em> mean the application declares no handlers (that is an available, empty catalogue).
     */
    boolean available();

    /**
     * Framework-correct explanation shown when {@link #available()} is {@code false}, or {@code null}.
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * The discovered declarations, unsorted and unpaged, with BootUI's own components already excluded.
     * Returns an empty list when {@link #available()} is {@code false}.
     */
    List<ErrorHandlerDescriptor> handlers();
}
