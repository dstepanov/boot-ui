package io.github.jdubois.bootui.micronaut.email;

import io.github.jdubois.bootui.engine.email.CapturedAttachment;
import io.github.jdubois.bootui.engine.email.CapturedEmail;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.email.Attachment;
import io.micronaut.email.BodyType;
import io.micronaut.email.Contact;
import io.micronaut.email.Email;
import io.micronaut.email.TransactionalEmailSender;
import jakarta.inject.Singleton;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;

/**
 * Records every email the application sends into the shared engine {@link EmailCaptureService}, which backs
 * the Email Viewer panel.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code SentMail} observer. Micronaut publishes no
 * send event, so the sender bean is wrapped in a recording proxy as it is created — the same
 * {@link BeanCreatedEventListener} seam the SQL-trace binding uses, and the same one
 * {@code micronaut-liquibase} uses on datasources.
 *
 * <p>The wrapper is strictly pass-through: the message is recorded and then handed to the real sender
 * unchanged, and a capture failure is swallowed so it can never cost the application a delivery. Unlike
 * Spring's dev-trap, BootUI does not intercept the send — the mail really goes out, which is why the panel
 * reports these as sent.
 */
@RequiresBootUi
@Requires(classes = TransactionalEmailSender.class)
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class BootUiEmailCaptureListener implements BeanCreatedEventListener<TransactionalEmailSender<?, ?>> {

    private final EmailCaptureService captureService;

    public BootUiEmailCaptureListener(EmailCaptureService captureService) {
        this.captureService = captureService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public TransactionalEmailSender<?, ?> onCreated(BeanCreatedEvent<TransactionalEmailSender<?, ?>> event) {
        TransactionalEmailSender<?, ?> sender = event.getBean();
        if (sender == null) {
            return null;
        }
        try {
            return (TransactionalEmailSender<?, ?>) Proxy.newProxyInstance(
                    sender.getClass().getClassLoader(),
                    new Class<?>[] {TransactionalEmailSender.class},
                    new RecordingHandler(sender, captureService));
        } catch (RuntimeException ex) {
            // A sender that cannot be proxied is returned untouched: the panel loses a message, the
            // application does not lose its mail.
            return sender;
        }
    }

    /** Records the message, then delegates. Only {@code send} is observed; everything else passes through. */
    private record RecordingHandler(TransactionalEmailSender<?, ?> delegate, EmailCaptureService captureService)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("send".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof Email email) {
                capture(email);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
        }

        private void capture(Email email) {
            try {
                captureService.capture(CapturedEmail.builder()
                        .from(address(email.getFrom()))
                        .to(addresses(email.getTo()))
                        .cc(addresses(email.getCc()))
                        .bcc(addresses(email.getBcc()))
                        .subject(email.getSubject())
                        .textBody(body(email, BodyType.TEXT))
                        .htmlBody(body(email, BodyType.HTML))
                        .attachments(attachments(email))
                        .build());
            } catch (RuntimeException ex) {
                // Best-effort capture: never let a capture-side failure disrupt the app's mail pipeline.
            }
        }

        private static String body(Email email, BodyType type) {
            try {
                return email.getBody() == null
                        ? null
                        : email.getBody().get(type).orElse(null);
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private static String address(Contact contact) {
            return contact == null ? null : contact.getEmail();
        }

        private static List<String> addresses(Collection<Contact> contacts) {
            if (contacts == null || contacts.isEmpty()) {
                return List.of();
            }
            return contacts.stream()
                    .filter(contact -> contact != null)
                    .map(Contact::getEmail)
                    .toList();
        }

        /**
         * Attachment metadata only. The bytes are deliberately not captured: an attachment can be
         * arbitrarily large, and the panel exists to show what was sent to whom, not to store copies.
         */
        private static List<CapturedAttachment> attachments(Email email) {
            List<Attachment> attachments = email.getAttachments();
            if (attachments == null || attachments.isEmpty()) {
                return List.of();
            }
            return attachments.stream()
                    .filter(attachment -> attachment != null)
                    .map(attachment ->
                            new CapturedAttachment(attachment.getFilename(), attachment.getContentType(), null))
                    .toList();
        }
    }
}
