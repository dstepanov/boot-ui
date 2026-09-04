package io.github.jdubois.bootui.micronaut.serde;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The mix-in every {@link io.micronaut.serde.annotation.SerdeImport} in {@link BootUiSerdeImports} carries:
 * it pins BootUI's wire contract — write every field, including empty collections, empty maps and nulls —
 * onto the imported type without touching the type itself.
 *
 * <p><strong>Why it is needed.</strong> Micronaut Serde's global serialization inclusion
 * ({@code serde.serialization.inclusion}) defaults to {@code NON_EMPTY}, so on a default-configured
 * application an empty {@code List} or {@code Map} property is <em>omitted</em> from the JSON rather than
 * written as {@code []} / <code>{}</code>, and a {@code null} property disappears entirely. That silently
 * breaks the console: the shared Vue UI and the shared conformance contract both require a stable shape, and
 * a panel with nothing to report ("no traces yet", "no findings yet") is exactly the case where its list is
 * empty. This is the same contract bug {@link io.github.jdubois.bootui.micronaut.json.BootUiJsonInclusionCustomizer}
 * fixes on the Jackson-databind stack, where Micronaut's {@code jackson.serialization-inclusion} default is
 * {@code NON_EMPTY} for the same reason.
 *
 * <p><strong>Why a mix-in rather than a setting.</strong> {@code serde.serialization.inclusion} belongs to
 * the host application, and changing it would silently rewrite the application's own API responses. A mix-in
 * is scoped to exactly the types named in {@code BootUiSerdeImports} — BootUI's own DTOs — so every other
 * class the application serializes keeps its configured inclusion policy untouched. It is the Serde analogue
 * of the Jackson mix-in resolver the databind customizer installs, arrived at from the opposite direction:
 * Jackson resolves mix-ins at runtime by class, Serde merges them at compile time into the generated
 * introspection.
 *
 * <p><strong>Why {@code content} as well as {@code value}.</strong> {@code value} governs the property
 * itself and {@code content} the entries of a container property, and Serde reads them as two independent
 * members ({@code SerdeConfig.include} and {@code SerdeConfig.includeContent}). Without {@code content} a
 * {@code null} inside a {@code Map} value — a nullable per-key report, for instance — would still be dropped.
 *
 * <p>Jackson's own {@code @JsonInclude} is the annotation here, not a Serde-native one, because Serde reads
 * it: {@code micronaut-serde-processor}'s {@code JsonIncludeMapper} translates it into
 * {@code @SerdeConfig(include = ALWAYS, includeContent = ALWAYS)} at compile time. Using it keeps the two
 * stacks stating the same intent in the same words, and costs nothing at runtime — the annotation is only
 * ever read by the annotation processor.
 */
@JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
abstract class AlwaysInclude {}
