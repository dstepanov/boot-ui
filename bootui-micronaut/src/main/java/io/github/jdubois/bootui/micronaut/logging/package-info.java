/**
 * Logback capture, which fills the Log Tail and Exceptions panels.
 *
 * <p>Logback is a {@code provided} dependency of this adapter — Micronaut applications use it by default,
 * but an application on another SLF4J backend has none of it. {@link BootUiLogbackCapture} is
 * {@code @Context}-scoped, so it is instantiated during startup rather than on first request; without the
 * package-level {@code @Requires} below it would be created on such an application and its
 * {@code @PostConstruct} would fail with {@code NoClassDefFoundError} before the console ever served a
 * request. Declaring the guard here means the bean is simply never created, which is the honest degradation
 * the class documents: both panels render empty and the application boots normally.
 *
 * <p>The two appenders in this package are plain classes rather than beans, so they are unaffected by this
 * declaration; they are only ever constructed once the capture bean has resolved a live Logback context.
 */
@Configuration
@Requires(classes = LoggerContext.class)
package io.github.jdubois.bootui.micronaut.logging;

import ch.qos.logback.classic.LoggerContext;
import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
