# Releasing

Pushing a `v*.*.*` tag runs `.github/workflows/release.yml`: tests, then a signed upload to the Central Portal
(`automaticRelease = true`, so no manual step there), then a GitHub release.

## One-time setup

1. Register the namespace `dev.nihildigit` at <https://central.sonatype.com> under Namespaces. Verification is a
   DNS TXT record on `nihildigit.dev` with the value Sonatype shows.
2. Create a user token on the Central Portal profile.
3. Reuse the GPG key that signs pikpak-kotlin, or create one and publish its public half to a keyserver.
4. Add four repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (the
   ASCII-armored private key) and `SIGNING_KEY_PASSWORD`.

## Per release

```bash
# set version in build.gradle.kts, then
git commit -am "release: 0.1.0"
git tag -a v0.1.0 -m "v0.1.0"
git push origin main v0.1.0
```

The workflow refuses a tag that differs from the version in `build.gradle.kts` and refuses `-SNAPSHOT`.
Central takes a while to sync to `repo1.maven.org`; wait until the version resolves there before pointing a
consumer at it.

## Local development against a consumer

A consumer includes this repository as a composite build instead of publishing anything, which keeps the
namespace's upload quota intact:

```kotlin
// settings.gradle.kts of the consumer
includeBuild("../compose-windows-touch") {
    dependencySubstitution {
        substitute(module("dev.nihildigit:compose-windows-touch")).using(project(":"))
    }
}
```
