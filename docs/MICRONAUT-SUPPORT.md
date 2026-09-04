# Micronaut support — design notes

These notes are for people working on BootUI itself. They record how the Micronaut adapter is built, which
decisions differ from the Spring and Quarkus adapters and why, and what is deliberately not there yet. For
what a user sees, read [Micronaut setup](./setup/micronaut.md) and [Framework support](./FRAMEWORK-SUPPORT.md).

## Shape of the adapter

`bootui-micronaut` is the Micronaut binding over the framework-neutral engine — the analogue of
`bootui-spring-autoconfigure` and of `bootui-quarkus`. It follows the same architecture invariant: the engine
stays framework-, DI- and JSON-free, and everything Micronaut-specific lives here behind the neutral SPI ports
in `io.github.jdubois.bootui.spi`.

There is no deployment/augmentation half. Micronaut resolves dependency injection at compile time through its
own annotation processor, so an adapter needs no build-time capture step of its own: the metadata the Quarkus
extension has to capture into Jandex at build time — bean definitions and their injection edges, the route
table, the application's base packages — is all readable from the running context.

| Concern | Spring | Quarkus | Micronaut |
| --- | --- | --- | --- |
| Wiring | `@Bean` in an auto-configuration | `@Produces` in a CDI producer | `@Singleton` in a `@Factory` |
| Activation | `BootUiActivationCondition` over profiles | `LaunchMode != NORMAL` build-step gate | `BootUiEnabledCondition` over environments |
| Endpoints | Spring MVC / WebFlux controllers | JAX-RS resources | `@Controller` classes |
| Guards | servlet / WebFlux filters | Vert.x global route filters | `@ServerFilter` beans |
| Bean inventory | Actuator `BeansEndpoint` | Arc + build-time edge capture | `BeanContext` + `BeanDefinition` |
| Route inventory | Actuator `MappingsEndpoint` | build-time Jandex capture | live `Router` |
| Configuration | `Environment` property sources | SmallRye `Config` | `Environment` property sources |
| Loggers | Actuator `LoggersEndpoint` | JBoss LogManager | Logback `LoggerContext` |
| Dependency inventory | `DependencyCatalog` classpath scan | build-time application model | `java.class.path` + classloader scan |
| JSON for the shared GitHub/OSV clients | Jackson 3 `SpringJsonCodec` | Jackson 2 `QuarkusJsonCodec` | Jackson 2 `MicronautJsonCodec` |

The last row is the whole of what this adapter owns for the GitHub and Vulnerabilities panels. The GitHub REST
client and the OSV scanner are shared `bootui-engine` code (`GitHubApiClient`, `OsvVulnerabilityScanner`); they
read third-party JSON through the neutral `JsonCodec`/`JsonTree` SPI, so what is left per adapter is a thin
Jackson wrapper plus mapping `bootui.github.*` / `bootui.vulnerabilities.*` onto the engine's settings records
(`MicronautGitHubSettings`, `MicronautVulnerabilitySettings`).

## Decisions worth knowing

**Activation is environment-based and fails closed.** Micronaut deduces the `test` environment when the
application starts under JUnit or Spock, but it deduces nothing for an ordinary run — there is no analogue of
Quarkus' dev launch mode. So the default enabled set is `dev`, `local` and `test`, mirroring the Spring
adapter's `BootUiDefaults.ENABLED_PROFILES` plus the deduced test environment, and an application that
declares no environment is dark. That is the safe direction: an operator opts in with
`micronaut.environments=dev` or `bootui.enabled=ON`, and never the other way around.

**The console mounts are configuration placeholders, not a rewrite filter.** Quarkus pins its resources to a
fixed `/bootui` mount and reroutes requests from the configured mount; Micronaut resolves a `@Controller` path
from configuration at startup, so the controllers bind directly to `bootui.path` / `bootui.api-path` and a
custom mount costs nothing per request. There is consequently **no reserved internal mount on Micronaut**: with
`bootui.path=/console` nothing of BootUI is served under `/bootui`, and every guard, capture point and the
`micronaut-security` rule claims exactly the configured mounts — claiming `/bootui` on top of them would hijack
whatever the application itself serves there.

The one wrinkle is that a Micronaut placeholder default cannot be derived from another property: the resolver
does not evaluate a nested `${...}` inside a default, so `${bootui.api-path:${bootui.path:/bootui}/api}` yields
the literal `/bootui/api}`. `BootUiApiPathConfigurer` — an `ApplicationContextConfigurer`, which Micronaut runs
after the environment has started and before any bean definition is loaded — contributes
`bootui.api-path` = `<bootui.path>/api` as a real property, at a precedence below every application property
source and only when the key is unset. So the API mount follows a moved UI mount exactly as it does on Spring
and Quarkus, and `MicronautBootUiPaths.apiPath` agrees with what the controllers bound to.
`BootUiPathsValidator` is left with what it should have been all along: normalization, failing fast on an
invalid mount.

**The SPA is served by a controller, not by `micronaut.router.static-resources`.** Micronaut's static-resource
support is configuration-driven, which would make BootUI a two-step install and could not follow a custom
`bootui.path`. `BootUiAssetsController` serves the packaged bundle instead, rejecting any traversal or absolute
path before a lookup so nothing outside the bundle is reachable.

**Booleans are parsed strictly.** Micronaut's own conversion turns an unrecognized string into `false`. For
switches whose safe value is `true` — `bootui.mask-secrets`, `bootui.panels.*.read-only`, `bootui.read-only` —
that would silently widen access on a typo, so `BootUiBooleans` parses these values itself and falls back to
each key's documented default, warning about the invalid value.

**The inventory panels describe the application, and Micronaut makes that three separate decisions.** Every
panel's subject is the host application, never the console or the framework, and on Micronaut each of the
three inventories reaches that answer differently.

- *Beans.* The self-filter is scoped to `io.github.jdubois.bootui.micronaut` and `…core` rather than the whole
  `io.github.jdubois.bootui` tree, so an application that happens to live under that root package is not
  swallowed by the console it embeds. But BootUI's console is assembled by `@Factory` classes whose
  `@Singleton` methods return framework-neutral `bootui-engine` and `spi` types, which that scope does not
  cover — some fifty of them, `beansService` and `apiTokenAuthenticator` among them. Micronaut records the
  producing factory on a factory-built definition, so `MicronautBeanTypes.isBootUiOwned` hides a bean when
  either its type *or* its `BeanDefinition.getDeclaringType()` is in the adapter's packages. That is the
  Micronaut form of the Spring adapter's `isBootUiBean(beanName, type, resource)`, and it is shared by all
  four inventories that walk the container (Beans, Scheduled Tasks, Fault Tolerance, error contract) so they
  cannot drift.
- *Mappings.* Micronaut registers a generated `HEAD` route beside every route that answers `GET` — `@Get`
  does it unless the method sets `headRoute = false`, and the management endpoints' `@Read` does it
  unconditionally — so a naive listing reports each endpoint twice. The panel inventories declarations, and
  Micronaut publishes no flag for the generated route, so `MicronautMappingProvider` identifies it
  structurally, exactly as the two route builders create it: a `HEAD` route that does not itself declare
  `@Head` and whose path, declaring class and target method are shared with a `GET` route. An explicit
  `@Head` route survives.
- *Error contract.* Unlike Spring's `@ControllerAdvice` (of which the framework declares none) and Quarkus'
  application-archive index, Micronaut ships its own error contract as ordinary `ExceptionHandler` beans, so
  a plain application hands the container a dozen framework handlers — `JsonExceptionHandler`,
  `ConversionErrorHandler` and the rest. `ErrorHandlerDescriptor` is the frozen neutral SPI record and has no
  field for marking an entry framework-provided, so the engine could not group them either; handlers whose
  declaring type is under `io.micronaut.` are therefore excluded, as BootUI's own already are, and the
  catalogue is the application's alone.

**Architecture rules treat Micronaut like Spring, not like Quarkus.** Micronaut generates interception
subclasses at compile time, so a `private`, `static` or `final` method cannot be advised — the same
proxyability bar as Spring, rather than Arc's bytecode-transformation semantics. `ArchitecturePlatform.MICRONAUT`
therefore deliberately does not share Quarkus' relaxed visibility branch.

**The dependency inventory reads the classpath twice, and reads both Maven metadata files.** Micronaut resolves
nothing about dependencies at build time, so `MicronautDependencyProvider` derives the Vulnerabilities panel's
inventory from what the JVM actually loaded. It reads `java.class.path` *and* the application classloader: under
an in-process launcher such as `mvn exec:java`, `java.class.path` describes the launcher and not the
application, which used to make the panel report a single, confidently wrong entry
(`org.apache.maven.wrapper:maven-wrapper`). Each jar's coordinate comes from
`META-INF/maven/<group>/<artifact>/pom.properties` where there is one and from the `pom.xml` beside it where
there is not — Maven writes the properties file, Gradle writes only the POM, and Micronaut itself is published
from Gradle, so a properties-only reader omitted the framework the application runs on. A jar with neither
contributes nothing rather than a guessed coordinate.

**Both Micronaut JSON stacks are supported, and the adapter brings neither.** `micronaut-serde-jackson` (the
default for a new Micronaut 4 application) and `micronaut-jackson-databind` both work, unchanged; BootUI must not
dictate the host's JSON stack, so neither is a dependency of `bootui-micronaut`.

Serde is the harder of the two, and the reason is architectural rather than accidental. Core DTOs are immutable,
annotation-free records — `bootui-core` is shared by three adapters across two incompatible Jackson generations
and may never depend on a JSON library. Jackson databind reflects over such a record happily. Serde is
reflection-free by design and writes only types the compiler produced a `BeanIntrospection` for, so every
`/bootui/api/**` response used to fail with *"No serializable introspection present … Consider adding
@Serdeable"*.

The fix keeps both invariants: `BootUiSerdeImports`, in the adapter, declares a `@SerdeImport` for every record
the API can put on the wire, and `micronaut-serde-processor` — on the compiler's `annotationProcessorPaths`, not
on anyone's runtime classpath — generates the introspections into BootUI's own jar. `micronaut-serde-api` is a
`provided` dependency, so an application on databind is never dragged onto Serde; its `@SerdeImport` annotations
are then simply not loadable at runtime, which the JVM ignores, and the generated introspections sit unused.

Four consequences worth knowing:

- **The imported set is the whole `core.dto` package, not just what the controllers name.** A few endpoints answer
  `HttpResponse<?>` and the CLI bridge answers an `Object`-typed tool payload, so signature reflection is not an
  upper bound on what gets serialized. `BootUiSerdeImportsTest` enforces the package rule plus a reachability
  closure over record components, and fails the build when a DTO is added without an import — Serde's own failure
  would otherwise arrive as a 500 the first time a user opens the panel.
- **The MCP transport serializes itself.** `McpBridgeController` used to answer a Jackson `JsonNode` body, which
  Serde cannot write. `MicronautMcpEnvelope` now owns a private `ObjectMapper` — it also injected the
  application's, a bean that does not exist under Serde — and renders the JSON-RPC envelope to bytes the
  controller writes directly. The protocol's wire shape is now the same on every host, and immune to an
  application's own Jackson customisation.
- **Configuration values are reduced to the contract's own types.** `Environment.getProperty(key, Object.class)`
  can return any object at all — a `Class`, for one, which a stock Micronaut context really does hold.
  `MicronautConfigProvider` normalizes to strings, numbers, booleans, lists and maps before the value reaches the
  DTO, which is both what Serde needs and what makes the Configuration panel consistent with Spring and Quarkus.
- **Every field is written, on both stacks.** Micronaut defaults `jackson.serialization-inclusion` to `NON_EMPTY`
  under databind, and Serde defaults `serde.serialization.inclusion` to the same thing, so an empty list or map
  and a `null` are dropped rather than written as `[]` / `{}` / `null` — breaking the wire contract on exactly the
  panels with nothing to report. Neither setting may be changed: both belong to the host application. So the
  policy is overridden for BootUI's own types only, twice, because the two stacks share no serialization code:
  `BootUiJsonInclusionCustomizer` installs a Jackson mix-in resolver scoped to `io.github.jdubois.bootui.*` at
  runtime under databind, and every `@SerdeImport` carries `mixin = AlwaysInclude.class` — a mix-in with
  `@JsonInclude(ALWAYS, content = ALWAYS)`, which `micronaut-serde-processor` translates into
  `@SerdeConfig(include = ALWAYS, includeContent = ALWAYS)` — at compile time under Serde. `BootUiSerdeImportsTest`
  fails the build if an import is missing the mix-in.

Coverage is split across two modules because the two stacks cannot share a classpath — each publishes its own
`JsonMapper` and message-body handlers. `bootui-micronaut`'s own tests run under `micronaut-serde-jackson` (the
stricter stack, so the smoke walk over every live panel doubles as the completeness proof);
`bootui-micronaut-sample-app` declares `micronaut-jackson-databind` and runs the same assertions there. Both
modules run the shared, framework-neutral API conformance suite — `MicronautSerdeApiConformanceTest` on Serde,
`MicronautApiConformanceTest` on databind — so "one wire contract, either JSON stack" is a checked claim rather
than a hope, and both pin the inclusion contract on the raw response bytes in a `BootUiJsonInclusionContractTest`.

## What is available

`MicronautPanelAvailability` is the single source of truth, and the manifest at `/bootui/api/panels` reports
it with `platform: "micronaut"`. Three groups:

**Always live** — Overview, Beans, Mappings, Configuration (read-only), Loggers, Health, Metrics, Threads,
Heap Dump, Live Memory, JVM Tuning, HTTP Probe, HTTP Exchanges, Log Tail, Exceptions, Profile Diff,
Security Logs, Live Activity (including its service map), Traces, AI Framework, SQL Trace, REST Client,
Vulnerabilities, MCP Server, Command Line, and the Memory, Architecture, REST API, Pentesting and Database
advisors.

**Live when the integration is present** — Database Connection Pools (HikariCP), Cache
(`micronaut-cache-*`), Hibernate and Hibernate Statistics (Hibernate ORM), WebSockets
(`micronaut-websocket`), Fault Tolerance (`micronaut-retry`), Email (`micronaut-email`). Each names its
missing dependency when it is dark.

**Live when the integration is configured** — Flyway and Liquibase need more than `micronaut-flyway` /
`micronaut-liquibase` on the classpath. Each lights up only when at least one enabled
`flyway.datasources.<name>` / `liquibase.datasources.<name>` (with a `change-log`) configuration is backed by
a `DataSource` bean of the same name — the Micronaut analogue of the Spring adapter's "a `Flyway` /
`SpringLiquibase` bean exists" rule. The manifest reads that decision from the very provider the engine
services run on, so it never advertises a panel whose report would say `flywayPresent: false` and whose
actions would answer 404. A library that is present but unconfigured names the missing configuration rather
than the missing dependency.

**Live when the environment allows** — GitHub (a git checkout with a GitHub origin), Copilot and Claude Code
(their session directories exist).

### How each one is bound

Nothing needs a build-time capture step. The bean container, route table, configuration and base packages
are read live; `@Scheduled`, `@Error`, `ExceptionHandler`, `@Retryable`/`@CircuitBreaker` and
`@ServerWebSocket` inventories come from compile-time bean metadata; and the capture points hang off seams
Micronaut already publishes — a server filter for HTTP exchanges, Logback appenders for the Log Tail and
Exceptions, security and retry and WebSocket application events, a `@ClientFilter` for outbound calls, and
`BeanCreatedEventListener` wrappers for datasources (SQL Trace) and the email sender.

Three limits are reported honestly rather than papered over:

- **WebSocket frame capture** is unsupported. Micronaut binds messages directly to the annotated handler at
  compile time and exposes no interception seam, so the panel records connection open/close only.
- **Live circuit-breaker state** is not claimed. Micronaut's breaker keeps state inside its generated
  interceptor, so the panel reports the configured policy and learns transitions by observing the
  framework's own circuit events.
- **Cache statistics and cache access edges** are reported unavailable. Micronaut's cache abstraction has no
  statistics API, and its `@Cacheable` advice exposes no access hook, so neither the Cache panel nor the
  service map invents them.

## What is not applicable

Nine panels have no Micronaut analogue and are permanently marked not applicable, each with its own reason in
`MicronautPanelAvailability`: **GraalVM** and **CRaC** (Micronaut generates its own reachability metadata and
starts fast by design), **Conditions** and **Startup Timeline** (wiring is resolved at compile time, so there
is no runtime condition graph or step timeline), **Spring Security** and **Spring Data** (Micronaut uses
`micronaut-security` and Micronaut Data), **Spring DevTools**, **HTTP Sessions** (stateless by default), and
**Transactions** (boundary capture needs a Spring hook Micronaut's transaction manager does not expose).

## What is not ported yet

Five panels remain, each reported honestly as *not yet available*:

- **Kafka** and **RabbitMQ**. The engine's activity recorders are already wired (which is why the Live
  Activity timeline handles their absence rather than failing), but neither has a Micronaut capture point
  yet. Micronaut Kafka publishes no send/receive event, so capture would mean contributing Kafka's own
  `interceptor.classes` as a default property source and reaching the recorder from an interceptor Kafka
  instantiates reflectively — workable, but it deserves its own change.
- **Dev Services**. The Micronaut analogue is Micronaut Test Resources; reading which containers it started
  needs its client, and the panel should report resolved resources rather than guess.
- **Security advisor** and the platform **application advisor** (`spring` panel id). Both need a Micronaut
  ruleset written in the engine — `micronaut-security` configuration for the former, Micronaut idioms for
  the latter — rather than an adapter binding. The MCP catalog already excludes both from the Micronaut
  stack, so no agent is offered a tool that cannot run.

The shared `bootui-conformance` suite now has a Micronaut runner: `bootui-micronaut-sample-app` runs the API,
CLI and MCP contracts against `Runtime.MICRONAUT` with an `expected-panels-micronaut.json` manifest, alongside
the Spring and Quarkus runners. The five panels above are scoped out of the contracts they cannot satisfy
(`BootUiApiContractCatalog.NON_MICRONAUT`), mirroring `McpToolCatalog.NON_MICRONAUT_STACKS`, rather than left
failing.
