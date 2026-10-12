# Shields

Shields is DashDeck's ad and tracker blocker. It runs entirely on the phone.

## How it works

- **Engine:** Brave's [adblock-rust](https://github.com/brave/adblock-rust), the engine in the
  Brave browser, compiled into `libdashdeck_brave_adblock.so` (`native/brave-adblock`).
- **Filter lists:** EasyList, EasyPrivacy, uBlock Origin filters and uBlock Origin unbreak.
- **Network blocking:** every request a page makes is checked; blocked scripts get a harmless
  stand-in where a filter asks for one, so pages keep working.
- **Cosmetic filtering:** site-specific and generic element hiding, plus uBlock Origin
  scriptlets. Scriptlets are what remove YouTube ads.
- **Per site:** the shield in the toolbar shows how many requests were blocked on the page and
  turns Shields off for that site.

The engine is cached in serialized form, so it loads in a few hundred milliseconds.

## Filter list updates

Lists ship with the app and refresh in the background every four days, or on demand from
**Settings → Check for Shields updates**. New lists are downloaded to a staging folder, a
candidate engine is built from them and must pass a self-test suite (known ad and tracker URLs
blocked, normal content allowed, YouTube scriptlets present, cache reloads). Only then do they
replace the running engine. Otherwise the current lists stay in use.

## Engine updates

New adblock-rust versions are delivered as signed engine plugins:

1. The `shields-engine` workflow (or `scripts/shields-engine-release.sh`) builds a new engine,
   runs the native tests and signs a manifest with the project's Ed25519 key.
2. The app downloads the manifest, verifies the signature against the public key it was built
   with, checks the plugin's size and SHA-256, and checks its interface version.
3. The plugin is loaded and self-tested in a separate process (`:shieldsprobe`). A crash there
   cannot take down the browser.
4. Only a plugin that passes is activated. It takes effect the next time DashDeck starts.
5. If DashDeck fails to start cleanly twice with a plugin, it switches back to the engine
   built into the APK and tells the user.

The signing key never ships with the app; only its public half does (`gradle.properties`,
`dashdeck.shieldsEnginePublicKey`). Generate a key with `scripts/shields-engine-keygen.sh`.

## Regenerating bundled resources

`app/src/main/assets/shields/resources.json` holds uBlock Origin's scriptlets and redirect
resources in adblock-rust's format:

```bash
git clone --depth 1 https://github.com/gorhill/uBlock.git /tmp/ublock
echo '{"type":"module"}' > /tmp/ublock/package.json
node scripts/build-shields-resources.mjs /tmp/ublock
```
