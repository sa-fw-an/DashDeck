# Contributing

Thanks for helping with DashDeck.

## Setup

- JDK 21, Android SDK with NDK 29.0.14206865
- Rust stable with `rustup target add aarch64-linux-android`
- An Android 15+ phone; for car testing, a car or Google's Desktop Head Unit
  (see [docs/android-auto.md](docs/android-auto.md))

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug :app:testDebugUnitTest
(cd native/brave-adblock && cargo test --release)
```

## Commits

Commits follow [Conventional Commits](https://www.conventionalcommits.org), checked in CI:

```
feat(shields): add per-site toggle
fix(mirror): map touches after rotation
docs: explain engine updates
```

Common types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `ci`, `chore`.
Scopes used in this repo: `browser`, `shields`, `mirror`, `car`, `ui`, `settings`, `engine`.

## Pull requests

- Keep each pull request to one change, and describe how you tested it.
- Test on the car display (or the Desktop Head Unit) when your change touches it.
- Comments should explain why, not what; keep them short.
