# Micronaut adapter — remaining work

Ready-to-file issues for the gaps in `bootui-micronaut`. Each entry states the blocker as it actually is, not as it
looked from the outside: several were investigated against the real Micronaut APIs and the conclusions changed.

Order below is the suggested order of work. Numbering is stable: an entry keeps its identifier once it has been
filed, so a closed item is struck from the table rather than renumbering the rest.

| # | Title | Kind | Where | Size |
| --- | --- | --- | --- | --- |
| ~~MN-1~~ | ~~Serialization fails under `micronaut-serde`~~ | Bug | adapter | **done** |
| ~~MN-2~~ | ~~Conformance runner for the Micronaut stack~~ | Test | conformance + sample | **done** |
| MN-3 | Kafka capture | Panel | adapter | M |
| MN-4 | Micronaut application advisor ruleset | Panel | engine | L |
| MN-5 | Caffeine cache statistics | Enhancement | adapter | S |
| MN-6 | RabbitMQ capture | Panel | adapter | L |
| MN-7 | Dev Services over Micronaut Test Resources | Panel | adapter | M |
| MN-8 | Micronaut security advisor ruleset | Panel | engine | XL |
| MN-9 | Runtime configuration overrides | Enhancement | adapter | M |
| ~~MN-10~~ | ~~Derive `bootui.api-path` from `bootui.path`~~ — **done** | Enhancement | adapter | M |
| MN-11 | GraalVM native-image support | Enhancement | adapter | M |
| MN-12 | Sample-app Playwright coverage | Test | sample | M |
| MN-13 | WebSocket frame capture | Blocked | upstream | — |
| MN-14 | Live circuit-breaker state | Blocked | upstream | — |

---

## MN-1 — Serialization fails under `micronaut-serde` — **done**

Every `/bootui/api/**` endpoint returned 500 when the application used `micronaut-serde-jackson` rather than
`micronaut-jackson-databind`, which is the JSON stack a new Micronaut 4 application gets by default.

Resolved along option 1: `BootUiSerdeImports` in `bootui-micronaut` declares a `@SerdeImport` for every record the
API can put on the wire, and `micronaut-serde-processor` on the compiler's `annotationProcessorPaths` generates the
introspections into BootUI's own jar. The core DTOs stay annotation-free, and `micronaut-serde-api` is `provided`, so
an application on databind is not dragged onto Serde. Option 2 stayed rejected.

An introspection makes a DTO writable but does not decide which of its fields get written, and Serde's own
`serde.serialization.inclusion` defaults to `NON_EMPTY` — the same trap MN-2 found on databind. So every
`@SerdeImport` also carries `mixin = AlwaysInclude.class`, a mix-in annotated `@JsonInclude(ALWAYS, content = ALWAYS)`
that `micronaut-serde-processor` translates into `@SerdeConfig(include = ALWAYS, includeContent = ALWAYS)` at compile
time. It is scoped to the imported types, so the host application's inclusion setting still governs its own
responses, and it covers nulls as well as empty containers.

Three related fixes were needed for the same reason — a type Serde cannot write: the MCP transport now serializes its
own JSON-RPC envelope instead of answering a Jackson `JsonNode`; `MicronautMcpEnvelope` owns a private `ObjectMapper`
instead of injecting a bean that does not exist under Serde; and `MicronautConfigProvider` reduces resolved property
values (which can be any object, a `Class` among them) to the types the JSON contract is defined in.

`micronaut-jackson-databind` is no longer a compile dependency of `bootui-micronaut` at all — only plain
`com.fasterxml.jackson.core:jackson-databind`, for BootUI's own internal JSON work, which registers nothing with the
host.

Guarded by `BootUiSerdeImportsTest` (package coverage, a reachability closure over record components, and the mix-in
on every import, so a new DTO without an import — or with an import missing the mix-in — fails the build) and by
running both the smoke suite and the shared API conformance suite on both stacks: `bootui-micronaut`'s own tests
under `micronaut-serde-jackson` (`MicronautSerdeApiConformanceTest`), `bootui-micronaut-sample-app`'s under
`micronaut-jackson-databind` (`MicronautApiConformanceTest`). `BootUiJsonInclusionContractTest` exists in both
modules and asserts on the raw response bytes, since deserializing would silently fill a dropped field back in. See
[Micronaut support](./MICRONAUT-SUPPORT.md) for the full rationale.

---

## MN-2 — Conformance runner for the Micronaut stack — **done**

**Kind:** test · **Priority:** was high — this was the gate for everything after it

`bootui-conformance` now has `Runtime.MICRONAUT`, an `expected-panels-micronaut.json` manifest, and three runners in
`bootui-micronaut-sample-app` (`MicronautApiConformanceTest`, `MicronautCliConformanceTest`,
`MicronautMcpConformanceTest`), each `@MicronautTest` on a random port. The API suite also runs a fourth time, in
`bootui-micronaut` itself (`MicronautSerdeApiConformanceTest`), so the whole contract is exercised on
`micronaut-serde-jackson` as well as on `micronaut-jackson-databind`; the two stacks share no serialization code, so
every wire-shape guarantee has to be earned twice.

`ALL` gained `MICRONAUT`; the four action contracts Micronaut cannot satisfy (`security.scan`, `spring.scan`,
`kafka.clear`, `rabbitmq.clear`) moved to a new `NON_MICRONAUT` set, mirroring `McpToolCatalog.NON_MICRONAUT_STACKS`.
The read contracts needed no triage: the suite only exercises a panel the live manifest reports available, and the
unported panels report themselves unavailable. `config` is declared `actionlessPanels()` for the same reason Quarkus
does (no runtime override write path — MN-9).

**The risk was real: the suite found three adapter bugs, all now fixed.**

1. **Empty collections and nulls were omitted from every response.** Micronaut defaults
   `jackson.serialization-inclusion` to `NON_EMPTY`, so an empty list or map property was dropped instead of written
   as `[]` / `{}` — breaking the wire contract on exactly the panels with nothing to report. Fixed by
   `BootUiJsonInclusionCustomizer`, a Jackson mix-in resolver scoped to `io.github.jdubois.bootui.*` classes, so the
   host application's own inclusion policy is untouched. Serde defaults `serde.serialization.inclusion` to the same
   `NON_EMPTY` and needed its own equivalent, since the mix-in resolver is a Jackson-databind mechanism and Serde
   publishes no `ObjectMapper` bean for it to attach to: every `@SerdeImport` in `BootUiSerdeImports` now carries
   `mixin = AlwaysInclude.class`, applied at compile time (MN-1). Both halves are pinned on the raw response bytes by
   a `BootUiJsonInclusionContractTest` in each module.
2. **`ActionBusyException` had no handler**, so a second concurrent scan got the framework's generic 500 instead of
   the canonical 409 + `ActionBusyResult`. Fixed by `ActionBusyExceptionHandler`, the analogue of the Spring and
   Quarkus mappers.
3. **`GET /exceptions/{id}` returned a bodyless 404**, so the `get_exception_detail` MCP/CLI tool told an agent only
   "Not Found" and never which id. It now throws `HttpStatusException` with the same message the Quarkus adapter uses,
   which `MicronautMcpToolFailures` already knows how to translate in-band.

---

## MN-3 — Kafka capture

**Kind:** panel · Lights up: Kafka, plus `MESSAGING` entries in Live Activity and the service map

The recorders are already wired (`KafkaActivityRecorder` is produced unconditionally), so only the capture points are
missing. micronaut-kafka has clean seams — an earlier assessment that it had none was wrong:

- **Consumer:** implement `io.micronaut.configuration.kafka.ConsumerRecordInterceptor`. `KafkaConsumerProcessor` takes
  `List<ConsumerRecordInterceptor<?,?>>` in its constructor and filters per listener through
  `matches(BeanDefinition, ExecutableMethod)`, so a `@Singleton` is enough.
- **Producer:** wrap `ProducerFactory` with a `BeanCreatedEventListener`, returning a recording `Producer` proxy — the
  same pattern the datasource and email captures already use.

**Open question to answer first:** does `KafkaClientIntroductionAdvice` (declarative `@KafkaClient`) obtain producers
through `ProducerFactory`, or build them itself? It keeps its own `producerMap`. If it bypasses the factory, the seam
has to move to `ProducerRegistry` or declarative sends go uncaptured.

**Also:** keys are typed (`Serializer<K>`); decide how a non-String key renders into the recorder's bounded key field.
Reuse the `bootui.kafka.*` keys and defaults from the Quarkus adapter unchanged.

---

## MN-4 — Micronaut application advisor ruleset

**Kind:** panel (engine work) · Lights up: the `spring` panel id (the platform-aware application advisor)

`QuarkusAppChecks` is 19 rules (`QA-*`, 366 lines) over a `QuarkusAppSnapshot` of CDI scopes, `@ConfigProperty` use,
reactive/blocking and profiles. A Micronaut ruleset needs its own snapshot record and checks in a new
`io.github.jdubois.bootui.engine.micronautapp` package — the adapter side is just a config-driven snapshot provider.

Several concepts port directly (configuration injection style, environments, blocking work on the event loop);
Micronaut-specific candidates include compile-time DI misuse, `@Requires` conditions that can never match, and
`@ExecuteOn` placement. This is the cheaper of the two advisors and the better first one.

---

## MN-5 — Caffeine cache statistics

**Kind:** enhancement · **Size:** small

`MicronautCacheProvider` reports statistics as unavailable for every cache. That is right for the abstraction —
`SyncCache` has no statistics API — but wrong for the common case: Micronaut's default store is Caffeine, whose native
cache exposes `stats()` once the application enables `recordStats`.

Read them reflectively when the native cache is a Caffeine cache and recording is on, and narrow the existing
"no statistics API" message to the stores where it is still true.

---

## MN-6 — RabbitMQ capture

**Kind:** panel · **Size:** large — one side has no public seam

- **Publishing:** `ReactivePublisher` is a public interface; wrap it with a `BeanCreatedEventListener`.
- **Consuming:** no public seam. `RabbitMQConsumerAdvice`/`DefaultConsumer` are internal, and the only events
  (`RabbitConsumerStarting`/`Started`) are lifecycle, not per-message.

The precedent for going lower is micronaut-rabbitmq's own metrics: `RabbitMetricsInterceptor` is a
`BeanCreatedEventListener<ConnectionFactory>`. BootUI can do the same, but that means proxying
ConnectionFactory → Connection → Channel to observe `basicPublish`/`basicConsume`, and ordering carefully against the
application's own connection-factory listeners. Consider shipping publish-side capture first.

---

## MN-7 — Dev Services over Micronaut Test Resources

**Kind:** panel · **Blocker is data shape, not access**

`TestResourcesClient` exposes `getResolvableProperties`, `resolve`, `getRequiredProperties` and `closeAll`/`closeScope`.
It has **no container inventory** — no image, status or ports — which is most of `DevServiceDto`.

**Two decisions before coding**

1. The panel would list *resolved resources* with type inferred from their values (the engine's
   `DevServiceTypeInference` already does this for Quarkus), leaving `image`, `status` and `restartable` honestly
   empty. Confirm that is worth shipping.
2. **Safety:** `resolve()` can start a container. The panel must read only already-resolved values and never provoke a
   start on render, per the "page render is network-free and side-effect-free" rule.

---

## MN-8 — Micronaut security advisor ruleset

**Kind:** panel (engine work) · **Size:** extra-large

`QuarkusSecurityChecks` is 49 rules (`QS-*`, 802 lines) over a 30-field `QuarkusSecuritySnapshot` — OIDC, proactive
auth, `quarkus.http.insecure-requests`, permission mappings. micronaut-security's model is different enough
(`SecurityRule` beans, `intercept-url-map`, `micronaut.security.*`, JWT/OAuth2 configuration, `@Secured` counts) that
roughly two thirds of the rules need rewriting rather than remapping.

Perhaps a third port conceptually: TLS configuration, CORS, security headers, secret-looking configuration keys.
Worth splitting into "transport and headers" (shareable) and "authentication and authorization" (framework-shaped).

---

## MN-9 — Runtime configuration overrides

**Kind:** enhancement · Makes the Configuration panel writable

The panel is read-only on Micronaut, and `MicronautConfigProvider.overrideSourceName()` returns `null` — matching
Quarkus, which genuinely has no write path.

Micronaut does: `Environment.addPropertySource(...)` accepts a source at runtime. A high-precedence BootUI override
source would give the same experience Spring has. Needs care about which properties can meaningfully be re-read
(anything bound at startup will not change), so the panel must be honest about what an override does and does not
affect.

---

## MN-10 — Derive `bootui.api-path` from `bootui.path` — **done**

**Kind:** enhancement · **Status:** done

On Spring and Quarkus the API mount derives from the UI mount. On Micronaut a `@Controller` placeholder default cannot
reference another property — the resolver does not evaluate a nested `${...}` inside a default, so
`${bootui.api-path:${bootui.path:/bootui}/api}` resolves to the literal `/bootui/api}` — and `BootUiPathsValidator`
used to fail fast when only `bootui.path` was set: honest, but an adapter-specific rule users had to learn.

Solved without re-registering any route. `BootUiApiPathConfigurer` is an `ApplicationContextConfigurer`, which
Micronaut invokes after the environment has started and before any bean definition is loaded — so it can read
`bootui.path` from every property source and contribute `bootui.api-path` = `<bootui.path>/api` in time for the
controllers' own placeholders to resolve to it. The contribution is skipped when the key is set, sits below every
application property source, and stays silent on an invalid `bootui.path` so `BootUiPathsValidator` still reports that
with the normalizer's message. `BootUiApiPathDerivationTest` boots a real server to pin that `bootui.path=/console`
alone serves `/console/api/panels` (404 at `/bootui/api/panels`) and composes with `micronaut.server.context-path`.

---

## MN-11 — GraalVM native-image support

**Kind:** enhancement

Micronaut is native-image-first. MN-1 removed part of the obstacle: an application on `micronaut-serde-jackson` now
serializes every BootUI response through compile-time introspections, with no reflection at all. An application on
`micronaut-jackson-databind` still serializes reflectively, and BootUI's own internal Jackson use (the MCP envelope,
the GitHub and OSV clients) is reflective on both stacks, so this is not closed — but the console's response path is
no longer the blocker it was.

Also needs checking: the SPA assets served from the classpath by `BootUiAssetsController`, and the reflective Caffeine
`estimatedSize()` / Hibernate unwrap paths.

---

## MN-12 — Sample-app Playwright coverage

**Kind:** test

`bootui-spring-sample-app/e2e` and `bootui-quarkus-sample-app/e2e` have browser suites; the Micronaut sample has none.
The console has been driven by hand against it, which is not a regression gate. Mirror the Quarkus suite's smoke spec
now that MN-2 has landed.

---

## MN-13 — WebSocket frame capture (blocked upstream)

Micronaut binds WebSocket messages directly to the annotated handler method through compile-time binders, and AOP
advice is compile-time bound, so a library added to an application later cannot weave into handler classes it did not
compile. There is no `WebSocketSessionHandler` decorator equivalent to the STOMP seam the Spring MVC adapter uses.

The panel reports this honestly today. Closing it needs a Micronaut-side interception SPI; worth raising upstream
rather than tracking here.

---

## MN-14 — Live circuit-breaker state (blocked upstream)

`@CircuitBreaker` state lives inside the compile-time generated interceptor, with no equivalent of SmallRye's
`CircuitBreakerMaintenance` to query it. The Fault Tolerance panel therefore reports the configured policy and learns
state by observing `CircuitOpenEvent`/`CircuitClosedEvent`, which is accurate but only from the first transition
onward.

Closing it needs a Micronaut-side accessor; same recommendation as MN-13.
