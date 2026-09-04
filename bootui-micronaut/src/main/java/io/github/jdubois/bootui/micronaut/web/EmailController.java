package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailEmlRenderer;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import java.nio.charset.StandardCharsets;

/**
 * Controller for the Email Viewer panel ({@code GET /bootui/api/email}, a message detail, its {@code .eml}
 * download and the clear action).
 *
 * <p>A thin transport adapter over the shared engine {@link EmailCaptureService}, filled by
 * {@code BootUiEmailCaptureListener}. When the application has no Micronaut Email sender the panel says so
 * rather than showing an empty inbox that could be mistaken for "nothing was sent".
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/email")
public class EmailController {

    static final String EMAIL_ABSENT_REASON = "No Micronaut Email sender is present";

    private static final String EML_CONTENT_TYPE = "message/rfc822";

    private final EmailCaptureService service;
    private final boolean emailPresent;
    private final int maxEntries;

    public EmailController(EmailCaptureService service, Environment environment) {
        this.service = service;
        this.emailPresent = isPresent();
        this.maxEntries = environment
                .getProperty("bootui.email.max-entries", Integer.class)
                .orElse(100);
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public EmailsReport list() {
        if (!emailPresent) {
            return EmailsReport.unavailable(EMAIL_ABSENT_REASON, maxEntries);
        }
        return service.list();
    }

    @Get("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<EmailMessageDto> detail(@PathVariable String id) {
        EmailMessageDto message = find(id);
        return message == null ? HttpResponse.notFound() : HttpResponse.ok(message);
    }

    @Get("/{id}/eml")
    @Produces(EML_CONTENT_TYPE)
    public HttpResponse<byte[]> download(@PathVariable String id) {
        EmailMessageDto message = find(id);
        if (message == null) {
            return HttpResponse.notFound();
        }
        byte[] bytes = EmailEmlRenderer.render(message).getBytes(StandardCharsets.UTF_8);
        return HttpResponse.ok(bytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"email-" + id + ".eml\"")
                .contentType(EML_CONTENT_TYPE);
    }

    @Delete
    public HttpResponse<?> clear() {
        service.clear();
        return HttpResponse.noContent();
    }

    private EmailMessageDto find(String id) {
        return emailPresent ? service.get(id) : null;
    }

    /** Whether Micronaut Email is on the classpath at all; without it nothing can ever be captured. */
    private static boolean isPresent() {
        try {
            Class.forName("io.micronaut.email.TransactionalEmailSender", false, EmailController.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
