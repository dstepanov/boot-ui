package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the BootUI single-page application shell at the configured UI mount.
 *
 * <p>The compiled Vue assets ship inside {@code bootui-ui} at {@code META-INF/resources/bootui/} and are
 * served from the same mount by {@link BootUiAssetsController}. This controller answers the shell request
 * itself so it can inject the runtime paths the SPA needs: the generated
 * {@code index.html} references its assets relatively, so the browser base must be the console's own mount
 * and must end in a slash.
 *
 * <p>Rather than redirect the bare path (which a proxy that strips trailing slashes could turn into an
 * infinite loop), this controller answers both the bare and trailing-slash forms directly and injects a
 * {@code <base>} plus {@code bootui-api-path} / {@code bootui-application-path} metadata composed from
 * normalized configuration — read from {@code micronaut.server.context-path}, {@code bootui.path} and
 * {@code bootui.api-path}, never from the attacker-influenced request URI.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.UI)
public class MicronautIndexController {

    static final String INDEX_LOCATION = "META-INF/resources/bootui/index.html";

    private static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head[^>]*>");

    private static final Pattern EXISTING_BASE = Pattern.compile("(?i)<base\\b");

    private static final Pattern EXISTING_API_PATH =
            Pattern.compile("(?i)<meta\\b[^>]*\\bname=[\"']bootui-api-path[\"']");

    private static final Pattern EXISTING_APPLICATION_PATH =
            Pattern.compile("(?i)<meta\\b[^>]*\\bname=[\"']bootui-application-path[\"']");

    private final Environment environment;

    private volatile String cachedTemplate;

    public MicronautIndexController(Environment environment) {
        this.environment = environment;
    }

    @Get
    @Produces(MediaType.TEXT_HTML)
    public HttpResponse<String> index() {
        String baseHref = MicronautBootUiPaths.applicationUiPath(environment) + "/";
        String apiPath = MicronautBootUiPaths.applicationApiPath(environment);
        String applicationPath = MicronautBootUiPaths.applicationPath(environment, "/");
        return HttpResponse.ok(injectRuntimePaths(template(), baseHref, apiPath, applicationPath))
                .contentType(MediaType.TEXT_HTML_TYPE);
    }

    private String template() {
        String html = cachedTemplate;
        if (html == null) {
            html = readTemplate();
            cachedTemplate = html;
        }
        return html;
    }

    private static String readTemplate() {
        try (InputStream in = MicronautIndexController.class.getClassLoader().getResourceAsStream(INDEX_LOCATION)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("BootUI index.html not found at " + INDEX_LOCATION));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read BootUI index.html from " + INDEX_LOCATION, ex);
        }
    }

    /**
     * Inserts a {@code <base href>} as the first child of {@code <head>} so it precedes every relative
     * asset/API URL in the document. Returns the markup unchanged when it already declares a {@code <base>}
     * tag or has no {@code <head>}.
     */
    static String injectBaseHref(String html, String baseHref) {
        if (EXISTING_BASE.matcher(html).find()) {
            return html;
        }
        Matcher matcher = HEAD_OPEN.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        int insertAt = matcher.end();
        String baseTag = "\n    <base href=\"" + escapeAttribute(baseHref) + "\" />";
        return html.substring(0, insertAt) + baseTag + html.substring(insertAt);
    }

    static String injectRuntimePaths(String html, String baseHref, String apiPath, String applicationPath) {
        String rewritten = injectBaseHref(html, baseHref);
        rewritten = injectRuntimePath(rewritten, EXISTING_API_PATH, "bootui-api-path", apiPath);
        return injectRuntimePath(rewritten, EXISTING_APPLICATION_PATH, "bootui-application-path", applicationPath);
    }

    private static String injectRuntimePath(String html, Pattern existingPath, String name, String path) {
        if (existingPath.matcher(html).find()) {
            return html;
        }
        Matcher matcher = HEAD_OPEN.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        int insertAt = matcher.end();
        String meta = "\n    <meta content=\"" + escapeAttribute(path) + "\" name=\"" + name + "\" />";
        return html.substring(0, insertAt) + meta + html.substring(insertAt);
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
