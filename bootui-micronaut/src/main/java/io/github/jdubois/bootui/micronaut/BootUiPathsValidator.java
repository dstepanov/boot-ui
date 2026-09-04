package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.BootUiException;
import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;

/**
 * Validates the configured BootUI mounts at startup, failing fast rather than serving a half-moved console.
 *
 * <p>Two checks run, both eager (this is a {@link Context}-scoped bean, so it is created during startup
 * rather than on first request):
 *
 * <ul>
 *   <li>Both {@code bootui.path} and {@code bootui.api-path} are normalized, so an invalid value (an empty
 *       mount, a mount that is not absolute, or an API mount nested under the reserved internal path) is
 *       reported at startup with the normalizer's own message instead of producing a console whose routes
 *       are silently wrong.</li>
 *   <li>{@code bootui.path} may not be customized without also setting {@code bootui.api-path}. On Spring
 *       and Quarkus the API mount <em>derives</em> from the UI mount, but Micronaut resolves a controller's
 *       path from a property placeholder whose default cannot reference another property, so a moved UI
 *       would leave the API behind at {@code /bootui/api} — a console that loads and then fails every
 *       call. Failing fast with an actionable message is the honest alternative to that.</li>
 * </ul>
 */
@RequiresBootUi
@Context
public class BootUiPathsValidator {

    private final Environment environment;

    public BootUiPathsValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        MicronautBootUiPaths.validate(environment);

        String configuredUiPath = environment
                .getProperty(MicronautBootUiPaths.PATH_KEY, String.class)
                .orElse(null);
        boolean apiPathConfigured = environment.containsProperty(MicronautBootUiPaths.API_PATH_KEY);
        if (configuredUiPath == null || apiPathConfigured) {
            return;
        }
        if (!BootUiPathNormalizer.DEFAULT_PATH.equals(BootUiPathNormalizer.normalize(configuredUiPath))) {
            throw new BootUiException("BootUI is configured with " + MicronautBootUiPaths.PATH_KEY + "="
                    + configuredUiPath + " but no " + MicronautBootUiPaths.API_PATH_KEY
                    + ". On Micronaut the API mount does not derive from the UI mount, so set "
                    + MicronautBootUiPaths.API_PATH_KEY + " explicitly (for example "
                    + BootUiPathNormalizer.normalize(configuredUiPath) + "/api).");
        }
    }
}
