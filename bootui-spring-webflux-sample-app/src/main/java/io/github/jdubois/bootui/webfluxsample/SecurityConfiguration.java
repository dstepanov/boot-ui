package io.github.jdubois.bootui.webfluxsample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Minimal reactive Spring Security configuration for the WebFlux sample app.
 *
 * <p>Configures a simple, one-user in-memory authentication store and requires authentication on
 * all application routes. BootUI's own {@code BootUiReactiveSpringSecurityAutoConfiguration} inserts
 * a higher-priority permit-all filter chain for the {@code /bootui/**} surface, so the console
 * is fully accessible without logging in.</p>
 */
@Configuration
public class SecurityConfiguration {

    /**
     * Application security chain: requires authentication for all non-BootUI routes.
     *
     * <p>BootUI's own higher-priority chain ({@code bootUiReactiveSecurityWebFilterChain}) matches
     * first on {@code /bootui/**} and permits all access there; this chain covers the rest of the
     * application.</p>
     */
    @Bean
    public SecurityWebFilterChain applicationSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.authorizeExchange(authorize -> authorize
                        .pathMatchers("/api/**", "/greeting/**").authenticated()
                        .anyExchange().permitAll())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }

    /**
     * Provides a demo user so the application can demonstrate authenticated endpoints.
     * The password is never exposed by BootUI — only the property key is flagged if it
     * appears in application properties as a plain-text literal.
     */
    @Bean
    @SuppressWarnings("deprecation")
    public MapReactiveUserDetailsService userDetailsService() {
        return new MapReactiveUserDetailsService(
                User.withDefaultPasswordEncoder().username("demo").password("demo").roles("USER").build());
    }
}
