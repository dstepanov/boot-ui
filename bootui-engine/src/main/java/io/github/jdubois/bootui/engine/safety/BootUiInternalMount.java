package io.github.jdubois.bootui.engine.safety;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;

/**
 * The reserved internal {@code /bootui} classpath mount, as a framework-neutral request-path predicate.
 *
 * <p>{@code bootui-ui} packages the compiled Vue bundle at {@code META-INF/resources/bootui/}, a
 * location every supported runtime serves from its own built-in static-resource handling, independently
 * of anything BootUI registers. {@code bootui.path} moves the console's routes but never that classpath
 * location, so {@code /bootui} stays reserved and adapters need one agreed answer to "is this request
 * addressing the packaged mount?".</p>
 *
 * <p>Deliberately free of any framework type: the Spring MVC binding is a servlet {@code Filter} and the
 * Spring WebFlux binding is a {@code WebFilter}, and a reactive-only application has no servlet API on
 * its classpath at all, so neither binding may reach into the other for this decision.</p>
 *
 * <p>Callers must pass a <strong>decoded</strong> application-relative path — the same form the
 * framework's own handler mapping matches on. Comparing raw request URIs would let {@code /%62ootui/...}
 * slip past a guard while still resolving to the bundle.</p>
 */
public final class BootUiInternalMount {

    private BootUiInternalMount() {}

    /**
     * Returns {@code true} for the reserved mount itself and everything below it.
     *
     * @param path a decoded, application-relative request path
     */
    public static boolean covers(String path) {
        return isUnder(path, BootUiPathNormalizer.DEFAULT_PATH);
    }

    /**
     * Returns {@code true} when {@code path} addresses {@code mount} itself or anything below it.
     *
     * <p>Adapters use this for the additional prefixes under which their framework may expose the same
     * packaged bundle &mdash; a Spring {@code spring.mvc.servlet.path} or static path pattern, for
     * example &mdash; without teaching the engine about framework-specific configuration.</p>
     *
     * @param path a decoded, application-relative request path
     * @param mount an absolute mount path with no trailing slash
     */
    public static boolean isUnder(String path, String mount) {
        return path != null && mount != null && (path.equals(mount) || path.startsWith(mount + "/"));
    }
}
