package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * One declared error-handling method, described in framework-neutral terms.
 *
 * <p>Adapters report <em>raw declaration facts</em> only. Classification (body category, status source,
 * precedence ranking, ordering, paging and cross-linking) belongs to the engine's
 * {@code ErrorContractService} so Spring servlet, Spring WebFlux and Quarkus produce the same
 * catalogue shape. Nothing here may be obtained by instantiating or invoking application code: every
 * field comes from an annotation, a signature, or a build-time index.</p>
 *
 * @param source where the declaration came from: {@link #SPRING_CONTROLLER_ADVICE},
 *     {@link #SPRING_CONTROLLER}, {@link #JAKARTA_REST_EXCEPTION_MAPPER} or
 *     {@link #QUARKUS_SERVER_EXCEPTION_MAPPER}
 * @param componentClassName fully-qualified name of the declaring class
 * @param methodName the declaring method name
 * @param exceptionTypeNames fully-qualified names of the exception types the method declares it handles;
 *     one catalogue entry is produced per type
 * @param scope {@link #SCOPE_GLOBAL}, {@link #SCOPE_SCOPED}, {@link #SCOPE_CONTROLLER} or
 *     {@link #SCOPE_UNKNOWN}
 * @param scopeTarget the narrowing evidence for a scoped or controller-local declaration (for example the
 *     controller's fully-qualified name, or an advice selector summary), or {@code null}
 * @param declaredOrder the statically declared ordering value ({@code @Order}, {@code @Priority}), or
 *     {@code null} when the declaration carries none
 * @param dynamicPrecedence {@code true} when the component decides its own ordering at runtime (it
 *     implements {@code Ordered}), so the declared evidence cannot rank it; the engine reports such an
 *     entry as unresolved instead of guessing
 * @param declaredStatus the statically declared HTTP status (for example {@code "404"}), or {@code null}
 * @param dynamicStatus {@code true} when the status is built at runtime (a {@code ResponseEntity},
 *     {@code Response} or {@code RestResponse} return type) and therefore cannot be resolved statically
 * @param returnTypeName erased fully-qualified return type of the declaring method, or {@code null}
 * @param bodyTypeName fully-qualified type of the response body where the signature exposes it (for
 *     example the {@code X} of {@code ResponseEntity<X>}), or {@code null} when it is not statically known
 * @param produces declared response media types; empty when the framework leaves them to content
 *     negotiation
 */
public record ErrorHandlerDescriptor(
        String source,
        String componentClassName,
        String methodName,
        List<String> exceptionTypeNames,
        String scope,
        String scopeTarget,
        Integer declaredOrder,
        boolean dynamicPrecedence,
        String declaredStatus,
        boolean dynamicStatus,
        String returnTypeName,
        String bodyTypeName,
        List<String> produces) {

    /** A Spring {@code @ControllerAdvice}/{@code @RestControllerAdvice} {@code @ExceptionHandler} method. */
    public static final String SPRING_CONTROLLER_ADVICE = "SPRING_CONTROLLER_ADVICE";

    /** A Spring {@code @ExceptionHandler} method declared on the controller it serves. */
    public static final String SPRING_CONTROLLER = "SPRING_CONTROLLER";

    /** A Jakarta REST {@code @Provider} implementation of {@code ExceptionMapper<X>}. */
    public static final String JAKARTA_REST_EXCEPTION_MAPPER = "JAKARTA_REST_EXCEPTION_MAPPER";

    /** A Quarkus REST {@code @ServerExceptionMapper} method. */
    public static final String QUARKUS_SERVER_EXCEPTION_MAPPER = "QUARKUS_SERVER_EXCEPTION_MAPPER";

    /** A Micronaut {@code @Error} method, declared on a controller or globally. */
    public static final String MICRONAUT_ERROR_HANDLER = "MICRONAUT_ERROR_HANDLER";

    /** A Micronaut {@code ExceptionHandler} bean, which is always application-wide. */
    public static final String MICRONAUT_EXCEPTION_HANDLER = "MICRONAUT_EXCEPTION_HANDLER";

    /** Applies application-wide. */
    public static final String SCOPE_GLOBAL = "GLOBAL";

    /** Applies application-wide but narrowed by declared selectors. */
    public static final String SCOPE_SCOPED = "SCOPED";

    /** Applies only to the controller or resource that declares it. */
    public static final String SCOPE_CONTROLLER = "CONTROLLER";

    /** Applicability could not be resolved from declarations alone. */
    public static final String SCOPE_UNKNOWN = "UNKNOWN";

    public ErrorHandlerDescriptor {
        exceptionTypeNames = exceptionTypeNames == null ? List.of() : List.copyOf(exceptionTypeNames);
        produces = produces == null ? List.of() : List.copyOf(produces);
    }
}
