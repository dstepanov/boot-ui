package io.github.jdubois.bootui.sample.resilience;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quarkus analogue of the Spring sample's {@code SampleResilienceController}. Exercises the sample's fault
 * tolerance annotations on demand so the BootUI Resilience panel shows a circuit breaker that has actually
 * moved state. Nothing runs on startup or on page load.
 */
@Path("/api/sample/resilience")
public class SampleResilienceResource {

    @Inject
    SampleResilienceService resilience;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> exerciseResilience() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservation", resilience.reserve("BOOTUI-1"));
        int failures = 0;
        int rejections = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                resilience.charge("ORDER-" + attempt);
            } catch (IllegalStateException failure) {
                failures++;
            } catch (RuntimeException rejected) {
                // CircuitBreakerOpenException once the breaker trips.
                rejections++;
            }
        }
        result.put("circuitBreakerFailures", failures);
        result.put("circuitBreakerRejections", rejections);
        return result;
    }
}
