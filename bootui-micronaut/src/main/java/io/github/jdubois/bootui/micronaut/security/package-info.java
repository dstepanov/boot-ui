/**
 * Micronaut Security integration: the console's own access rule and the login/logout capture that fills the
 * Security Logs panel.
 *
 * <p>Both beans bind to {@code micronaut-security}, a {@code provided} dependency of this adapter, so the
 * package-level {@code @Requires} below keeps them out of an application that does not secure itself with
 * it. See the Kafka package for the same rationale in full.
 *
 * <p>{@code micronaut.security.enabled=false} switches {@code micronaut-security} itself off. The security
 * rule is then never consulted and no login event is ever published, so both beans follow that switch
 * rather than sitting inert on the classpath.
 *
 * <p>{@link MicronautSecurityPresence} is deliberately not a bean and deliberately probes by class name: the
 * Security Logs panel has to answer "is micronaut-security here?" from code that must load when it is not.
 */
@Configuration
@Requires(classes = SecurityRule.class)
@Requires(property = "micronaut.security.enabled", notEquals = StringUtils.FALSE)
package io.github.jdubois.bootui.micronaut.security;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.security.rules.SecurityRule;
