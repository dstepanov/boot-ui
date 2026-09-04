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
custom mount costs nothing per request. The trade-off is that a placeholder default cannot be derived from
another property, so `bootui.api-path` does not follow `bootui.path` automatically. `BootUiPathsValidator`
fails fast at startup on that combination rather than serving a console whose API is somewhere else.

**The SPA is served by a controller, not by `micronaut.router.static-resources`.** Micronaut's static-resource
support is configuration-driven, which would make BootUI a two-step install and could not follow a custom
`bootui.path`. `BootUiAssetsController` serves the packaged bundle instead, rejecting any traversal or absolute
path before a lookup so nothing outside the bundle is reachable.

**Booleans are parsed strictly.** Micronaut's own conversion turns an unrecognized string into `false`. For
switches whose safe value is `true` — `bootui.mask-secrets`, `bootui.panels.*.read-only`, `bootui.read-only` —
that would silently widen access on a typo, so `BootUiBooleans` parses these values itself and falls back to
each key's documented default, warning about the invalid value.

**Architecture rules treat Micronaut like Spring, not like Quarkus.** Micronaut generates interception
subclasses at compile time, so a `private`, `static` or `final` method cannot be advised — the same
proxyability bar as Spring, rather than Arc's bytecode-transformation semantics. `ArchitecturePlatform.MICRONAUT`
therefore deliberately does not share Quarkus' relaxed visibility branch.

## What is available

`MicronautPanelAvailability` is the single source of truth, and the manifest at `/bootui/api/panels` reports
it with `platform: "micronaut"`. Three groups:

**Always live** — Overview, Beans, Mappings, Configuration (read-only), Loggers, Health, Metrics, Threads,
Heap Dump, Live Memory, JVM Tuning, HTTP Probe, HTTP Exchanges, Log Tail, Exceptions, Profile Diff,
Security Logs, Live Activity (including its service map), Traces, AI Framework, SQL Trace, REST Client,
Vulnerabilities, MCP Server, Command Line, and the Memory, Architecture, REST API, Pentesting and Database
advisors.

**Live when the integration is present** — Database Connection Pools (HikariCP), Cache
(`micronaut-cache-*`), Flyway (`micronaut-flyway`), Liquibase (`micronaut-liquibase`), Hibernate and
Hibernate Statistics (Hibernate ORM), WebSockets (`micronaut-websocket`), Fault Tolerance
(`micronaut-retry`), Email (`micronaut-email`). Each names its missing dependency when it is dark.

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

Five panels and one test surface remain, each reported honestly as *not yet available*:

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
- **Conformance**. The shared `bootui-conformance` suite has no Micronaut runner yet. Adding one means a
  `Runtime.MICRONAUT` constant, an `expected-panels-micronaut.json` manifest, and a runner in the sample app.
  That is the natural gate for the remaining panels.
