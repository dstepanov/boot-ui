package io.github.jdubois.bootui.micronaut.serde;

import io.github.jdubois.bootui.micronaut.web.LoggersController;
import io.github.jdubois.bootui.micronaut.web.McpServerController;
import io.micronaut.serde.annotation.SerdeImport;

/**
 * Declares a Micronaut Serde introspection for every type the BootUI console puts on the wire, so the
 * adapter serializes correctly under {@code micronaut-serde-jackson} as well as under
 * {@code micronaut-jackson-databind}.
 *
 * <p><strong>Why this class exists.</strong> The shared core DTOs are immutable records with no annotations
 * at all — a deliberate architecture invariant, because {@code bootui-core} is consumed by the Spring
 * (Jackson 3), Quarkus (Jackson 2) and Micronaut adapters and must never depend on a JSON library. Jackson
 * databind is happy with that: it reflects over the record at runtime. Micronaut Serde is not, and cannot
 * be — it is reflection-free by design and writes only types for which the compiler produced a
 * {@code BeanIntrospection}. Without those introspections every {@code /bootui/api/**} response failed with
 * <em>"No serializable introspection present for type … Consider adding @Serdeable"</em>, which made the
 * console unusable on the JSON stack a new Micronaut 4 application gets by default.
 *
 * <p>{@link SerdeImport} is the escape hatch built for exactly this shape of problem: it makes an external
 * type serializable from the outside, without touching the type. Declaring the imports here — in the
 * adapter, the only module that knows a JSON stack exists — keeps {@code bootui-core} annotation-free while
 * {@code micronaut-serde-processor} generates the introspections into this module's jar at compile time.
 *
 * <p><strong>Why the whole DTO package, not just the types the controllers name.</strong> A handful of
 * endpoints answer {@code HttpResponse<?>}, and the CLI bridge answers a tool payload typed {@code Object},
 * so the set of DTOs reachable through method signatures is not an upper bound on what actually gets
 * serialized. The rule is therefore: <em>every public type in {@code io.github.jdubois.bootui.core.dto} is
 * imported</em>, plus the request records the controllers declare for their own bodies.
 *
 * <p><strong>Why one package import rather than a list.</strong> {@link SerdeImport#packageName()} imports
 * every public, non-nested type in a package, applying the same mix-in to each — so the rule above is
 * declared rather than re-derived, and a new DTO is covered the moment it is written. Enumerating the types
 * one per line stated the same thing in ~280 lines that had to be maintained by hand, and a missing line
 * failed at request time, not build time. Two request records stay explicit because they are nested in their
 * controllers, which {@code packageName} deliberately skips. Sub-packages are skipped too, so a new package
 * under {@code core.dto} would need its own line here; {@code BootUiSerdeImportsTest} fails the build if one
 * appears, and if any DTO ends up without a generated introspection.
 *
 * <p><strong>Cost when the host is on Jackson databind.</strong> None that is observable.
 * {@code micronaut-serde-api} is a {@code provided} dependency, so it is not forced onto the application;
 * the annotations below are then simply not loadable at runtime, which the JVM ignores, and the generated
 * introspections sit unused next to Jackson's reflective path. The wire bytes are unchanged either way,
 * which is what keeps the shared Vue UI and the conformance contract identical across all four stacks.
 *
 * <p><strong>Why every import carries {@link AlwaysInclude}.</strong> An introspection alone makes a DTO
 * writable; it does not decide <em>which</em> of its fields get written. Serde's global
 * {@code serde.serialization.inclusion} defaults to {@code NON_EMPTY}, which would drop exactly the empty
 * lists, empty maps and nulls the console's wire contract requires. The mix-in pins the always-include
 * policy onto each imported type at compile time, scoped to BootUI's own DTOs so the host application's
 * inclusion setting still governs its own responses — the Serde counterpart of the runtime Jackson mix-in
 * resolver {@link io.github.jdubois.bootui.micronaut.json.BootUiJsonInclusionCustomizer} installs on the
 * databind stack. {@code BootUiSerdeImportsTest} fails the build if an import is missing it.
 *
 * <p>The class is never instantiated and wires up no bean: it is a compile-time declaration only.
 */
@SerdeImport(packageName = "io.github.jdubois.bootui.core.dto", mixin = AlwaysInclude.class)
@SerdeImport(value = LoggersController.LevelUpdateRequest.class, mixin = AlwaysInclude.class)
@SerdeImport(value = McpServerController.ToggleRequest.class, mixin = AlwaysInclude.class)
public final class BootUiSerdeImports {

    private BootUiSerdeImports() {}
}
