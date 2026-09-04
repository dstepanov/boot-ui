# Micronaut adapter — remaining work

Ready-to-file issues for the gaps in `bootui-micronaut`. Each entry states the blocker as it actually is, not as it
looked from the outside: several were investigated against the real Micronaut APIs and the conclusions changed.

Order below is the suggested order of work. **MN-1 is a correctness bug that makes the adapter unusable on a
default-configured Micronaut 4 application; everything else is coverage.**

| # | Title | Kind | Where | Size |
| --- | --- | --- | --- | --- |
| MN-1 | Serialization fails under `micronaut-serde` | Bug | adapter | M |
| MN-2 | Conformance runner for the Micronaut stack | Test | conformance + sample | M |
| MN-3 | Kafka capture | Panel | adapter | M |
| MN-4 | Micronaut application advisor ruleset | Panel | engine | L |
| MN-5 | Caffeine cache statistics | Enhancement | adapter | S |
| MN-6 | RabbitMQ capture | Panel | adapter | L |
| MN-7 | Dev Services over Micronaut Test Resources | Panel | adapter | M |
| MN-8 | Micronaut security advisor ruleset | Panel | engine | XL |
| MN-9 | Runtime configuration overrides | Enhancement | adapter | M |
| MN-10 | Derive `bootui.api-path` from `bootui.path` | Enhancement | adapter | M |
| MN-11 | GraalVM native-image support | Enhancement | adapter | M |
| MN-12 | Sample-app Playwright coverage | Test | sample | M |
| MN-13 | WebSocket frame capture | Blocked | upstream | — |
| MN-14 | Live circuit-breaker state | Blocked | upstream | — |

---

## MN-1 — Serialization fails under `micronaut-serde`

**Kind:** bug · **Priority:** highest

Every `/bootui/api/**` endpoint returns 500 when the application uses `micronaut-serde-jackson` instead of
`micronaut-jackson-databind`. Serde is the default for new Micronaut 4 applications, so BootUI is unusable on a large
share of them.

Reproduced by swapping the dependency in the sample app:

```
Internal Server Error: Error encoding object [PanelsReport[platform=micronaut, panels=[...]]]
No serializable introspection present for type ...  Consider adding @Serdeable
```

The cause is architectural, not accidental: core DTOs are annotation-free immutable records by design
(`java-architecture.instructions.md`), and Serde requires a compile-time introspection for every type it writes.

**Options**

1. Declare `@SerdeImport` for the DTOs from the adapter, which is exactly what that annotation exists for — external
   types made serializable without annotating them. ~150 records in `io.github.jdubois.bootui.core.dto`, so generate
   the import class rather than hand-writing it, and add a test asserting every DTO the API returns is covered.
2. Force `micronaut-jackson-databind`. Rejected: it conflicts with an application that deliberately chose Serde, and
   BootUI must not dictate the host's JSON stack.

**Acceptance:** the sample app builds and passes its smoke test under both `micronaut-serde-jackson` and
`micronaut-jackson-databind`; a test fails if a DTO reachable from the API has no introspection.

---

## MN-2 — Conformance runner for the Micronaut stack

**Kind:** test · **Priority:** high — this is the gate for everything after it

`bootui-conformance` has no Micronaut runner, so the adapter's 35 live panels are verified by a hand-written smoke
test rather than the shared contract suite.

**Work**

- Add `BootUiApiContractCatalog.Runtime.MICRONAUT`.
- Decide what `ALL` means now: it is `Set.of(SPRING_MVC, SPRING_WEBFLUX, QUARKUS)`, and adding Micronaut to it asserts
  every contract — including the five unported panels. Each `ALL` contract needs the same triage
  `McpToolCatalog.NON_MICRONAUT_STACKS` just received.
- Add `expected-panels-micronaut.json` (58 panels, registry order).
- Add the runner to `bootui-micronaut-sample-app` with `@MicronautTest` on a random port, plus the
  `AbstractCliConformanceTest` and `AbstractMcpConformanceTest` runners.

**Risk:** the suite checks more than a 200 — canonical rejection bodies, action contracts, expected error-contract
components. Expect real failures here; that is the point of doing it before adding more panels.

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

## MN-10 — Derive `bootui.api-path` from `bootui.path`

**Kind:** enhancement

On Spring and Quarkus the API mount derives from the UI mount. On Micronaut a `@Controller` placeholder default cannot
reference another property, so `BootUiPathsValidator` fails fast when only `bootui.path` is set — honest, but an
adapter-specific rule users have to learn.

A `DefaultRouteBuilder` that mirrors the console's routes at the configured mount would remove the difference:
`RouteBuilder` exposes `GET(String, BeanDefinition, ExecutableMethod)`, so the routes can be re-registered at startup
from the existing controller definitions. Verify it composes with `micronaut.server.context-path` and that the
mirrored routes still go through the filter chain in the same order.

---

## MN-11 — GraalVM native-image support

**Kind:** enhancement

Micronaut is native-image-first and BootUI serializes with reflective Jackson databind, so the adapter is unlikely to
work in a native image today. This overlaps with MN-1: moving to compile-time introspections would fix reflection at
the same time.

Also needs checking: the SPA assets served from the classpath by `BootUiAssetsController`, and the reflective Caffeine
`estimatedSize()` / Hibernate unwrap paths.

---

## MN-12 — Sample-app Playwright coverage

**Kind:** test

`bootui-spring-sample-app/e2e` and `bootui-quarkus-sample-app/e2e` have browser suites; the Micronaut sample has none.
The console has been driven by hand against it, which is not a regression gate. Mirror the Quarkus suite's smoke spec
once MN-2 lands.

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
