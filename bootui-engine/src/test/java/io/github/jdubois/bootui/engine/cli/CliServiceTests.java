package io.github.jdubois.bootui.engine.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CliServerStatus;
import io.github.jdubois.bootui.core.dto.CliToolInfo;
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CliServiceTests {

    private final McpTool overview = new McpTool(
            "get_overview", "Read the overview.", McpToolSchema.NONE, "overview", false, args -> Map.of("ok", true));
    private final McpTool architecture = new McpTool(
            "architecture_scan",
            "Run the architecture advisor.",
            McpToolSchema.NONE,
            "architecture",
            true,
            args -> Map.of("findings", List.of()));
    private final McpTool config = new McpTool(
            "get_config",
            "Search configuration.",
            McpToolSchema.QUERY_LIMIT,
            "config",
            false,
            args -> Map.of(
                    "items",
                    IntStream.range(0, args.limit() == null ? 3 : args.limit())
                            .boxed()
                            .toList()));
    private final McpTool detail = new McpTool(
            "get_exception_detail",
            "Read one exception group.",
            McpToolSchema.ID,
            "exceptions",
            false,
            args -> Map.of("id", args.id()));

    private final FakePolicy policy = new FakePolicy();

    private CliService service(boolean enabled) {
        return new CliService(
                enabled,
                () -> List.of(overview, architecture, config, detail),
                policy,
                "9.9.9",
                "/bootui/api/cli",
                25,
                4,
                5000,
                failure());
    }

    @Test
    void statusDescribesEveryAdvertisedToolWithItsLivePanelGating() {
        policy.disabled.add("architecture");
        policy.readOnly.add("config");

        CliServerStatus status = service(true).status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.serverName()).isEqualTo(McpProtocol.SERVER_NAME);
        assertThat(status.serverVersion()).isEqualTo("9.9.9");
        assertThat(status.endpoint()).isEqualTo("/bootui/api/cli");
        assertThat(status.maxResults()).isEqualTo(25);
        assertThat(status.toolCount()).isEqualTo(4);
        assertThat(status.tools())
                .extracting(CliToolInfo::name)
                .containsExactly("get_overview", "architecture_scan", "get_config", "get_exception_detail");
        assertThat(status.tools()).extracting(CliToolInfo::schema).containsExactly("NONE", "NONE", "QUERY_LIMIT", "ID");
        assertThat(status.tools()).extracting(CliToolInfo::action).containsExactly(false, true, false, false);
        assertThat(status.tools())
                .filteredOn(tool -> tool.name().equals("architecture_scan"))
                .allMatch(tool -> !tool.panelEnabled());
        assertThat(status.tools())
                .filteredOn(tool -> tool.name().equals("get_config"))
                .allMatch(CliToolInfo::panelReadOnly);
    }

    @Test
    void statusProjectsSchemaArgumentNamesSoTheCliCanBuildItsFlags() {
        CliServerStatus status = service(true).status();

        assertThat(status.tools())
                .filteredOn(tool -> tool.name().equals("get_config"))
                .singleElement()
                .satisfies(tool -> assertThat(tool.arguments()).containsExactly("query", "limit"));
        assertThat(status.tools())
                .filteredOn(tool -> tool.name().equals("get_exception_detail"))
                .singleElement()
                .satisfies(tool -> assertThat(tool.arguments()).containsExactly("id"));
        assertThat(status.tools())
                .filteredOn(tool -> tool.name().equals("get_overview"))
                .singleElement()
                .satisfies(tool -> assertThat(tool.arguments()).isEmpty());
    }

    @Test
    void statusAnswersWhileDisabledButAdvertisesNothingInvocable() {
        CliServerStatus status = service(false).status();

        assertThat(status.enabled()).isFalse();
        assertThat(status.tools()).isEmpty();
        assertThat(status.toolCount()).isZero();
        assertThat(status.serverVersion()).isEqualTo("9.9.9");
    }

    @Test
    void invokeReturnsTheToolPayloadDirectly() {
        CliToolResponse response = service(true).invoke("get_overview", null, null, null);

        assertThat(response.status()).isEqualTo(CliStatus.OK);
        assertThat(response.payload()).isEqualTo(Map.of("ok", true));
        assertThat(response.error()).isNull();
    }

    @Test
    void invokeCapsLimitAtMaxResults() {
        CliToolResponse response = service(true).invoke("get_config", "db", 500, null);

        assertThat(response.status()).isEqualTo(CliStatus.OK);
        assertThat(response.payload())
                .isInstanceOfSatisfying(
                        Map.class,
                        payload -> assertThat((List<?>) payload.get("items")).hasSize(25));
    }

    @Test
    void invokeReportsAnUnknownToolAsNotFound() {
        CliToolResponse response = service(true).invoke("no_such_tool", null, null, null);

        assertThat(response.status()).isEqualTo(CliStatus.NOT_FOUND);
        assertThat(response.error()).contains("no_such_tool");
    }

    @Test
    void invokeReportsAnUnexpectedArgumentAsBadRequest() {
        CliToolResponse response = service(true).invoke("get_overview", "anything", null, null);

        assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST);
    }

    @Test
    void invokeReportsAMissingIdAsBadRequest() {
        CliToolResponse response = service(true).invoke("get_exception_detail", null, null, null);

        assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST);
    }

    @Test
    void invokeReportsADisabledPanelAsForbidden() {
        policy.disabled.add("overview");

        CliToolResponse response = service(true).invoke("get_overview", null, null, null);

        assertThat(response.status()).isEqualTo(CliStatus.FORBIDDEN);
        assertThat(response.error()).isEqualTo("disabled:overview");
    }

    @Test
    void invokeRefusesAnActionToolOnAReadOnlyPanelButStillServesReads() {
        policy.readOnly.add("architecture");
        policy.readOnly.add("overview");
        CliService service = service(true);

        assertThat(service.invoke("architecture_scan", null, null, null).status())
                .isEqualTo(CliStatus.FORBIDDEN);
        assertThat(service.invoke("get_overview", null, null, null).status()).isEqualTo(CliStatus.OK);
    }

    @Test
    void invokeIsRefusedWhileDisabled() {
        CliToolResponse response = service(false).invoke("get_overview", null, null, null);

        assertThat(response.status()).isEqualTo(CliStatus.SERVICE_UNAVAILABLE);
        assertThat(response.error()).isEqualTo(CliOutcomes.DISABLED_MESSAGE);
    }

    @Test
    void runtimeStatsCountOnlyCommandLineTraffic() {
        CliService service = service(true);
        assertThat(service.runtimeStats().snapshot().callCount()).isZero();

        service.invoke("get_overview", null, null, null);
        service.invoke("get_overview", null, null, null);

        assertThat(service.runtimeStats().snapshot().callCount()).isEqualTo(2);
    }

    @Test
    void enabledReflectsTheConfiguredSetting() {
        assertThat(service(true).enabled()).isTrue();
        assertThat(service(false).enabled()).isFalse();
    }

    private static McpFailureReporter failure() {
        return (operation, throwable) -> {};
    }

    private static final class FakePolicy implements McpPanelPolicy {
        private final Set<String> disabled = new HashSet<>();
        private final Set<String> readOnly = new HashSet<>();

        @Override
        public boolean isEnabled(String panelId) {
            return !disabled.contains(panelId);
        }

        @Override
        public String disabledReason(String panelId) {
            return "disabled:" + panelId;
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return readOnly.contains(panelId);
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "read-only:" + panelId;
        }
    }

    @Test
    void unexpectedRequestBodyPropertiesAreRefusedRatherThanIgnored() {
        CliToolResponse response = service(true).invoke("get_config", Map.of("q", "spring"));

        assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST);
        assertThat(response.error()).isEqualTo("Unexpected tool argument: q");
    }

    @Test
    void requestBodyArgumentTypesAreValidatedTheSameWayTheMcpTransportValidatesThem() {
        CliService service = service(true);

        assertThat(service.invoke("get_config", Map.of("query", 7)))
                .satisfies(response -> assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST))
                .satisfies(response -> assertThat(response.error()).isEqualTo("Argument 'query' must be a string"));
        assertThat(service.invoke("get_config", Map.of("limit", "5")))
                .satisfies(response -> assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST))
                .satisfies(response -> assertThat(response.error()).isEqualTo("Argument 'limit' must be an integer"));
        assertThat(service.invoke("get_config", Map.of("limit", 2.5)))
                .satisfies(response -> assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST));
        assertThat(service.invoke("get_config", Map.of("limit", 0)))
                .satisfies(response -> assertThat(response.status()).isEqualTo(CliStatus.BAD_REQUEST))
                .satisfies(response -> assertThat(response.error()).isEqualTo("Argument 'limit' must be at least 1"));
    }

    @Test
    void aRequestBodyArgumentSentAsAWholeNumberOfAnyWidthIsAccepted() {
        CliToolResponse response = service(true).invoke("get_config", Map.of("limit", 2L));

        assertThat(response.status()).isEqualTo(CliStatus.OK);
        assertThat(response.payload())
                .isInstanceOfSatisfying(
                        Map.class,
                        payload -> assertThat((List<?>) payload.get("items")).hasSize(2));
    }

    @Test
    void anAbsentRequestBodyInvokesTheToolWithNoArguments() {
        CliToolResponse response = service(true).invoke("get_overview", (Map<String, Object>) null);

        assertThat(response.status()).isEqualTo(CliStatus.OK);
    }
}
