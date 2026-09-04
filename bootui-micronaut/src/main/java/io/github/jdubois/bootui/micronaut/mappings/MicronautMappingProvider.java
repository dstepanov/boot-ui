package io.github.jdubois.bootui.micronaut.mappings;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.micronaut.MicronautBeanTypes;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Head;
import io.micronaut.inject.MethodExecutionHandle;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Micronaut {@link MappingProvider} backed by the live {@link Router}.
 *
 * <p>The Micronaut analogue of the Spring adapter's Actuator-backed {@code SpringMappingProvider} and of
 * the Quarkus adapter's build-time-captured {@code QuarkusMappingProvider}. Micronaut builds its route
 * table at compile time but exposes it at runtime through {@link Router#uriRoutes()}, so this provider
 * reads the real, live routes — including routes contributed by other libraries — with no build-time
 * capture step.
 *
 * <p>BootUI's own console routes are filtered out here, where both the pattern and the declaring class are
 * available (the two things the Spring adapter's {@code BootUiSelfDataFilter} inspects), through
 * {@link MicronautBeanTypes#isBootUiType(Class)}. The engine {@code MappingsService} then sorts, queries and
 * pages what is left.
 *
 * <h2>The implicit HEAD counterpart</h2>
 *
 * <p>Micronaut registers a second, generated HEAD route for every route that answers GET — {@code @Get}
 * does it unless the method sets {@code headRoute = false}, and the management endpoints' {@code @Read}
 * does it unconditionally. Those routes are real (a {@code HEAD /catalog} is genuinely served) but they are
 * not <em>declarations</em>: nobody wrote them, they carry the annotation metadata of the GET method they
 * were cloned from, and listing them nearly doubles the panel — 24 rows where the sample application and
 * the management endpoints it adds declare 13. The Spring panel, reading Actuator's mappings, lists the
 * declared method only; this provider matches that by dropping the generated counterpart.
 *
 * <p>Micronaut publishes no flag for it, so the counterpart is identified structurally, exactly as the two
 * route builders create it: a HEAD route that does not itself declare {@code @Head} and whose path,
 * declaring class and target method are shared with a GET route. An explicitly declared {@code @Head} route
 * therefore survives — including one declared on a method that also answers GET, which the annotation check
 * catches before the pairing check can.
 *
 * <p>What remains of {@code distinct()} is the other source of duplicate rows: Micronaut registers a route
 * per matched variant, so the same method/pattern/handler triple can still appear more than once. The panel
 * inventories endpoints, not route objects, so those identical rows are collapsed while their order is
 * preserved.
 */
public final class MicronautMappingProvider implements MappingProvider {

    private final Router router;

    public MicronautMappingProvider(Router router) {
        this.router = router;
    }

    @Override
    public boolean available() {
        return router != null;
    }

    @Override
    public List<MappingDto> mappings() {
        if (router == null) {
            return List.of();
        }
        try {
            List<UriRouteInfo<?, ?>> routes =
                    router.uriRoutes().filter(route -> !isInternal(route)).toList();
            Set<String> declaredGetTargets = new HashSet<>();
            for (UriRouteInfo<?, ?> route : routes) {
                if (HttpMethod.GET.name().equals(route.getHttpMethodName())) {
                    declaredGetTargets.add(targetKey(route));
                }
            }
            return routes.stream()
                    .filter(route -> !isGeneratedHeadCounterpart(route, declaredGetTargets))
                    .map(MicronautMappingProvider::toDto)
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            // A router that cannot be enumerated must render an empty panel, never fail the request.
            return List.of();
        }
    }

    private static boolean isInternal(UriRouteInfo<?, ?> route) {
        return MicronautBeanTypes.isBootUiType(route.getDeclaringType());
    }

    /**
     * Whether this is the HEAD route Micronaut generated alongside a GET route rather than one the
     * application declared. A route that carries {@code @Head} is a declaration whatever else it shares, so
     * it is answered first; otherwise the route is generated exactly when the GET it was cloned from is
     * still in the table under the same {@link #targetKey}.
     */
    private static boolean isGeneratedHeadCounterpart(UriRouteInfo<?, ?> route, Set<String> declaredGetTargets) {
        if (!HttpMethod.HEAD.name().equals(route.getHttpMethodName())) {
            return false;
        }
        if (route.getAnnotationMetadata().hasDeclaredAnnotation(Head.class)) {
            return false;
        }
        return declaredGetTargets.contains(targetKey(route));
    }

    /**
     * Identity of the handler a route reaches: its path plus the class and method it invokes. The generated
     * HEAD route shares all three with its GET original, and nothing else in the table does.
     */
    private static String targetKey(UriRouteInfo<?, ?> route) {
        Class<?> declaringType = route.getDeclaringType();
        MethodExecutionHandle<?, ?> target = route.getTargetMethod();
        return (declaringType == null ? "?" : declaringType.getName())
                + '#'
                + (target == null ? "?" : target.getMethodName())
                + ' '
                + route.getUriMatchTemplate().toPathString();
    }

    static MappingDto toDto(UriRouteInfo<?, ?> route) {
        return new MappingDto(
                route.getHttpMethodName(),
                route.getUriMatchTemplate().toPathString(),
                handler(route),
                mediaTypes(route.getProduces()),
                mediaTypes(route.getConsumes()));
    }

    private static String handler(UriRouteInfo<?, ?> route) {
        Class<?> declaringType = route.getDeclaringType();
        return declaringType == null ? route.toString() : declaringType.getName();
    }

    private static String mediaTypes(List<MediaType> mediaTypes) {
        if (mediaTypes == null || mediaTypes.isEmpty()) {
            return null;
        }
        return mediaTypes.stream().map(MediaType::toString).collect(Collectors.joining(", "));
    }
}
