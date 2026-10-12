# Releasing

## App releases

Run the "Before every push" and "Releasing" checklists in [AGENTS.md](../AGENTS.md) first.

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `## [x.y.z] - YYYY-MM-DD` section to `CHANGELOG.md`.
3. Commit (`chore(release): x.y.z`), then tag and push:

   ```bash
   git tag -a vx.y.z -m "DashDeck x.y.z"
   git push origin main vx.y.z
   ```

The `Release` workflow checks that the tag matches `versionName`, builds and signs
`DashDeck-x.y.z.apk`, and publishes it with its SHA-256 and the changelog section as notes.

### Repository secrets

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -i release.jks` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |
| `SHIELDS_ENGINE_SIGNING_KEY` | Contents of `~/.dashdeck/shields-engine-signing.pem` |

Create a release keystore once and keep a backup; an APK signed with a different key cannot
update an existing install:

```bash
keytool -genkeypair -v -keystore release.jks -alias dashdeck -keyalg RSA -keysize 4096 -validity 10000
```

## Shields engine plugins

The `Shields engine` workflow runs daily. When Brave tags a new adblock-rust release, it builds
the engine, runs the native tests, signs it and uploads it to the `shields-engine`
pre-release, where installed apps pick it up. Run it by hand from the Actions tab to rebuild a
specific tag.

Locally:

```bash
scripts/shields-engine-keygen.sh            # once; prints the public key for gradle.properties
scripts/shields-engine-release.sh v0.13.3 out
```

Keep a backup of `~/.dashdeck/shields-engine-signing.pem`. Without it, existing installs will
not accept new engine plugins.
