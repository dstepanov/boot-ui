package io.github.jdubois.bootui.micronaut.security;

import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.event.LoginFailedEvent;
import io.micronaut.security.event.LoginSuccessfulEvent;
import io.micronaut.security.event.LogoutEvent;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records micronaut-security's authentication events into the shared engine {@link SecurityEventBuffer}
 * behind the Security Logs panel.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusSecurityEventCapture}. Micronaut
 * publishes login and logout outcomes as ordinary application events, so one listener over the common
 * supertype is enough — no per-event registration and no security-internals coupling.
 *
 * <p>The bean exists only when {@code micronaut-security} is on the application's classpath. Without it
 * there are no events to observe, and the panel reports the honest reason rather than an empty list that
 * might be mistaken for "no failed logins".
 */
@RequiresBootUi
@Requires(classes = LoginSuccessfulEvent.class)
@Singleton
public class MicronautSecurityEventCapture implements ApplicationEventListener<Object> {

    private final SecurityEventBuffer buffer;
    private final BeanContext beanContext;

    public MicronautSecurityEventCapture(SecurityEventBuffer buffer, BeanContext beanContext) {
        this.buffer = buffer;
        this.beanContext = beanContext;
    }

    @Override
    public boolean supports(Object event) {
        return event instanceof LoginSuccessfulEvent
                || event instanceof LoginFailedEvent
                || event instanceof LogoutEvent;
    }

    @Override
    public void onApplicationEvent(Object event) {
        try {
            buffer.record(new CapturedSecurityEvent(
                    Instant.now(), principal(event), event.getClass().getSimpleName(), data(event), currentTraceId()));
        } catch (RuntimeException ex) {
            // Capture is best-effort: it must never break the application's authentication flow.
        }
    }

    /**
     * The subject of the event. A successful login and a logout carry the authenticated identity; a failed
     * login carries the rejected attempt, whose identity Micronaut deliberately does not always expose, so
     * it may legitimately be {@code null}.
     */
    private static String principal(Object event) {
        Object source = sourceOf(event);
        if (source instanceof Authentication authentication) {
            return authentication.getName();
        }
        return null;
    }

    /**
     * The event's own detail. Only the failure reason is surfaced, and never the submitted credentials: a
     * failed-login event carries the attempted secret, which must not reach a diagnostics panel even behind
     * masking.
     */
    private static Map<String, Object> data(Object event) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (event instanceof LoginFailedEvent failed) {
            data.put("outcome", "FAILED");
            Object reason = failed.getSource();
            if (reason != null) {
                data.put("reason", String.valueOf(reason));
            }
        } else if (event instanceof LoginSuccessfulEvent) {
            data.put("outcome", "SUCCEEDED");
        } else if (event instanceof LogoutEvent) {
            data.put("outcome", "LOGGED_OUT");
        }
        return data;
    }

    private static Object sourceOf(Object event) {
        if (event instanceof LoginSuccessfulEvent successful) {
            return successful.getSource();
        }
        if (event instanceof LogoutEvent logout) {
            return logout.getSource();
        }
        return null;
    }

    private String currentTraceId() {
        try {
            return beanContext
                    .findBean(TraceIdProvider.class)
                    .map(TraceIdProvider::currentTraceId)
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
