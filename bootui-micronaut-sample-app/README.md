# BootUI Micronaut sample app

A minimal Micronaut 4 application with the BootUI console wired in, the analogue of the Spring and Quarkus
sample apps. Use it to try the console, or as the reference for what a Micronaut integration looks like.

## Run it

```bash
./mvnw -B -ntp -pl bootui-micronaut-sample-app -am install -DskipTests
./mvnw -B -ntp -pl bootui-micronaut-sample-app exec:exec
```

Then open <http://localhost:8080/bootui>. Pass `-Dmicronaut.server.port=18080` to move it.

`exec:exec`, not `exec:java`: `exec:java` runs the application inside Maven's own JVM, where
`java.class.path` describes the Maven launcher rather than the application. The Vulnerabilities panel
builds its inventory from the runtime classpath, and started that way it used to report exactly one
dependency, `org.apache.maven.wrapper:maven-wrapper`. The adapter now also reads the application
classloader so it survives an in-process launcher, but this sample is what a Micronaut integration is
supposed to look like, so it runs the way a real application does: its own JVM, its own classpath.

The application's own endpoints are `GET /catalog`, `GET /catalog/{index}`, `GET /catalog/flaky` and a `/echo`
WebSocket, so the Mappings, Beans, Fault Tolerance and WebSockets panels have real application content to show.

## Why it declares an environment

BootUI activates in the `dev`, `local` and `test` environments. Micronaut deduces `test` on its own but never
`dev`, and `micronaut.environments` cannot be set from `application.properties` — Micronaut resolves the active
environments before it reads configuration files. So the sample declares `dev` as its *default* environment in
`BootUiMicronautSampleApplication`, which still lets a system property or `MICRONAUT_ENVIRONMENTS` override it:

```bash
./mvnw -B -ntp -pl bootui-micronaut-sample-app exec:exec -Dmicronaut.environments=prod
```

started that way, the console is correctly dark and the whole `/bootui` surface answers 404. (The pom
forwards `micronaut.environments` and `micronaut.server.port` to the forked JVM as Maven properties, because
`exec:exec` passes the arguments the pom lists rather than inheriting Maven's own system properties.)

## What it adds beyond the adapter

Each dependency lights up one of the integration-gated panels, so the sample shows them with real data
rather than their "add this dependency" state:

- `micronaut-management` — Health reports real indicators rather than setup guidance.
- `micronaut-micrometer-core` — Metrics reports real meters.
- `logback-classic` — Loggers can read and write levels, and the Log Tail and Exceptions panels capture.
- `micronaut-retry` — Fault Tolerance inventories `FlakyService`'s `@Retryable` and `@CircuitBreaker`
  policies; `GET /catalog/flaky` fails twice out of three calls, so retry events actually appear.
- `micronaut-websocket` — WebSockets inventories the `/echo` endpoint and tracks its sessions.

It also ships the `logback.xml` Micronaut Launch generates for a new application — one console appender,
root at `INFO`. That is not housekeeping: with no configuration at all Logback defaults the root logger to
`DEBUG`, and the console then shows Micronaut's bean-resolution chatter in the Log Tail panel and records
the `UnsupportedOperationException`s Netty throws and logs at `DEBUG` while probing the JVM at startup
("Native access (restricted methods) is not enabled…", "sun.misc.Unsafe unavailable") as this application's
own exceptions. Netty handles those itself; attributing them to the sample is simply wrong.

This module is a reference application and is never published to Maven Central.
