package io.github.jdubois.bootui.micronaut.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.ClassIntrospector.MixInResolver;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

/**
 * Makes BootUI's own DTOs serialize every field, including empty collections and maps.
 *
 * <p>Micronaut's Jackson integration defaults {@code jackson.serialization-inclusion} to
 * {@code NON_EMPTY}, so on a default-configured application an empty {@code List} or {@code Map} property
 * is <em>omitted</em> from the JSON rather than written as {@code []} / <code>{}</code>. That silently
 * breaks the console: the shared Vue UI and the shared conformance contract both require a stable shape,
 * and a panel with nothing to report ("no traces yet", "no findings yet", "no warnings") is exactly the
 * case where its list is empty — so the fields most needed to render an empty state were the ones going
 * missing.
 *
 * <p>BootUI cannot fix this by setting {@code jackson.serialization-inclusion} itself: that key belongs to
 * the host application, and changing it would silently rewrite the application's own API responses. So the
 * override is scoped to BootUI's own types instead, through a Jackson mix-in resolver that answers only for
 * classes under {@value #BOOTUI_PACKAGE_PREFIX}. Every other class — every class the host application
 * serializes — falls through to the mapper's existing resolver and keeps the application's configured
 * inclusion policy exactly as it was.
 *
 * <p>{@code content} is set alongside {@code value} because Micronaut applies the configured inclusion to
 * both property values and container contents, so a BootUI DTO nested as a map value would otherwise still
 * lose its empty collections.
 *
 * <p><strong>This covers the databind stack only.</strong> {@code micronaut-serde-jackson} publishes no
 * Jackson {@code ObjectMapper} bean, so this listener never fires there — which is why it is conditioned on
 * the class being present. Serde defaults {@code serde.serialization.inclusion} to {@code NON_EMPTY} and
 * would drop the very same fields, so the identical policy is pinned on that stack at compile time instead,
 * by the {@code AlwaysInclude} mix-in every {@code @SerdeImport} in
 * {@code io.github.jdubois.bootui.micronaut.serde.BootUiSerdeImports} carries. Both halves are asserted on
 * the raw wire bytes: {@code BootUiJsonInclusionContractTest} exists once in this module (Serde) and once in
 * {@code bootui-micronaut-sample-app} (databind).
 *
 * <p>Like every other console bean this is gated on {@link RequiresBootUi}: a production run creates no
 * listener at all and the application's {@code ObjectMapper} is never touched.
 */
@RequiresBootUi
@Requires(classes = ObjectMapper.class)
@Singleton
public class BootUiJsonInclusionCustomizer implements BeanCreatedEventListener<ObjectMapper> {

    /** Every BootUI-owned type: the shared core DTOs, the engine's records, and the adapter's own. */
    static final String BOOTUI_PACKAGE_PREFIX = "io.github.jdubois.bootui.";

    @Override
    public ObjectMapper onCreated(BeanCreatedEvent<ObjectMapper> event) {
        ObjectMapper mapper = event.getBean();
        mapper.setMixInResolver(new BootUiMixInResolver());
        return mapper;
    }

    /**
     * Answers with the always-include mix-in for BootUI's own classes and {@code null} for everything else.
     *
     * <p>{@code ObjectMapper.setMixInResolver} installs this as an <em>override</em> resolver consulted
     * before the mapper's explicitly registered mix-ins, and a {@code null} answer defers to them — so this
     * adds a rule for BootUI's classes without removing or shadowing any rule the application registered.
     */
    static final class BootUiMixInResolver implements MixInResolver {

        @Override
        public Class<?> findMixInClassFor(Class<?> target) {
            return target != null && target.getName().startsWith(BOOTUI_PACKAGE_PREFIX) ? AlwaysInclude.class : null;
        }

        @Override
        public MixInResolver copy() {
            return new BootUiMixInResolver();
        }
    }

    /** The mix-in itself: carries nothing but the inclusion policy BootUI's wire contract requires. */
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
    abstract static class AlwaysInclude {}
}
