package io.github.jdubois.bootui.autoconfigure.security;

import java.util.List;

/**
 * Bounded, read-only snapshot of the host application's Spring Security WebFlux (reactive)
 * configuration. Built by {@link ReactiveSecurityScanner} from the registered
 * {@code SecurityWebFilterChain} beans and related security beans, and consumed by the reactive
 * advisor ruleset. Holds no credentials, keys, or session identifiers.
 */
final class ReactiveSecurityModel {

    private ReactiveSecurityModel() {}

    /**
     * One {@code SecurityWebFilterChain} and the salient, read-only facts the reactive advisor
     * needs about it.
     *
     * @param permitsAllAnonymous best-effort result of whether the chain permits all requests
     *     without authorization ({@code TRUE} when no {@code AuthorizationWebFilter} is found,
     *     {@code null} when it could not be determined)
     * @param webFilterNames simple class names of the {@code WebFilter}s installed in the chain
     * @param headerWriterNames simple class names of the {@code ServerHttpHeadersWriter}s installed
     *     by the chain's {@code HttpHeaderWriterWebFilter}, when one is present
     * @param hstsMaxAgeSeconds the HSTS writer's configured {@code maxAgeInSeconds}, when an HSTS
     *     writer is present and the field could be read
     * @param hstsIncludeSubdomains the HSTS writer's configured {@code includeSubDomains}
     * @param cspPolicyDirectives the CSP writer's configured {@code policyDirectives}
     * @param cspReportOnly whether the CSP writer emits Content-Security-Policy-Report-Only
     */
    record WebFilterChainModel(
            int index,
            String matcher,
            List<String> webFilterNames,
            Boolean permitsAllAnonymous,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly) {

        private static final long HSTS_MIN_MAX_AGE_SECONDS = 31536000L;

        WebFilterChainModel {
            webFilterNames = List.copyOf(webFilterNames);
            headerWriterNames = headerWriterNames == null ? List.of() : List.copyOf(headerWriterNames);
        }

        boolean hasWebFilter(String simpleName) {
            return webFilterNames.stream().anyMatch(name -> name.equals(simpleName));
        }

        boolean hasAuthorizationWebFilter() {
            return hasWebFilter("AuthorizationWebFilter");
        }

        boolean hasCsrfWebFilter() {
            return hasWebFilter("CsrfWebFilter");
        }

        boolean hasHeaderWriterWebFilter() {
            return hasWebFilter("HttpHeaderWriterWebFilter");
        }

        boolean hasHttpsRedirectFilter() {
            return hasWebFilter("HttpsRedirectWebFilter");
        }

        boolean hasHstsWriter() {
            return headerWriterNames.stream().anyMatch(name -> name.contains("Hsts"));
        }

        boolean hasFrameOptionsWriter() {
            return headerWriterNames.stream()
                    .anyMatch(name -> name.contains("FrameOptions") || name.contains("XFrame"));
        }

        boolean hasCspWriter() {
            return headerWriterNames.stream().anyMatch(name -> name.contains("ContentSecurityPolicy"));
        }

        boolean hasContentTypeOptionsWriter() {
            return headerWriterNames.stream().anyMatch(name -> name.contains("ContentTypeOptions"));
        }

        boolean hasWeakHsts() {
            return hstsMaxAgeSeconds != null && hstsMaxAgeSeconds < HSTS_MIN_MAX_AGE_SECONDS;
        }

        boolean matchesAnyRequest() {
            if (matcher == null) {
                return false;
            }
            String normalized = matcher.toLowerCase(java.util.Locale.ROOT).trim();
            return normalized.equals("any request")
                    || normalized.contains("anyrequest")
                    || normalized.contains("[/**]");
        }

        boolean hasAuthenticationFilter() {
            return webFilterNames.stream()
                    .anyMatch(name -> (name.contains("Authentication") && name.contains("WebFilter"))
                            || name.contains("OAuth2Login")
                            || name.contains("OidcLogin"));
        }

        String describe() {
            return "Chain " + index + " (" + (matcher != null ? matcher : "unknown matcher") + ")";
        }

        boolean isStateful() {
            // Reactive chains using OAuth2 login are stateful; pure bearer-token resource servers
            // are stateless.
            return hasWebFilter("OAuth2LoginAuthenticationWebFilter")
                    || hasWebFilter("OAuth2AuthorizationCodeGrantWebFilter");
        }
    }

    record CorsConfigModel(
            String pattern,
            List<String> allowedOrigins,
            List<String> allowedOriginPatterns,
            List<String> allowedMethods,
            List<String> allowedHeaders,
            Boolean allowCredentials) {

        CorsConfigModel {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedOriginPatterns =
                    allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
            allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
            allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
        }

        boolean hasWildcardOrigin() {
            return allowedOrigins.contains("*");
        }

        boolean hasWildcardOriginPattern() {
            return allowedOriginPatterns.stream().anyMatch(p -> p.equals("*") || p.endsWith("**"));
        }
    }
}
