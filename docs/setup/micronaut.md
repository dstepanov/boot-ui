# BootUI on Micronaut

BootUI also ships as a **Micronaut adapter**. It serves the same Vue console and JSON contract as Spring and
Quarkus (`/bootui` and `/bootui/api/**` by default), backed by the Micronaut build of the framework-neutral
BootUI engine.

## Prerequisites

- Java 17 or later
- A Micronaut 4 application (built and tested against the version pinned by the root `pom.xml`'s
  `micronaut.platform.version` property)
- Maven or Gradle (or their local wrappers)

## Add the dependency

Add BootUI to your build — nothing else is required. BootUI wires itself up only in the **dev**, **local** and
**test** environments and stays completely dark everywhere else, so it is safe to leave on the classpath.

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-micronaut</artifactId>
  <version>1.16.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
implementation 'com.julien-dubois.bootui:bootui-micronaut'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
implementation("com.julien-dubois.bootui:bootui-micronaut")
```

:::

## Run your app in development

Micronaut deduces the `test` environment on its own when your application starts from a JUnit or Spock test, but it
does **not** deduce a `dev` environment for an ordinary run. Declare it once, in whichever way suits your project:

```java
Micronaut.build(args)
        .mainClass(Application.class)
        .defaultEnvironments(Environment.DEVELOPMENT)
        .start();
```

or, without touching code:

```bash
./mvnw exec:java -Dmicronaut.environments=dev
```

```bash
MICRONAUT_ENVIRONMENTS=dev ./gradlew run
```

`micronaut.environments` cannot be set in `application.yml` — Micronaut resolves the active environments *before* it
reads configuration files, so it must come from the builder, a system property, or the environment variable.

## Open BootUI

Nice job! BootUI is now configured 🚀

Visit: <http://localhost:8080/bootui>

## Use a custom path

The same `bootui.path` / `bootui.api-path` settings shown in
[Use a custom path](../SETUP.md#use-a-custom-path) work on Micronaut and compose with
`micronaut.server.context-path`, with **one Micronaut-specific rule**: set both keys together.

```properties
bootui.path=/console
bootui.api-path=/console/api
```

On Spring and Quarkus the API mount derives from the UI mount automatically. Micronaut resolves a controller's path
from a configuration placeholder whose default cannot reference another property, so moving only `bootui.path` would
leave the API behind. BootUI fails fast at startup with an actionable message rather than serving a console that loads
and then fails every call.

## Activation and safety on Micronaut

Activation mirrors the Spring adapter's, with Micronaut **environments** in place of Spring profiles:

```properties
bootui.enabled=AUTO                                # AUTO (default), ON, or OFF
bootui.enabled-environments=dev,local,test         # any active one enables the console
bootui.disabled-environments=prod,production       # any active one disables it, unless bootui.enabled=ON
```

Everything fails closed: an unknown `bootui.enabled` value, or no matching environment, leaves the console dark. When
it is dark, **no** controller, filter, engine service or provider bean is created at all, and an always-registered
guard answers `404` for the whole console surface — including the packaged SPA assets — so a production build cannot
serve even an empty shell.

The request-time safety model is **identical to Spring Boot and Quarkus**: BootUI is loopback-only by default and
shares the same `LocalhostGuard` (loopback-source trust, a `Host` allow-list as a DNS-rebinding defense, and
cross-site-write / CSRF protection). The same opt-in keys apply, read live from the Micronaut environment:

```properties
bootui.allow-non-localhost=false        # default: reject non-loopback callers
bootui.allowed-hosts=localhost          # extra Host header values to accept
bootui.trusted-proxies=172.16.0.0/12    # extra source ranges (e.g. a Docker gateway)
bootui.trust-container-gateway=AUTO     # auto-trust the container gateway in dev containers
```

Per-panel `bootui.panels.*` enable / read-only toggles and the `bootui.read-only` master switch are enforced
identically on every framework.

## Which panels are available on Micronaut

Most of them. The console itself is the authoritative answer for your application: every panel that cannot run says so
in the sidebar and in a banner, and `/bootui/api/panels` carries the same information for agents and scripts.

Live without any extra dependency: **Beans** (the live bean container, including injection edges), **Mappings** (the
live router), **Configuration** (read-only), **Loggers** (read and write Logback levels), **Threads**, **Heap Dump**,
**Live Memory**, **JVM Tuning**, **HTTP Probe**, **HTTP Exchanges**, **Log Tail**, **Exceptions**, **Profile Diff**,
**Security Logs**, **Live Activity** with its service map, **Traces**, **AI Framework**, **SQL Trace**, **REST
Client**, **Vulnerabilities**, **MCP Server**, **Command Line**, and the **Memory**, **Architecture**, **REST API**,
**Pentesting** and **Database** advisors. **Health** and **Metrics** report real data once `micronaut-management` and
`micronaut-micrometer` are present.

Panels that light up with their integration: **Database Connection Pools** (HikariCP), **Cache**, **Flyway**,
**Liquibase**, **Hibernate** and **Hibernate Statistics**, **WebSockets**, **Fault Tolerance**, **Email**. **GitHub**,
**Copilot** and **Claude Code** light up when the working directory or the agent's session directory says so.

Panels that target Spring-specific runtime concepts — GraalVM and CRaC readiness, Conditions, Startup Timeline, HTTP
Sessions, Spring Data, Spring Security, DevTools and Transactions — are marked *not applicable* on Micronaut, each with
its own reason. Kafka, RabbitMQ, Dev Services, the Security advisor and the platform application advisor are marked
*not yet available*: see [Micronaut support](../MICRONAUT-SUPPORT.md) for why and what each would take.
