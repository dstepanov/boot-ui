package io.github.jdubois.bootui.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

/**
 * Opens BootUI's own route inside Spring Security while keeping the localhost-only reactive filter
 * as the outer safety boundary.
 *
 * <p>Reactive counterpart of {@link BootUiSpringSecurityAutoConfiguration}: creates a highest-priority
 * {@link SecurityWebFilterChain} that permits all unauthenticated access to the BootUI routes
 * ({@code /bootui} and {@code /bootui/**}) and sets up SPA-friendly cookie-based CSRF for the
 * browser UI — exempting the MCP JSON-RPC bridge and auth-session endpoint (programmatic clients
 * that cannot present a CSRF token).</p>
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(
        name = {
            "org.springframework.security.config.web.server.ServerHttpSecurity",
            "org.springframework.security.web.server.SecurityWebFilterChain"
        })
@ConditionalOnBean(ServerHttpSecurity.class)
@EnableConfigurationProperties(BootUiProperties.class)
public class BootUiReactiveSpringSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiReactiveSpringSecurityAutoConfiguration.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "bootUiReactiveSecurityWebFilterChain")
    public SecurityWebFilterChain bootUiReactiveSecurityWebFilterChain(
            ServerHttpSecurity http, BootUiProperties properties) {
        String[] bootUiPatterns = BootUiSpringSecurityAutoConfiguration.bootUiSecurityPatterns(properties);
        String mcpPattern = childSecurityEndpoint(properties.getApiPath(), "mcp");
        String mcpDescendantsPattern = childSecurityPattern(properties.getApiPath(), "mcp");
        String authSessionPattern = childSecurityEndpoint(properties.getApiPath(), "auth/session");
        log.warn(
                "BootUI detected Spring Security (reactive) and is permitting unauthenticated access to {}; "
                        + "BootUI's localhost-only filter still rejects non-loopback callers unless "
                        + "bootui.allow-non-localhost=true is set.",
                String.join(", ", bootUiPatterns));

        ServerWebExchangeMatcher chainMatcher = toOrMatcher(bootUiPatterns);
        ServerWebExchangeMatcher csrfExclusions =
                toOrMatcher(mcpPattern, mcpDescendantsPattern + "/**", authSessionPattern);
        return http.securityMatcher(chainMatcher)
                .authorizeExchange(authorize -> authorize.anyExchange().permitAll())
                // The MCP bridge and auth-session endpoint are called by programmatic clients that
                // cannot present a CSRF token; they are protected by LocalhostOnlyFilter instead.
                .csrf(csrf -> csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(new NegatedServerWebExchangeMatcher(csrfExclusions)))
                .build();
    }

    private static ServerWebExchangeMatcher toOrMatcher(String... patterns) {
        ServerWebExchangeMatcher[] matchers = new ServerWebExchangeMatcher[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            matchers[i] = new PathPatternParserServerWebExchangeMatcher(patterns[i]);
        }
        return matchers.length == 1 ? matchers[0] : new OrServerWebExchangeMatcher(matchers);
    }

    /** Inverts the match result of a delegate matcher. */
    private static final class NegatedServerWebExchangeMatcher implements ServerWebExchangeMatcher {

        private final ServerWebExchangeMatcher delegate;

        NegatedServerWebExchangeMatcher(ServerWebExchangeMatcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public reactor.core.publisher.Mono<MatchResult> matches(
                org.springframework.web.server.ServerWebExchange exchange) {
            return delegate.matches(exchange).map(result -> result.isMatch() ? MatchResult.notMatch() : MatchResult.match());
        }
    }

    private static String childSecurityPattern(String basePath, String childPath) {
        return childSecurityEndpoint(basePath, childPath);
    }

    private static String childSecurityEndpoint(String basePath, String childPath) {
        String normalized = basePath;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/" + childPath;
    }
}
