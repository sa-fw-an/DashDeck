# Agent guide

Instructions for AI coding agents working in this repository. Humans: see CONTRIBUTING.md.

## Project

DashDeck is an Android Auto web browser (Kotlin, Android Views) with Brave's adblock-rust
engine (`native/brave-adblock`, Rust via JNI) and phone screen sharing to the car display.

- Build: `./gradlew :app:assembleDebug` (JDK 21; Gradle builds the Rust engine with cargo)
- Check: `./gradlew :app:lintDebug :app:testDebugUnitTest` and `cargo test --release` in `native/brave-adblock`
- Car testing: Google's Desktop Head Unit, see docs/android-auto.md

## Rules

- **Commits:** Conventional Commits (`feat(scope): …`, `fix(scope): …`), checked in CI.
  The maintainer is the sole author: never add `Co-Authored-By`, "Generated with" or any AI attribution
  to commits, pull requests, tags or releases.
- **Privacy:** never commit or publish screenshots of maps, the Android Auto dashboard, phone status
  bars or home-screen widgets, or anything revealing a location, device identifier, local path or
  personal email. README images show DashDeck's UI only, with the status bar cropped.
- **Secrets:** signing keys live outside the repo (`~/.dashdeck/`) and in GitHub secrets. Never commit,
  print or log them, or `local.properties`, keystores, `.pem` files or tokens.
- **Comments:** short, and only where the reason isn't obvious from the code.
- **Branches:** `main` is protected; work on a branch and open a pull request. Merge with rebase.

## Before every push

- [ ] `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` passes
- [ ] `cargo test --release` passes if `native/` changed
- [ ] Tested on a phone, and on the car display (or Desktop Head Unit) if the car UI, mirroring or
      Shields changed
- [ ] `gitleaks git . --redact` and `gitleaks dir . --redact` report no leaks
- [ ] `git diff --cached` reviewed: no keys, local paths, personal data or debugging leftovers
- [ ] New or changed images inspected for location, status bar and personal data
- [ ] Commit messages follow Conventional Commits, with no AI trailers

## Releasing

Follow docs/releasing.md. In short:

- [ ] Everything above passes on `main`
- [ ] `versionCode` incremented and `versionName` set in `app/build.gradle.kts`
- [ ] `## [x.y.z] - YYYY-MM-DD` section added to `CHANGELOG.md`
- [ ] Commit `chore(release): x.y.z`, then an annotated tag `vx.y.z`; push both
- [ ] The Release workflow passes and the release has `DashDeck-x.y.z.apk` and its `.sha256`
- [ ] The published APK installs and runs on a phone and in Android Auto
