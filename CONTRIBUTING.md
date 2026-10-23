# Contributing to Hermes Local

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

1. Start each feature in its own worktree off `main`:
   `git worktree add ../hermes-my-change -b feature/my-change origin/main`. See
   [Worktrees and promotion](#worktrees-and-promotion) below for the full workflow, including the `test` and `stage` branches
   that changes pass through before `main`.
2. Open a PR **into the next branch in the chain** (`test`, then `stage`, then `main`). Keep it
   focused and incremental; describe what changed and how you verified it.
3. CI (build + unit tests + CodeQL + secret scan) must pass, and review conversations must be
   resolved, before merge.

## Worktrees and promotion
## One worktree per feature

Every feature, fix or experiment gets its own branch **and its own git worktree**. Never work
on `main`, `stage` or `test` directly, and never on a detached HEAD.

```bash
git fetch origin
git worktree add ../hermes-<slug> -b feat/<slug> origin/main   # feat/ fix/ chore/ docs/
cd ../hermes-<slug>
```

- Creating a feature branch and worktree needs no approval. Do it as soon as new work starts.
- If a request is unrelated to the branch you are on, start a new worktree for it instead of
  stacking it on the current one. One feature per branch keeps PRs small and revertable.
- Check `git worktree list` first; reuse a worktree if the feature already has one.
- Remove a worktree once its branch is merged: `git worktree remove ../hermes-<slug>`.

### Promotion: feature -> test -> stage -> main

| Branch  | Purpose                                                          |
|---------|------------------------------------------------------------------|
| `test`  | Features being tried on a device. May be rough.                  |
| `stage` | Features that passed on-device testing, awaiting release.        |
| `main`  | Production. Always builds and passes CI.                         |

1. Build on a feature branch; install it on the phone straight from that worktree
   (`scripts/install-release.sh`). No merge needed to try a change.
2. When it should be tried alongside other work, merge the feature into `test`.
3. When it has been verified on the device, promote `test` -> `stage`, then `stage` -> `main`.
4. Prefer a PR for each step. Never force-push any of these branches.

Pushing a feature branch, merging into `test`/`stage`/`main`, and opening or merging PRs happen
when the user asks. Creating branches and worktrees does not need asking.

### Commits

Conventional Commits (`feat:`, `fix:`, `docs:`, ...), as in CONTRIBUTING.md. No AI or assistant
attribution in commits or PRs. Stage files by name, not `git add -A`, so unrelated work in the
tree is not swept in.

### Checks

- `./gradlew :app:compileDebugKotlin` before committing; unit tests for logic changes.
- The pre-push hook runs a secret scan: `git config core.hooksPath .githooks` once per clone.

By contributing you agree your contributions are licensed under [GPL-3.0](LICENSE).
