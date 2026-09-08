/**
 * Email capture, which fills the Email Viewer panel.
 *
 * <p>The capture listener binds to {@code micronaut-email}, a {@code provided} dependency of this adapter,
 * so the package-level {@code @Requires} below keeps it out of an application that sends no mail. See the
 * Kafka package for the same rationale in full.
 */
@Configuration
@Requires(classes = TransactionalEmailSender.class)
package io.github.jdubois.bootui.micronaut.email;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.email.TransactionalEmailSender;
