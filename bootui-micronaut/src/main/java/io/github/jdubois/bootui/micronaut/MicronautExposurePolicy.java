package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micronaut implementation of the framework-neutral {@link ExposurePolicy}, resolved from the Micronaut
 * {@link Environment}.
 *
 * <p>This is the Micronaut analogue of the Spring adapter's {@code BootUiExposure} and of the Quarkus
 * adapter's {@code QuarkusExposurePolicy}. It reads {@code bootui.expose-values} and
 * {@code bootui.mask-secrets} live (per call), so the engine masks consistently on every stack. It
 * <em>fails closed</em>: a missing, blank, or invalid value resolves to {@link ValueExposure#MASKED} /
 * {@code maskSecrets=true} so a typo can never disclose a secret.</p>
 */
@RequiresBootUi
@Singleton
public class MicronautExposurePolicy implements ExposurePolicy {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautExposurePolicy.class);

    static final String EXPOSE_VALUES_KEY = "bootui.expose-values";
    static final String MASK_SECRETS_KEY = "bootui.mask-secrets";

    private final Environment environment;

    public MicronautExposurePolicy(Environment environment) {
        this.environment = environment;
    }

    @Override
    public ValueExposure valueExposure() {
        String raw = environment.getProperty(EXPOSE_VALUES_KEY, String.class).orElse(null);
        if (raw == null || raw.isBlank()) {
            return ValueExposure.MASKED;
        }
        try {
            return ValueExposure.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warn("Ignoring invalid BootUI property '{}={}'; falling back to MASKED.", EXPOSE_VALUES_KEY, raw);
            return ValueExposure.MASKED;
        }
    }

    @Override
    public boolean maskSecrets() {
        return BootUiBooleans.value(environment, MASK_SECRETS_KEY, true, LOG);
    }
}
