package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.spi.ServerPortSupplier;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Micronaut implementation of the framework-neutral {@link ServerPortSupplier} consumed by the engine
 * {@code HttpProbeService} behind the HTTP Probe panel, and by the Pentesting advisor's loopback probes.
 *
 * <p>The engine always probes {@code http://localhost:<port><path>}, so the only per-framework detail is
 * <em>which</em> port the application is actually listening on right now. This is the Micronaut analogue
 * of the Spring adapter's {@code local.server.port} lambda and of {@code QuarkusServerPortSupplier}: it
 * asks the running {@link EmbeddedServer} for its <em>bound</em> port, which is the only correct answer
 * when {@code micronaut.server.port} is {@code -1} (Micronaut's "random port" value, used by
 * {@code @MicronautTest}) or {@code 0}. The server is resolved lazily through a {@link Provider} so this
 * bean never participates in the server's own startup cycle, and the port is read live on every probe.
 *
 * <p>It fails soft to the configured {@code micronaut.server.port}, and then to Micronaut's default
 * {@code 8080}, when the embedded server cannot be resolved or reports a non-positive port — a probe that
 * cannot resolve a port must degrade to a clear failure from the engine, never to an exception here.</p>
 */
@RequiresBootUi
@Singleton
public class MicronautServerPortSupplier implements ServerPortSupplier {

    static final String HTTP_PORT_KEY = "micronaut.server.port";
    static final int DEFAULT_HTTP_PORT = 8080;

    private final Provider<EmbeddedServer> embeddedServer;
    private final Environment environment;

    public MicronautServerPortSupplier(Provider<EmbeddedServer> embeddedServer, Environment environment) {
        this.embeddedServer = embeddedServer;
        this.environment = environment;
    }

    @Override
    public int localServerPort() {
        Integer boundPort = boundPort();
        if (boundPort != null && boundPort > 0) {
            return boundPort;
        }
        int configured = environment.getProperty(HTTP_PORT_KEY, Integer.class).orElse(DEFAULT_HTTP_PORT);
        return configured > 0 ? configured : DEFAULT_HTTP_PORT;
    }

    @Nullable
    private Integer boundPort() {
        try {
            EmbeddedServer server = embeddedServer.get();
            return server == null ? null : server.getPort();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
