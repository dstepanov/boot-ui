package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Boots the adapter with Flyway and Liquibase <em>configured</em> — a migration for the {@code default}
 * datasource {@link TestDataSourceFactory} publishes — and walks the two panels end to end over HTTP.
 *
 * <p>This is the state the shared conformance suite's confirmation-gate assertion expects of an available
 * panel: the manifest advertises it, its report says the integration is present, and a mutating action sent
 * without {@code confirm=true} is refused with the engine's canonical 400 rather than a 404 for an instance
 * that was never configured. {@code MicronautPanelAvailabilityTest} pins the other half — that the very same
 * classpath without this configuration keeps both panels dark.
 */
@MicronautTest
@Property(name = TestDataSourceFactory.PROPERTY, value = "true")
@Property(name = "flyway.datasources.default.enabled", value = "true")
@Property(name = "liquibase.datasources.default.change-log", value = "classpath:db/bootui-test-changelog.xml")
class MicronautMigrationPanelsTest {

    private static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it mutates the application database.";

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void advertisesBothPanelsOnceAMigrationIsConfiguredAgainstADatasource() {
        Map<?, ?> manifest = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/panels"), Map.class);

        for (String id : List.of("flyway", "liquibase")) {
            Map<?, ?> panel = panel(manifest, id);
            assertThat(panel.get("available")).as("%s available", id).isEqualTo(true);
            assertThat(panel.get("unavailableReason")).as("%s reason", id).isNull();
        }
    }

    @Test
    void reportsTheConfiguredDatasourceAsPresent() {
        Map<?, ?> flyway = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/flyway/migrations"), Map.class);
        Map<?, ?> liquibase =
                client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/liquibase/changesets"), Map.class);

        assertThat(flyway.get("flywayPresent")).isEqualTo(true);
        assertThat(names(flyway)).containsExactly("default");
        assertThat(liquibase.get("liquibasePresent")).isEqualTo(true);
        assertThat(names(liquibase)).containsExactly("default");
    }

    /** The engine's confirmation gate fires — the panel is live, so the answer is 400, never 404. */
    @Test
    void refusesUnconfirmedActionsWithTheCanonicalBlockedBody() {
        for (String path : List.of("/bootui/api/flyway/migrate", "/bootui/api/liquibase/update")) {
            HttpClientResponseException refused = catchThrowableOfType(
                    HttpClientResponseException.class,
                    () -> client.toBlocking().exchange(HttpRequest.POST(path, "{}"), Map.class));

            assertThat(refused).as("POST %s without confirm", path).isNotNull();
            assertThat((Object) refused.getStatus()).as("POST %s status", path).isEqualTo(HttpStatus.BAD_REQUEST);
            Map<?, ?> body = refused.getResponse().getBody(Map.class).orElseThrow();
            assertThat(body.get("status")).as("POST %s body.status", path).isEqualTo("blocked");
            assertThat(body.get("message")).as("POST %s body.message", path).isEqualTo(CONFIRMATION_REQUIRED);
        }
    }

    private static Map<?, ?> panel(Map<?, ?> manifest, String id) {
        return ((List<?>) manifest.get("panels"))
                .stream()
                        .map(Map.class::cast)
                        .filter(panel -> id.equals(panel.get("id")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No panel " + id + " in the manifest"));
    }

    private static List<Object> names(Map<?, ?> report) {
        return ((List<?>) report.get("databases"))
                .stream()
                        .map(Map.class::cast)
                        .map(database -> database.get("name"))
                        .toList();
    }
}
