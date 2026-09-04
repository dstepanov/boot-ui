package io.github.jdubois.bootui.micronautsample;

import io.micronaut.context.env.Environment;
import io.micronaut.runtime.Micronaut;

/**
 * Reference Micronaut application that runs the BootUI console, the analogue of the Spring and Quarkus
 * sample apps.
 *
 * <p>It declares {@code dev} as its <em>default</em> environment, which is one of BootUI's default enabled
 * environments, so the console is available at {@code http://localhost:8080/bootui} with no further
 * configuration. The environment is declared here rather than in {@code application.yml} because Micronaut
 * resolves active environments before it loads configuration files, so {@code micronaut.environments} only
 * takes effect from the builder, a system property, or the {@code MICRONAUT_ENVIRONMENTS} variable —
 * {@code defaultEnvironments} leaves all three able to override it, so running this sample with
 * {@code -Dmicronaut.environments=prod} correctly leaves the console dark.
 */
public final class BootUiMicronautSampleApplication {

    private BootUiMicronautSampleApplication() {}

    public static void main(String[] args) {
        Micronaut.build(args)
                .mainClass(BootUiMicronautSampleApplication.class)
                .defaultEnvironments(Environment.DEVELOPMENT)
                .start();
    }
}
