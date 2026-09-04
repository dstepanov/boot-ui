package io.github.jdubois.bootui.micronaut.mappings;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.micronaut.http.MediaType;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import java.util.List;
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
 * available (the two things the Spring adapter's {@code BootUiSelfDataFilter} inspects), using the shared
 * engine {@link InternalPackageMatcher}. The engine {@code MappingsService} then sorts, queries and pages
 * what is left.
 */
public final class MicronautMappingProvider implements MappingProvider {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

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
            // Micronaut registers a route per matched variant (and a HEAD counterpart for every GET), so the
            // same method/pattern/handler triple can appear several times; the panel inventories endpoints,
            // not route objects, so identical rows are collapsed while their order is preserved.
            return router.uriRoutes()
                    .filter(route -> !isInternal(route))
                    .map(MicronautMappingProvider::toDto)
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            // A router that cannot be enumerated must render an empty panel, never fail the request.
            return List.of();
        }
    }

    private static boolean isInternal(UriRouteInfo<?, ?> route) {
        Class<?> declaringType = route.getDeclaringType();
        return declaringType != null && INTERNAL_PACKAGES.matchesName(declaringType.getName());
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
