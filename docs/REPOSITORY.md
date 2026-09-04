# Repository and documentation

## Modules

- `bootui-core`: shared DTOs, secret masking, and core helpers.
- `bootui-engine`: framework-neutral services/advisors and SPI ports.
- `bootui-spring-autoconfigure`: Spring MVC/WebFlux adapter (auto-configuration, endpoints, safety).
- `bootui-spring-boot-starter`: Spring MVC starter dependency.
- `bootui-spring-boot-starter-reactive`: Spring WebFlux starter dependency.
- `bootui-ui`: Vue 3 frontend packaged into `META-INF/resources/bootui/`.
- `bootui-conformance`: shared HTTP contract suite and golden panel manifests for all adapters.
- `bootui-client`: dependency-free client for the command-line endpoint; depends on nothing, not even `bootui-core`.
- `bootui-cli`: the `bootui` command-line interface, generated from the engine's MCP tool catalog.
- `bootui-spring-sample-app`: Spring MVC sample app + Playwright e2e coverage.
- `bootui-spring-webflux-sample-app`: Spring WebFlux sample app.
- `bootui-quarkus-parent`: shared Quarkus LTS BOM and plugin management.
- `bootui-quarkus`: Quarkus runtime adapter.
- `bootui-quarkus-deployment`: Quarkus build-time wiring module.
- `bootui-quarkus-integration-tests`: Quarkus `@QuarkusTest` suites.
- `bootui-quarkus-sample-app`: Quarkus sample app.
- `bootui-micronaut-parent`: shared Micronaut platform BOM and annotation-processor plugin management.
- `bootui-micronaut`: Micronaut runtime adapter.
- `bootui-micronaut-sample-app`: Micronaut sample app.

## Compatibility version source of truth

Spring Boot, Quarkus and Micronaut compatibility references for the published adapters should follow the root
`pom.xml` properties:

- `spring-boot.version`
- `quarkus.platform.version`
- `micronaut.platform.version` (with `micronaut.core.version` for the annotation processor)

When these are updated, refresh matching documentation references in the same pull request (`README.md`,
`docs/SETUP.md`, `docs/features/`, `.github/copilot-instructions.md`, and
`.github/instructions/{spring-adapter,quarkus-adapter,micronaut-adapter}.instructions.md`). All Quarkus modules inherit
`bootui-quarkus-parent`, and all Micronaut modules inherit `bootui-micronaut-parent`; each imports its framework's BOM
closer than the root parent imports Spring Boot's BOM. This keeps the frameworks' shared transitive dependencies
isolated while giving each adapter, its tests, and its sample app one pinned platform version. Both parents are POM-only
but are published to Maven Central, because a consumer resolving `bootui-quarkus` or `bootui-micronaut` reads its
`<parent>` from there.

## Documentation website

The public documentation website is <https://www.julien-dubois.com/boot-ui/>. It is built with VuePress from the
markdown files in `docs/`, so repository documentation stays the source of truth for the published site.

```bash
npm install
npm run docs:dev
```

The local development server runs at <http://127.0.0.1:8090>. Before pushing documentation changes, run:

```bash
npm run docs:build
```

GitHub Pages is deployed by `.github/workflows/pages.yml` from the `main` branch. In the repository settings, set
**Pages > Build and deployment > Source** to **GitHub Actions**. The workflow builds VuePress with the `/boot-ui/` base
path and publishes the site at <https://www.julien-dubois.com/boot-ui/>.
