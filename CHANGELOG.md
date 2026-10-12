# Changelog

All notable changes to DashDeck. Versions follow [Semantic Versioning](https://semver.org).

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
