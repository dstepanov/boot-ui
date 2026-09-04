# BootUI Micronaut sample app

A minimal Micronaut 4 application with the BootUI console wired in, the analogue of the Spring and Quarkus
sample apps. Use it to try the console, or as the reference for what a Micronaut integration looks like.

## Run it

```bash
./mvnw -B -ntp -pl bootui-micronaut-sample-app -am install -DskipTests
./mvnw -B -ntp -pl bootui-micronaut-sample-app exec:java
```

Then open <http://localhost:8080/bootui>.

The application's own endpoints are `GET /catalog`, `GET /catalog/{index}`, `GET /catalog/flaky` and a `/echo`
WebSocket, so the Mappings, Beans, Fault Tolerance and WebSockets panels have real application content to show.

## Why it declares an environment

BootUI activates in the `dev`, `local` and `test` environments. Micronaut deduces `test` on its own but never
`dev`, and `micronaut.environments` cannot be set from `application.properties` — Micronaut resolves the active
environments before it reads configuration files. So the sample declares `dev` as its *default* environment in
`BootUiMicronautSampleApplication`, which still lets a system property or `MICRONAUT_ENVIRONMENTS` override it:

```bash
./mvnw -B -ntp -pl bootui-micronaut-sample-app exec:java -Dmicronaut.environments=prod
```

started that way, the console is correctly dark and the whole `/bootui` surface answers 404.

## What it adds beyond the adapter

Each dependency lights up one of the integration-gated panels, so the sample shows them with real data
rather than their "add this dependency" state:

- `micronaut-management` — Health reports real indicators rather than setup guidance.
- `micronaut-micrometer-core` — Metrics reports real meters.
- `logback-classic` — Loggers can read and write levels, and the Log Tail and Exceptions panels capture.
- `micronaut-retry` — Fault Tolerance inventories `FlakyService`'s `@Retryable` and `@CircuitBreaker`
  policies; `GET /catalog/flaky` fails twice out of three calls, so retry events actually appear.
- `micronaut-websocket` — WebSockets inventories the `/echo` endpoint and tracks its sessions.

This module is a reference application and is never published to Maven Central.
