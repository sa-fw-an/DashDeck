#!/usr/bin/env bash
# Builds, tests and signs a DashDeck Shields engine plugin.
#
#   scripts/shields-engine-release.sh <adblock-rust tag, e.g. v0.13.3> <output dir> [version label]
#
# The optional label overrides the reported engine version (e.g. 0.13.3-1 for a
# rebuild of the same upstream tag).
#
# Output: <out>/libdashdeck_brave_adblock-<version>.so and <out>/shields-engine.json.
# Upload both to the location dashdeck.shieldsEngineFeed points at. The app only
# installs a plugin whose manifest signature verifies against the public key it
# was built with, whose hash matches, and which passes the on-device self-tests.
set -euo pipefail

TAG="${1:?adblock-rust tag, e.g. v0.13.3}"
OUT="${2:?output directory}"
VERSION="${3:-${TAG#v}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="${DASHDECK_KEY_DIR:-$HOME/.dashdeck}/shields-engine-signing.pem"
TARGET=aarch64-linux-android
ABI=arm64-v8a
API_VERSION=2
NDK_VERSION=29.0.14206865
case "$(uname -s)" in Darwin) HOST_TAG=darwin-x86_64 ;; *) HOST_TAG=linux-x86_64 ;; esac
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK_LINKER="${CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER:-$SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android35-clang}"
[[ -x "$NDK_LINKER" ]] || { echo "NDK linker not found: $NDK_LINKER (set ANDROID_HOME)" >&2; exit 1; }
# Prefer a rustup toolchain (Homebrew's rust has no Android target).
[[ -d /opt/homebrew/opt/rustup/bin ]] && export PATH="/opt/homebrew/opt/rustup/bin:$PATH"

[[ -f "$KEY" ]] || { echo "No signing key at $KEY. Run scripts/shields-engine-keygen.sh first." >&2; exit 1; }

# Build in a scratch copy so the repo's pinned engine is untouched.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/native"
cp -R "$ROOT/native/brave-adblock" "$WORK/native/brave-adblock"
CRATE="$WORK/native/brave-adblock"
rm -rf "$CRATE/target"
sed -i.bak -E "s/(adblock = \{[^}]*tag = \")[^\"]+/\1$TAG/" "$CRATE/Cargo.toml"
sed -i.bak -E "s/^const ENGINE_VERSION: &str = \"[^\"]+\";/const ENGINE_VERSION: \&str = \"$VERSION\";/" "$CRATE/src/lib.rs"
grep -q "API_VERSION: jint = $API_VERSION;" "$CRATE/src/lib.rs" || { echo "API_VERSION in lib.rs is not $API_VERSION" >&2; exit 1; }
# The unit test reads filter lists from ../../app relative to the crate.
ln -s "$ROOT/app" "$WORK/app"

echo "== Self-tests (host) with adblock-rust $TAG" >&2
(cd "$CRATE" && cargo update -p adblock >/dev/null 2>&1 || true; cargo test --release)

echo "== Building $ABI plugin" >&2
(cd "$CRATE" && CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_LINKER" cargo build --release --target "$TARGET")

mkdir -p "$OUT"
SO_NAME="libdashdeck_brave_adblock-$VERSION.so"
cp "$CRATE/target/$TARGET/release/libdashdeck_brave_adblock.so" "$OUT/$SO_NAME"
SHA256="$( (sha256sum "$OUT/$SO_NAME" 2>/dev/null || shasum -a 256 "$OUT/$SO_NAME") | cut -d' ' -f1)"
SIZE="$(wc -c < "$OUT/$SO_NAME" | tr -d ' ')"

# Must match ShieldsEnginePlugin.signedPayload().
PAYLOAD="dashdeck-shields-engine|$API_VERSION|$VERSION|$ABI|$SO_NAME|$SHA256|$SIZE"
SIGNATURE="$(printf '%s' "$PAYLOAD" > "$WORK/payload" && openssl pkeyutl -sign -inkey "$KEY" -rawin -in "$WORK/payload" | base64 | tr -d '\n')"

cat > "$OUT/shields-engine.json" <<JSON
{
  "apiVersion": $API_VERSION,
  "engineVersion": "$VERSION",
  "abi": "$ABI",
  "file": "$SO_NAME",
  "sha256": "$SHA256",
  "size": $SIZE,
  "signature": "$SIGNATURE"
}
JSON
echo "== Signed plugin written to $OUT" >&2
cat "$OUT/shields-engine.json"
