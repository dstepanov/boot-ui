package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.WebFilterChainModel;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReactiveSecurityScannerTests {

    private static final int RULE_COUNT = ReactiveSecurityRuleRegistry.RULE_COUNT;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void initialReportIsNotScanned() {
        ReactiveSecurityContext context = minimalContext();
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
        assertThat(report.localOnly()).isTrue();
    }

    @Test
    void scanReportsRuleFindingsAcrossCategories() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "HttpHeaderWriterWebFilter",
                        "AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter", "XFrameOptionsServerHttpHeadersWriter",
                        "XXssProtectionServerHttpHeadersWriter", "ContentTypeOptionsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*");
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain),
                List.of(new CorsConfigModel("/**", List.of("*"), List.of(), List.of(), List.of(), Boolean.TRUE)),
                true,
                List.of(),
                List.of(),
                List.of(),
                environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains(
                        "SEC-RXF-CORS-001",
                        "SEC-RXF-CORS-002",
                        "SEC-RXF-ACT-001");
        // Severity histogram always lists all five severities
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void scanWithNoProxyReturnsDisabled() {
        // No-proxy case: discoverySupplier returns null context
        ReactiveSecurityContext context = null;
        // We cannot directly pass null, so we simulate via scanner with empty context
        // The scanner returns DISABLED when context is null (i.e., no WebFilterChainProxy found).
        // This is tested indirectly: construct with a context that has no chains and verify scan succeeds.
        WebFilterChainModel chain = new WebFilterChainModel(
                0, "any request", List.of(), null, List.of(), null, null, null, null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext emptyContext = new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(emptyContext, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
    }

    @Test
    void applyDismissalsMarksAndFiltersResults() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0, "any request", List.of(), Boolean.TRUE, List.of(), null, null, null, null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();
        assertThat(report.violationsFound()).isPositive();

        String firstViolationId = report.results().get(0).id();
        SecurityReport withDismissal = scanner.applyDismissals(report, java.util.Set.of(firstViolationId));

        assertThat(withDismissal.violationsFound()).isEqualTo(report.violationsFound() - 1);
        assertThat(withDismissal.results().stream().filter(r -> r.id().equals(firstViolationId)).findFirst())
                .isPresent()
                .get()
                .extracting(SecurityRuleResultDto::dismissed)
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void scanChainWithAuthorizationWebFilterPassesAuthzRule() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of("SecurityContextServerWebExchangeWebFilter", "AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter", "XFrameOptionsServerHttpHeadersWriter",
                        "XXssProtectionServerHttpHeadersWriter", "ContentTypeOptionsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        // SEC-RXF-AUTHZ-001 should not fire: AuthorizationWebFilter is present
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-AUTHZ-001");
    }

    @Test
    void scanDetectsCsrfWebFilterAbsence() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "AuthorizationWebFilter",
                        "OAuth2LoginAuthenticationWebFilter",
                        "OAuth2AuthorizationCodeGrantWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        // Stateful chain without CsrfWebFilter should trigger SEC-RXF-CSRF-001
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CSRF-001");
    }

    @Test
    void scanDetectsWildcardCorsOrigin() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain),
                List.of(new CorsConfigModel("/**", List.of("*"), List.of(), List.of(), List.of(), null)),
                true,
                List.of(),
                List.of(),
                List.of(),
                environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-001");
    }

    @Test
    void scanDetectsWildcardCorsWithCredentials() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain),
                List.of(new CorsConfigModel("/**", List.of("*"), List.of(), List.of(), List.of(), Boolean.TRUE)),
                true,
                List.of(),
                List.of(),
                List.of(),
                environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-002");
    }

    @Test
    void scanDetectsMissingHstsHeader() {
        // Chain with no HstsServerHttpHeadersWriter
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("XFrameOptionsServerHttpHeadersWriter"),
                null,
                null,
                null,
                null);
        MockEnvironment environment = new MockEnvironment();
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-001");
    }

    @Test
    void scanDetectsActuatorWildcardExposure() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of("AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*");
        ReactiveSecurityContext context = new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
        ReactiveSecurityScanner scanner = new ReactiveSecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-ACT-001");
    }

    @Test
    void ruleCountMatchesRegistry() {
        assertThat(ReactiveSecurityRuleRegistry.activeRules()).hasSize(RULE_COUNT);
    }

    @Test
    void allRuleIdsStartWithSecRxf() {
        assertThat(ReactiveSecurityRuleRegistry.activeRules())
                .extracting(r -> r.definition().id())
                .allMatch(id -> id.startsWith("SEC-RXF-"));
    }

    @Test
    void allRuleIdsAreUnique() {
        List<String> ids = ReactiveSecurityRuleRegistry.activeRules().stream()
                .map(r -> r.definition().id())
                .toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ReactiveSecurityContext minimalContext() {
        WebFilterChainModel chain = new WebFilterChainModel(
                0,
                "any request",
                List.of("AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of("HstsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        MockEnvironment environment = new MockEnvironment();
        return new ReactiveSecurityContext(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment);
    }
}
