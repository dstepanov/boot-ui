package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.WebFilterChainModel;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rule interface for the reactive security advisor, analogous to {@link SecurityRule} but bound to
 * {@link ReactiveSecurityContext}.
 */
interface ReactiveSecurityRule {

    SecurityRuleDefinition definition();

    SecurityRuleResultDto evaluate(ReactiveSecurityContext context);
}

/**
 * Abstract base for reactive security rules, providing pass/skip/violation helpers that delegate
 * to {@link SecurityRuleSupport}.
 */
abstract class AbstractReactiveSecurityRule implements ReactiveSecurityRule {

    private final SecurityRuleDefinition definition;

    AbstractReactiveSecurityRule(SecurityRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final SecurityRuleDefinition definition() {
        return definition;
    }

    abstract SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context);

    @Override
    public final SecurityRuleResultDto evaluate(ReactiveSecurityContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return SecurityRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    SecurityRuleResultDto pass() {
        return SecurityRuleSupport.pass(definition);
    }

    SecurityRuleResultDto skipped(String reason) {
        return SecurityRuleSupport.skipped(definition, reason);
    }

    SecurityRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : SecurityRuleSupport.violation(definition, details);
    }

    SecurityRuleResultDto violation(String severityOverride, List<String> details) {
        return details.isEmpty() ? pass() : SecurityRuleSupport.violation(definition, severityOverride, details);
    }
}

// ---------------------------------------------------------------------------
// Authorization
// ---------------------------------------------------------------------------

final class ReactiveAuthorizationFilterRule extends AbstractReactiveSecurityRule {

    ReactiveAuthorizationFilterRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-AUTHZ-001",
                "Every reactive filter chain should enforce authorization",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects a SecurityWebFilterChain that installs no AuthorizationWebFilter, so matched requests are unguarded.",
                "Add authorizeExchange(...) with at least anyExchange().authenticated() (or an explicit denyAll) to the chain.",
                "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (!chain.hasAuthorizationWebFilter()) {
                details.add(chain.describe() + " installs no authorization web filter.");
            }
        }
        return violation(details);
    }
}

final class ReactivePermitAllCatchAllRule extends AbstractReactiveSecurityRule {

    ReactivePermitAllCatchAllRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-AUTHZ-002",
                "Avoid blanket permitAll authorization in reactive chains",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects a reactive chain whose authorization grants every request to anonymous callers (permitAll catch-all) while also configuring authentication.",
                "Restrict sensitive paths and finish with anyExchange().authenticated(); keep permitAll only for genuinely public endpoints.",
                "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (Boolean.TRUE.equals(chain.permitsAllAnonymous())
                    && chain.matchesAnyRequest()
                    && chain.hasAuthenticationFilter()) {
                details.add(chain.describe()
                        + " matches every request and permits it anonymously even though it configures authentication.");
            }
        }
        return violation(details);
    }
}

final class ReactiveEffectivelyDisabledSecurityRule extends AbstractReactiveSecurityRule {

    ReactiveEffectivelyDisabledSecurityRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-AUTHZ-003",
                "Reactive application security should not be effectively disabled",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects when every reactive filter chain permits all requests anonymously with no authentication mechanism.",
                "Define authorization rules requiring authentication for non-public endpoints instead of leaving the app fully open.",
                "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<WebFilterChainModel> chains = context.chains();
        if (chains.isEmpty()) {
            return pass();
        }
        boolean anyDeterminable = chains.stream().anyMatch(chain -> chain.permitsAllAnonymous() != null);
        if (!anyDeterminable) {
            return skipped("Authorization decisions could not be determined for any chain.");
        }
        boolean allOpen = chains.stream().allMatch(chain -> Boolean.TRUE.equals(chain.permitsAllAnonymous()));
        boolean anyAuthentication = chains.stream().anyMatch(WebFilterChainModel::hasAuthenticationFilter);
        if (allOpen && !anyAuthentication) {
            return violation(List.of("All " + chains.size()
                    + " reactive security filter chains permit every request anonymously with no authentication mechanism."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// CSRF
// ---------------------------------------------------------------------------

final class ReactiveCsrfDisabledStatefulRule extends AbstractReactiveSecurityRule {

    ReactiveCsrfDisabledStatefulRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CSRF-001",
                "Reactive stateful chains should enable CSRF protection",
                SecurityCategory.CSRF,
                "HIGH",
                "Detects a reactive chain with OAuth2 login or session-based authentication but no CsrfWebFilter. Without CSRF protection, cross-origin state-changing requests can be forged.",
                "Add .csrf(Customizer.withDefaults()) or configure a CookieServerCsrfTokenRepository for reactive applications using session-based authentication.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.isStateful() && !chain.hasCsrfWebFilter()) {
                details.add(chain.describe()
                        + " uses session-based authentication but no CsrfWebFilter is installed.");
            }
        }
        return violation(details);
    }
}

final class ReactiveCsrfGloballyDisabledRule extends AbstractReactiveSecurityRule {

    ReactiveCsrfGloballyDisabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CSRF-002",
                "CSRF should not be globally disabled in reactive applications",
                SecurityCategory.CSRF,
                "MEDIUM",
                "Detects when none of the registered SecurityWebFilterChain beans installs a CsrfWebFilter. Stateless REST APIs using only bearer tokens do not need CSRF; check whether all chains are intentionally stateless.",
                "For non-stateless applications add .csrf(Customizer.withDefaults()) to chains using session-based or cookie-based authentication.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (context.chains().isEmpty()) {
            return pass();
        }
        boolean anyHasCsrf = context.chains().stream().anyMatch(WebFilterChainModel::hasCsrfWebFilter);
        if (!anyHasCsrf) {
            return violation(List.of("No CsrfWebFilter was found across all "
                    + context.chains().size()
                    + " registered reactive security filter chains. Verify all chains are intentionally stateless."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// CORS
// ---------------------------------------------------------------------------

final class ReactiveCorsWildcardOriginRule extends AbstractReactiveSecurityRule {

    ReactiveCorsWildcardOriginRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CORS-001",
                "CORS should not allow wildcard origins in reactive applications",
                SecurityCategory.CORS,
                "MEDIUM",
                "Detects a reactive CorsConfigurationSource that permits requests from any origin (allowedOrigins: \"*\").",
                "Enumerate allowed origins explicitly, e.g. https://app.example.com, instead of using the wildcard.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.corsSourcePresent()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (CorsConfigModel config : context.corsConfigs()) {
            if (config.hasWildcardOrigin() || config.hasWildcardOriginPattern()) {
                details.add("CORS config for pattern '" + config.pattern() + "' allows all origins (wildcard).");
            }
        }
        return violation(details);
    }
}

final class ReactiveCorsWildcardWithCredentialsRule extends AbstractReactiveSecurityRule {

    ReactiveCorsWildcardWithCredentialsRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CORS-002",
                "CORS wildcard origin must not be combined with allow-credentials in reactive apps",
                SecurityCategory.CORS,
                "HIGH",
                "Detects a reactive CORS configuration that combines a wildcard origin with allowCredentials=true.",
                "Replace the wildcard with explicit allowed origins before enabling credentials.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.corsSourcePresent()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (CorsConfigModel config : context.corsConfigs()) {
            boolean wildcard = config.hasWildcardOrigin() || config.hasWildcardOriginPattern();
            if (wildcard && Boolean.TRUE.equals(config.allowCredentials())) {
                details.add("CORS config for pattern '"
                        + config.pattern()
                        + "' combines a wildcard origin with allowCredentials=true.");
            }
        }
        return violation("CRITICAL", details);
    }
}

// ---------------------------------------------------------------------------
// Transport & security headers
// ---------------------------------------------------------------------------

final class ReactiveHstsHeaderRule extends AbstractReactiveSecurityRule {

    ReactiveHstsHeaderRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-HEAD-001",
                "HSTS header should be configured for reactive applications over TLS",
                SecurityCategory.HEADERS,
                "MEDIUM",
                "Detects chains that apply security headers (HttpHeaderWriterWebFilter) but do not include an HSTS writer while TLS is configured.",
                "Add a HstsServerHttpHeadersWriter via .headers(h -> h.hsts(Customizer.withDefaults())) to chains serving HTTPS traffic.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isTlsConfigured()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.hasHeaderWriterWebFilter() && !chain.hasHstsWriter()) {
                details.add(chain.describe() + " applies security headers without HSTS while TLS is configured.");
            }
        }
        return violation(details);
    }
}

final class ReactiveFrameOptionsRule extends AbstractReactiveSecurityRule {

    ReactiveFrameOptionsRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-HEAD-002",
                "X-Frame-Options header should be set in reactive chains",
                SecurityCategory.HEADERS,
                "MEDIUM",
                "Detects chains with security header writers but no FrameOptions writer, leaving the application vulnerable to clickjacking.",
                "Enable frame-options protection via .headers(h -> h.frameOptions(Customizer.withDefaults())).",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-frame-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.hasHeaderWriterWebFilter() && !chain.hasFrameOptionsWriter()) {
                details.add(chain.describe()
                        + " applies security headers without X-Frame-Options (clickjacking protection).");
            }
        }
        return violation(details);
    }
}

final class ReactiveContentTypeOptionsRule extends AbstractReactiveSecurityRule {

    ReactiveContentTypeOptionsRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-HEAD-003",
                "X-Content-Type-Options should be set in reactive chains",
                SecurityCategory.HEADERS,
                "LOW",
                "Detects chains with security header writers but no ContentTypeOptions writer.",
                "Enable content-type sniffing prevention via .headers(h -> h.contentTypeOptions(Customizer.withDefaults())).",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-content-type-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.hasHeaderWriterWebFilter() && !chain.hasContentTypeOptionsWriter()) {
                details.add(chain.describe()
                        + " applies security headers without X-Content-Type-Options (nosniff).");
            }
        }
        return violation(details);
    }
}

final class ReactiveContentSecurityPolicyRule extends AbstractReactiveSecurityRule {

    ReactiveContentSecurityPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-HEAD-004",
                "Content-Security-Policy should be defined in reactive chains",
                SecurityCategory.HEADERS,
                "MEDIUM",
                "Detects chains with security header writers that do not configure a Content-Security-Policy.",
                "Configure a Content-Security-Policy via .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(\"...\"))).",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-csp"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.hasHeaderWriterWebFilter() && !chain.hasCspWriter()) {
                details.add(chain.describe()
                        + " applies security headers but does not define a Content-Security-Policy.");
            }
        }
        return violation(details);
    }
}

final class ReactiveHeadersDisabledRule extends AbstractReactiveSecurityRule {

    ReactiveHeadersDisabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-HEAD-005",
                "Security headers should not be disabled in reactive chains",
                SecurityCategory.HEADERS,
                "HIGH",
                "Detects chains with authentication or authorization filters but no HttpHeaderWriterWebFilter, meaning all Spring Security header protections are absent.",
                "Do not call .headers(h -> h.disable()) unless the application sets equivalent headers via another mechanism.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            boolean hasSecurityFilters = chain.hasAuthorizationWebFilter() || chain.hasAuthenticationFilter();
            if (hasSecurityFilters && !chain.hasHeaderWriterWebFilter()) {
                details.add(chain.describe()
                        + " enforces authentication/authorization but installs no security header writer (HttpHeaderWriterWebFilter).");
            }
        }
        return violation(details);
    }
}

final class ReactiveWeakHstsPolicyRule extends AbstractReactiveSecurityRule {

    ReactiveWeakHstsPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-HEAD-006",
                "HSTS max-age should be at least one year in reactive applications",
                SecurityCategory.HEADERS,
                "LOW",
                "Detects an HSTS writer configured with a max-age below the recommended one-year minimum (31,536,000 seconds).",
                "Set hsts.maxAge(Duration.ofDays(365)) or higher, and set includeSubDomains when appropriate.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.hasHstsWriter() && chain.hasWeakHsts()) {
                details.add(chain.describe()
                        + " configures HSTS with a max-age of "
                        + chain.hstsMaxAgeSeconds()
                        + " seconds, below the recommended 31,536,000 (one year).");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Actuator exposure
// ---------------------------------------------------------------------------

final class ReactiveActuatorWildcardExposureRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorWildcardExposureRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-ACT-001",
                "Actuator endpoints should not be exposed with a wildcard",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Detects management.endpoints.web.exposure.include=* without any exclude, which exposes all Actuator endpoints including sensitive ones.",
                "Explicitly list only the endpoints you need, or add management.endpoints.web.exposure.exclude to exclude sensitive endpoints.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        String include = context.firstHostProperty("management.endpoints.web.exposure.include");
        String exclude = context.firstHostProperty("management.endpoints.web.exposure.exclude");
        if ("*".equals(include) && (exclude == null || exclude.isBlank())) {
            return violation(List.of(
                    "management.endpoints.web.exposure.include=* exposes all Actuator endpoints, including sensitive ones (env, beans, heapdump, shutdown)."));
        }
        return pass();
    }
}

final class ReactiveActuatorSensitiveExposureRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorSensitiveExposureRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-ACT-002",
                "Sensitive Actuator endpoints should be explicitly reviewed",
                SecurityCategory.ACTUATOR,
                "MEDIUM",
                "Detects one or more sensitive Actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown, loggers, mappings) explicitly included in management.endpoints.web.exposure.include.",
                "Restrict sensitive Actuator endpoints to a separate management port, or protect them with authentication/network policies.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        Set<String> exposed = context.effectiveSensitiveActuatorExposure();
        if (exposed.isEmpty()) {
            return pass();
        }
        return violation(List.of("Sensitive Actuator endpoints exposed: " + String.join(", ", exposed)
                + ". Ensure these are protected by authentication or a restricted network path."));
    }
}

final class ReactiveActuatorUnprotectedRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorUnprotectedRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-ACT-003",
                "Actuator endpoints should be protected when more than health/info are exposed",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Detects that Actuator endpoints beyond health/info are exposed AND every reactive security chain is fully open, leaving sensitive management operations unprotected.",
                "Add authentication requirements for the /actuator/** path, or restrict sensitive endpoints to a separate management port.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        boolean allChainsOpen = !context.chains().isEmpty()
                && context.chains().stream()
                        .allMatch(chain -> Boolean.TRUE.equals(chain.permitsAllAnonymous()));
        if (allChainsOpen) {
            return violation(List.of(
                    "Actuator endpoints beyond health/info are exposed and every reactive security chain permits unauthenticated access."));
        }
        return pass();
    }
}

final class ReactiveManagementPortIsolationRule extends AbstractReactiveSecurityRule {

    ReactiveManagementPortIsolationRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-ACT-004",
                "Consider isolating Actuator endpoints on a separate management port",
                SecurityCategory.ACTUATOR,
                "INFO",
                "Detects that sensitive Actuator endpoints are exposed on the same port as the application, without a separate management port configured.",
                "Set management.server.port to a non-public port so Actuator endpoints are not reachable via the application's main port.",
                "https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        if (context.firstHostProperty("management.server.port") != null) {
            return pass();
        }
        return violation(List.of(
                "Sensitive Actuator endpoints are exposed on the application's main port. "
                        + "Consider setting management.server.port to isolate them."));
    }
}

// ---------------------------------------------------------------------------
// OAuth2 / JWT
// ---------------------------------------------------------------------------

final class ReactiveJwtAudienceValidationRule extends AbstractReactiveSecurityRule {

    ReactiveJwtAudienceValidationRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-OAUTH2-001",
                "Reactive JWT resource server should validate the audience claim",
                SecurityCategory.OAUTH2,
                "MEDIUM",
                "Detects a ReactiveJwtDecoder bean with no configured OAuth2TokenValidator that checks the audience claim. Without audience validation, a JWT issued for a different service can be replayed against this application.",
                "Configure a DelegatingReactiveJwtDecoder with a JwtClaimValidator<List<String>>(\"aud\", ...) or use spring.security.oauth2.resourceserver.jwt.audiences.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (context.reactiveJwtDecoderTypes().isEmpty()) {
            return pass();
        }
        if (context.oauth2TokenValidatorTypes().isEmpty()) {
            return violation(List.of(
                    "A ReactiveJwtDecoder is configured but no OAuth2TokenValidator was found; the JWT audience claim may not be validated."));
        }
        return pass();
    }
}

final class ReactiveJwtStaticKeyRule extends AbstractReactiveSecurityRule {

    ReactiveJwtStaticKeyRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-OAUTH2-002",
                "Reactive JWT resource server should not use a hardcoded signing secret",
                SecurityCategory.OAUTH2,
                "HIGH",
                "Detects spring.security.oauth2.resourceserver.jwt.secret-value (a symmetric HMAC key) configured as a literal string in application properties.",
                "Use an asymmetric key pair (RSA/EC) with a JWKS endpoint, or store the symmetric key in a secrets manager.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        String secretValue = context.firstProperty(
                "spring.security.oauth2.resourceserver.jwt.secret-value",
                "spring.security.oauth2.resourceserver.jwt.secret");
        if (secretValue != null && !secretValue.contains("${")) {
            return violation(List.of(
                    "spring.security.oauth2.resourceserver.jwt.secret-value is set as a literal; use a JWKS endpoint or an external secrets manager instead."));
        }
        return pass();
    }
}

final class ReactiveInsecureJwtMetadataUrlRule extends AbstractReactiveSecurityRule {

    ReactiveInsecureJwtMetadataUrlRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-OAUTH2-003",
                "JWT issuer URI and JWKS URI should use HTTPS in reactive applications",
                SecurityCategory.OAUTH2,
                "HIGH",
                "Detects spring.security.oauth2.resourceserver.jwt.issuer-uri or jwk-set-uri configured with a plain HTTP URL in a production profile.",
                "Use https:// for all issuer-uri and jwk-set-uri values in non-development environments.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (String key : List.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")) {
            String value = context.firstProperty(key);
            if (value != null && value.toLowerCase(java.util.Locale.ROOT).startsWith("http://")) {
                details.add(key + " uses plain HTTP; switch to HTTPS.");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Configuration hygiene
// ---------------------------------------------------------------------------

final class ReactiveSecurityDebugRule extends AbstractReactiveSecurityRule {

    ReactiveSecurityDebugRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CONFIG-001",
                "Spring Security debug mode should not be active",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects spring.security.debug=true, which logs full request/response details including authentication headers and session tokens.",
                "Remove spring.security.debug=true before deploying to any shared or production environment.",
                "https://docs.spring.io/spring-security/reference/reactive/index.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (context.isPropertyTrue("spring.security.debug")) {
            return violation(List.of(
                    "spring.security.debug=true is active; this logs credential and session details to standard output."));
        }
        return pass();
    }
}

final class ReactiveHttpsEnforcementRule extends AbstractReactiveSecurityRule {

    ReactiveHttpsEnforcementRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CONFIG-002",
                "Reactive application should enforce HTTPS in production",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects a production profile active without any TLS configuration or HttpsRedirectWebFilter. Running over plain HTTP in production exposes all traffic.",
                "Configure TLS (server.ssl.*) or terminate it upstream and set server.forward-headers-strategy, or add .redirectToHttps(Customizer.withDefaults()) to production chains.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/https.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isTlsConfigured()) {
            return pass();
        }
        return violation(List.of(
                "A production profile is active but no TLS configuration or HttpsRedirectWebFilter was found. All traffic is served over plain HTTP."));
    }
}

final class ReactiveHardcodedSecretPropertyRule extends AbstractReactiveSecurityRule {

    ReactiveHardcodedSecretPropertyRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CONFIG-003",
                "Credentials or secrets should not be hardcoded in application properties",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects application property keys whose names suggest they hold credentials or secrets and whose values appear to be literal strings rather than placeholder references. Only the property name is reported; the value itself is never surfaced.",
                "Move secrets to environment variables, a secrets manager, Spring Cloud Vault, or another externalization mechanism.",
                "https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        Set<String> suspected = context.suspectedHardcodedSecretKeys();
        if (suspected.isEmpty()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (String key : suspected) {
            details.add("Property key '" + key + "' appears to hold a literal credential (value not shown).");
        }
        return violation(details);
    }
}

final class ReactiveSecurityDebugLoggingProductionRule extends AbstractReactiveSecurityRule {

    ReactiveSecurityDebugLoggingProductionRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-CONFIG-004",
                "Spring Security DEBUG logging should not run in production",
                SecurityCategory.CONFIGURATION,
                "MEDIUM",
                "Detects DEBUG-level logging configured for Spring Security packages while a production profile is active.",
                "Set logging.level.org.springframework.security to INFO or WARN in the production profile.",
                "https://docs.spring.io/spring-security/reference/reactive/index.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        String level = context.firstProperty(
                "logging.level.org.springframework.security",
                "logging.level.org.springframework.security.web");
        if (level != null && "DEBUG".equalsIgnoreCase(level.trim())) {
            return violation(List.of(
                    "logging.level.org.springframework.security is set to DEBUG while a production profile is active."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

final class ReactiveBearerTokenStatefulRule extends AbstractReactiveSecurityRule {

    ReactiveBearerTokenStatefulRule() {
        super(new SecurityRuleDefinition(
                "SEC-RXF-SESSION-001",
                "Bearer-token resource server should use stateless session management",
                SecurityCategory.SESSION,
                "LOW",
                "Detects a reactive chain that appears to handle bearer-token authentication (BearerTokenAuthenticationWebFilter) while also configuring session-based authentication mechanisms. JWTs are self-contained credentials; pairing them with session storage is redundant and increases attack surface.",
                "For pure bearer-token resource servers, configure .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) to disable server-side sessions.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainModel chain : context.chains()) {
            if (chain.hasWebFilter("BearerTokenAuthenticationWebFilter") && chain.isStateful()) {
                details.add(chain.describe()
                        + " configures both bearer-token authentication and session-based authentication; consider using stateless sessions for pure resource server chains.");
            }
        }
        return violation(details);
    }
}
