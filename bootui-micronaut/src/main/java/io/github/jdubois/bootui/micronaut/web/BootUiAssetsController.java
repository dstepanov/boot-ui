package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.server.types.files.StreamedFile;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the compiled Vue bundle shipped in {@code bootui-ui} under the console's configured UI mount.
 *
 * <p>Micronaut's own static-resource support is configured through
 * {@code micronaut.router.static-resources.*}, which would make the console a two-step install (every
 * application would have to add that configuration by hand) and could not follow a custom
 * {@code bootui.path}, because a mapping is fixed configuration rather than something a library can derive
 * at runtime. Serving the bundle from a controller instead keeps the console a single dependency and keeps
 * a custom mount working, at the cost of one route.
 *
 * <p>Only the bundle is reachable: the requested path is resolved <em>inside</em>
 * {@value #ASSETS_PREFIX} after being rejected for any traversal or absolute segment, so no other
 * classpath resource can be read through it. Resources are immutable, content-hashed build outputs, so
 * they are served straight from the classpath with no caching layer of BootUI's own; the response headers
 * (including cache-control) are applied by
 * {@link io.github.jdubois.bootui.micronaut.BootUiMicronautSafetyFilter}, which covers the whole console
 * surface.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.UI)
public class BootUiAssetsController {

    /** The classpath location the {@code bootui-ui} module packages the compiled SPA at. */
    static final String ASSETS_PREFIX = "META-INF/resources/bootui/";

    private static final Map<String, MediaType> CONTENT_TYPES = Map.of(
            "js", MediaType.of("text/javascript"),
            "css", MediaType.of("text/css"),
            "svg", MediaType.of("image/svg+xml"),
            "png", MediaType.IMAGE_PNG_TYPE,
            "webp", MediaType.of("image/webp"),
            "json", MediaType.APPLICATION_JSON_TYPE,
            "woff", MediaType.of("font/woff"),
            "woff2", MediaType.of("font/woff2"),
            "ico", MediaType.of("image/x-icon"),
            "map", MediaType.APPLICATION_JSON_TYPE);

    @Get("/{+path}")
    public HttpResponse<?> asset(@PathVariable @Nullable String path) {
        String resource = safeResourceName(path);
        if (resource == null) {
            return HttpResponse.notFound();
        }
        URL url = BootUiAssetsController.class.getClassLoader().getResource(ASSETS_PREFIX + resource);
        if (url == null) {
            return HttpResponse.notFound();
        }
        InputStream stream;
        try {
            stream = url.openStream();
        } catch (java.io.IOException ex) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(new StreamedFile(stream, contentType(resource)));
    }

    /**
     * Normalizes a requested asset path, returning {@code null} for anything that could escape the bundle:
     * an empty path, an absolute path, a Windows-style drive/backslash path, or any {@code ..} segment.
     */
    static String safeResourceName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String candidate = path.replace('\\', '/');
        if (candidate.startsWith("/") || candidate.contains("..") || candidate.contains(":")) {
            return null;
        }
        return candidate;
    }

    private static MediaType contentType(String resource) {
        int dot = resource.lastIndexOf('.');
        if (dot < 0 || dot == resource.length() - 1) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        String extension = resource.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM_TYPE);
    }
}
