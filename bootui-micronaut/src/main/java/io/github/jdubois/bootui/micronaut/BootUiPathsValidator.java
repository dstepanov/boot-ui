package io.github.jdubois.bootui.micronaut;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;

/**
 * Validates the configured BootUI mounts at startup, failing fast rather than serving a half-moved console.
 *
 * <p>Both {@code bootui.path} and the {@code bootui.api-path} that {@link BootUiApiPathConfigurer} derived
 * from it are normalized, so an invalid value — an empty mount, a mount that is not absolute, a mount with
 * a traversal or an encoded separator in it — is reported at startup with the normalizer's own message
 * instead of producing a console whose routes are silently wrong. This is a {@link Context}-scoped bean, so
 * the check runs during startup rather than on the first request.
 *
 * <p>It is also the place an invalid {@code bootui.path} surfaces at all: {@link BootUiApiPathConfigurer}
 * runs before the bean container exists and deliberately stays silent on a bad value rather than failing
 * the context with a message that points at property resolution instead of at the property.
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
    }
}
