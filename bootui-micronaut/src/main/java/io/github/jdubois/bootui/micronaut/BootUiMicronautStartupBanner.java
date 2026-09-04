package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs where the console is once the server is listening — the Micronaut analogue of the Quarkus adapter's
 * {@code BootUiQuarkusStartupBanner}.
 *
 * <p>It listens for {@link ServerStartupEvent} rather than a context-refresh event so the URL carries the
 * port the server actually bound, which is the only correct one when the port is random. The banner can be
 * turned off with {@code bootui.show-banner=false}.
 *
 * <p>When remote access is configured ({@code bootui.allow-non-localhost}, {@code bootui.trusted-proxies},
 * or {@code bootui.trust-container-gateway}) and BootUI generated the API token itself, the token is logged
 * once here — otherwise a non-local caller would have no way to learn it. A token the operator configured is
 * never logged.
 */
@RequiresBootUi
@Singleton
public class BootUiMicronautStartupBanner implements ApplicationEventListener<ServerStartupEvent> {

    static final String SHOW_BANNER_KEY = "bootui.show-banner";

    private static final Logger LOG = LoggerFactory.getLogger(BootUiMicronautStartupBanner.class);

    private final Environment environment;
    private final ApiTokenAuthenticator authenticator;

    public BootUiMicronautStartupBanner(Environment environment, ApiTokenAuthenticator authenticator) {
        this.environment = environment;
        this.authenticator = authenticator;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        if (showBanner()) {
            LOG.info("BootUI is available at {}", startupUrl(event.getSource().getPort()));
        }
        if (authenticator.generated() && remoteAccessConfigured()) {
            LOG.info("BootUI bearer token for non-local API access: {}", authenticator.token());
        }
    }

    private boolean showBanner() {
        return BootUiBooleans.value(environment, SHOW_BANNER_KEY, true, LOG);
    }

    private String startupUrl(int port) {
        return "http://localhost:" + port + MicronautBootUiPaths.applicationUiPath(environment);
    }

    private boolean remoteAccessConfigured() {
        return ApiTokenAuthenticator.remoteAccessConfigured(
                BootUiBooleans.value(environment, BootUiMicronautSafetyFilter.ALLOW_NON_LOCALHOST_KEY, false, LOG),
                environment
                        .getProperty(BootUiMicronautSafetyFilter.TRUSTED_PROXIES_KEY, String.class)
                        .filter(value -> !value.isBlank())
                        .isPresent(),
                !"OFF"
                        .equalsIgnoreCase(environment
                                .getProperty(BootUiMicronautSafetyFilter.TRUST_CONTAINER_GATEWAY_KEY, String.class)
                                .orElse("OFF")));
    }
}
