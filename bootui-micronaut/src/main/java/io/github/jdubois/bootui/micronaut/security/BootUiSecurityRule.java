package io.github.jdubois.bootui.micronaut.security;

import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Permits unauthenticated access to the BootUI console when the application uses {@code micronaut-security}.
 *
 * <p>This is the Micronaut analogue of the Spring adapter's {@code BootUiSpringSecurityAutoConfiguration},
 * which registers a highest-precedence filter chain permitting the same surface, and it is here for the same
 * reason: with security enabled, Micronaut denies every request that no rule allows, so without this the
 * console would be unreachable in any secured application — the developer would be locked out of their own
 * diagnostics exactly when they most need them.
 *
 * <p>This does <em>not</em> widen BootUI's exposure. The console keeps its own, stricter guard:
 * {@code BootUiMicronautSafetyFilter} still rejects non-loopback callers (unless
 * {@code bootui.allow-non-localhost=true}), still enforces the Host allow-list and cross-site-write
 * protection, and {@code BootUiMicronautAuthenticationFilter} still requires the BootUI bearer token from
 * any source that is not trusted. As on Spring, the decision is logged once at startup so it is never
 * silent.
 *
 * <p>The rule answers {@link SecurityRuleResult#UNKNOWN} for every path outside the console, which leaves
 * the application's own rules entirely in charge of its own endpoints. "The console" is the configured UI
 * and API mounts and nothing else — the shared
 * {@link MicronautBootUiPaths#isBootUiRequest(io.micronaut.core.value.PropertyResolver, String) matcher}
 * every guard and capture point uses. That matters most here: permitting a mount BootUI does not actually
 * occupy would hand anonymous access to whatever the application itself serves there.
 */
@RequiresBootUi
@Requires(classes = SecurityRule.class)
@Singleton
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BootUiSecurityRule implements SecurityRule<HttpRequest<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(BootUiSecurityRule.class);

    private final Environment environment;

    public BootUiSecurityRule(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void warnOnce() {
        LOG.warn(
                "BootUI detected Micronaut Security and is permitting unauthenticated access to {} and {}; "
                        + "BootUI's localhost-only filter still rejects non-loopback callers unless "
                        + "bootui.allow-non-localhost=true is set.",
                MicronautBootUiPaths.safeUiPath(environment),
                MicronautBootUiPaths.safeApiPath(environment));
    }

    @Override
    public Publisher<SecurityRuleResult> check(HttpRequest<?> request, Authentication authentication) {
        if (request == null) {
            return Mono.just(SecurityRuleResult.UNKNOWN);
        }
        boolean console = MicronautBootUiPaths.isBootUiRequest(environment, request.getPath());
        return Mono.just(console ? SecurityRuleResult.ALLOWED : SecurityRuleResult.UNKNOWN);
    }
}
