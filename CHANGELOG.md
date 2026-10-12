# Changelog

All notable changes to DashDeck. Versions follow [Semantic Versioning](https://semver.org).

## [0.3.0] - 2026-10-12

### Added
- Voice search: a mic in the toolbar listens (through the car's microphone in Android Auto)
  and searches or opens what you say.
- Continue on phone: open the page from the car screen in DashDeck on the phone.
- Shields blocks cross-site cookies by default, with a per-site exception and a "Clear cookies
  for this site" button in the Shields panel.
- The start page shows how many ads and trackers Shields has blocked.

## [0.2.2] - 2026-10-12

### Fixed
- Restored AABrowser's copyright and GPLv3 notices in every source file derived from it, and its
  original license text and author credit in Settings. Credit to AABrowser by Kododake is shown in
  the app menu and README.

### Changed
- Shields engine bridge updated to jni 0.22; native errors are logged and handled instead of
  aborting the call.
- Android Gradle Plugin 9.4.1, Gradle 9.8.1, AndroidX AppCompat 1.8.0, ConstraintLayout 2.2.2
  and OkHttp 5.5.0.

## [0.2.1] - 2026-10-12

First public release of DashDeck, a browser for Android Auto based on AABrowser.

### Added
- Shields: ad, tracker and YouTube ad blocking with Brave's adblock-rust engine and EasyList,
  EasyPrivacy and uBlock Origin filters, including element hiding and scriptlets.
- Shields updates: filter lists refresh every four days and are self-tested before use.
  Signed engine updates are tested in a separate process, with automatic fallback to the
  built-in engine.
- Per-site Shields toggle and blocked-request count in the toolbar.
- Phone screen sharing to the car display, with live touch control (taps, scrolling, dragging).
- Continue on car screen: send the page open on the phone to the car.
- Themes: Midnight, Graphite, Daylight and Match phone, with a circular reveal when switching.
- Quick links: add, rename, edit, remove and reorder up to eight sites.
- App update check against GitHub releases.

### Changed
- New design: flat surfaces, square corners, a top toolbar and a two-column car layout.
- New app icon.

### Fixed
- Web pages no longer reserve space for Android Auto's system bar, which made sites such as
  YouTube cover part of the screen with a blurred bar.
- The phone app no longer keeps the car display's layout after Android Auto was used.
- Back in the car steps back through page history.
