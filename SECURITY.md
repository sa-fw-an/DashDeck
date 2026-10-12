# Security

## Reporting

Please report security issues privately through
[GitHub security advisories](https://github.com/sa-fw-an/DashDeck/security/advisories/new),
not in public issues. You will get a reply within a week.

## Supported versions

Only the latest release receives fixes.

## Design notes

- DashDeck has no analytics and sends no data of its own. Network requests come from the pages
  you open, filter list and engine updates (GitHub, easylist.to, ublockorigin.github.io), and the
  app update check (GitHub API).
- Shields engine plugins are native code. The app only loads a plugin whose manifest is signed
  with the project's Ed25519 key, whose SHA-256 matches, and which passes self-tests in an
  isolated process. Plugin files are stored read-only in the app's private storage.
- The touch control accessibility service only replays touches made on the mirrored car display.
  It reads no screen content.
