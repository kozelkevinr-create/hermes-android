# Contributing to Hermes for Android

Thanks for your interest! This is an early-stage open-source project. By participating you
agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

1. Install **Android Studio** (it bundles the JBR / JDK 21 the build expects) and the Android
   SDK (`compileSdk` 36 / build-tools 36.x).
2. Clone and open the project; let Gradle sync.
3. Run the checks:
   ```bash
   ./gradlew :app:testDebugUnitTest      # JVM unit tests
   ./gradlew :app:assembleDebug          # build
   # Optional, needs a device/emulator:
   ./gradlew :app:connectedDebugAndroidTest
   ```

## Ground rules

- **Architecture:** MVVM + Hilt + Jetpack Compose + Repository. Keep transport
  (`data/network`), storage (`data/auth`, DataStore), and UI (`ui/<screen>`) separate.
  Business logic that can be a pure function (e.g. the chat `reduce()` reducer, DTO mappers)
  should live in a testable, I/O-free unit.
- **Tests:** add JVM unit tests for logic you can test without a device (see
  `ChatReducerTest`, the `*ViewModelTest`s). PRs that change reducers, parsing, mapping, or
  auth flow must include tests.
- **Security:** never commit secrets — `keystore.properties`, `*.jks`/`*.keystore`,
  `local.properties`, tokens, or anything under `~/.secrets`. Store credentials only via
  `EncryptedCredentialStore`, and don't loosen the network/cleartext config beyond what
  self-hosted private-network use requires. See [SECURITY.md](SECURITY.md). A `gitleaks`
  secret scan runs on every push (pre-push hook + CI).
- **Dependencies:** `gradle/verification-metadata.xml` pins sha256 checksums for the whole
  dependency graph (including the GitHub-hosted `org.rustls` artifacts). Any dependency or plugin
  bump, Dependabot PRs included, must regenerate it in the same PR:
  `./gradlew --no-daemon --refresh-dependencies --write-verification-metadata sha256 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleBeta`
  (`--refresh-dependencies` matters: a warm cache hides build-script artifacts). Review the diff
  for unexpected new components before committing.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org)
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`, `ci:`). No
  AI/assistant attribution in commits or PRs.
- **Style:** idiomatic Kotlin; match the surrounding code's conventions and comment density.

## Branches & pull requests

New work lands on `main` through a PR from a short-lived branch (see
[Development & release workflow](README.md#development--release-workflow)).

1. Branch from `main`: `git switch main && git switch -c feature/my-change`.
2. Open a PR **into `main`**. Keep it focused and incremental; describe what changed and
   how you verified it.
3. CI (build + unit tests + CodeQL + secret scan) must pass, and review conversations must be
   resolved, before merge.

By contributing you agree your contributions are licensed under [GPL-3.0](LICENSE).
