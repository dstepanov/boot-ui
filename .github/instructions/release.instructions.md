---
applyTo: ".github/workflows/release.yml,pom.xml,**/pom.xml,README.md,docs/SETUP.md,package.json,package-lock.json,**/package.json,**/package-lock.json"
---

# Release and publishing

- Use `.github/workflows/release.yml` for version bumps. It must update Maven versions, `README.md`, `docs/SETUP.md`,
  and every npm package and lock file.
- Keep `quarkus.platform.version` independent from the BootUI project version.
- Published artifacts are the parent POM, core, engine, UI, Spring autoconfigure, both Spring starters (MVC and
  reactive), Quarkus parent, Quarkus runtime, and Quarkus deployment. Sample apps, integration tests, and conformance
  must retain `maven.deploy.skip=true`.
- The source-less published modules (`bootui-ui`, `bootui-spring-boot-starter`, and
  `bootui-spring-boot-starter-reactive`) must attach their empty `javadoc.jar` during `package`, before release-profile
  signing at `verify`.
- Preserve the current workflow sequence: prepare and verify the versioned working tree; publish, wait for Central, and
  smoke-test all three distributions; then commit and push the release. If the branch advanced during publication,
  rebase the version-only release commit and retry. Create the annotated tag only after the push succeeds so it points to
  the commit that landed. A genuine rebase conflict fails loudly.
- Maven Central requires the matching public signing key to be available by fingerprint. Never expose signing secrets in
  command arguments or logs. If macOS `gpg --send-keys` fails through dirmngr, use the HTTPS upload APIs for
  `keys.openpgp.org` and `keyserver.ubuntu.com`.
- A failed Central deployment may consume the coordinate. Do not retry the same version until the failed deployment is dropped.
