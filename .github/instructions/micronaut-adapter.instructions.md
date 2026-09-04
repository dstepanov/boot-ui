---
applyTo: "pom.xml,bootui-micronaut-parent/**,bootui-micronaut/**,bootui-micronaut-sample-app/**,docs/MICRONAUT-SUPPORT.md,docs/setup/micronaut.md"
---

# Micronaut adapter

- Use the `micronaut.platform.version` property in the root POM as the compatibility source of truth. A nearer Micronaut
  BOM under `bootui-micronaut-parent` must override the root Spring Boot BOM for Micronaut modules. `micronaut.core.version`
  is a separate literal because `annotationProcessorPaths` cannot inherit a managed version.
- Keep controllers and providers thin: reusable behavior belongs in the engine, behind the neutral SPI ports. Micronaut
  resolves DI at compile time, so read bean, route, configuration and base-package metadata live from the context rather
  than inventing a build-time capture step.
- Every console bean carries `@RequiresBootUi`. The single exception is `BootUiProdShellGuardFilter`, which is always
  registered and decides at construction — that is what keeps a non-development run dark, including the packaged SPA
  assets, and it must stay easy to audit.
- Activation is `BootUiMicronautActivationResolver` over Micronaut environments, at parity with the Spring adapter's
  profile logic. It fails closed: an unknown `bootui.enabled` value or no matching environment leaves the console dark.
- `BootUiMicronautSafetyFilter` and `MicronautPanelAccessFilter` are thin bindings over shared engine policy. Preserve
  their order (safety first), their canonical JSON errors, and the fact that safety matches `/**` so an unmatched write
  under the console surface is still rejected rather than answered with a bare 404.
- Read live policy from the `Environment` and fail closed on missing or invalid values. Use `BootUiBooleans` for every
  safety-relevant boolean: Micronaut's own conversion turns an invalid string into `false`, which would widen access.
- Controllers bind their mounts from `bootui.path` / `bootui.api-path` placeholders, so the console surface is exactly
  the configured mounts — there is no reserved internal `/bootui` on Micronaut, and no guard, capture point or security
  rule may claim one. A placeholder default cannot derive from another property, so `BootUiApiPathConfigurer`
  contributes `bootui.api-path` = `<bootui.path>/api` from an `ApplicationContextConfigurer`, before bean definitions
  load; `BootUiPathsValidator` is normalization only.
- Optional integrations must be safe when absent: compile them as `provided`, guard them with a class-presence check or
  a bean condition, and report the panel unavailable with an honest reason rather than failing.
- The adapter must not dictate the host's JSON stack: it depends on neither `micronaut-serde-jackson` nor
  `micronaut-jackson-databind`, and works under both. Serde needs a compile-time introspection for every type on the
  wire, so `BootUiSerdeImports` declares a `@SerdeImport` for each one — add a line there for every new core DTO or
  controller request record, copying an existing line so it carries `mixin = AlwaysInclude.class`, or
  `BootUiSerdeImportsTest` fails the build. That mix-in is not decoration: both JSON stacks default to a `NON_EMPTY`
  inclusion policy that drops empty collections, empty maps and nulls, and it is what pins BootUI's
  write-every-field wire contract under Serde, as `BootUiJsonInclusionCustomizer` does under databind. Never hand a
  type the host's mapper owns (a Jackson `JsonNode`, a raw `Environment` property value) straight to a response
  body; serialize it here instead.
- Update `MicronautPanelAvailability` and `docs/MICRONAUT-SUPPORT.md` whenever panel support changes. Distinguish
  unavailable, not-yet-supported, and not-applicable states honestly.
- Focused adapter validation: `./mvnw -B -ntp -pl bootui-micronaut,bootui-micronaut-sample-app -am install`.
