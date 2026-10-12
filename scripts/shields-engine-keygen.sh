#!/usr/bin/env bash
# Creates the Ed25519 key that signs DashDeck Shields engine plugins.
# The private key never leaves ~/.dashdeck; the printed public key goes into
# gradle.properties as dashdeck.shieldsEnginePublicKey.
set -euo pipefail

KEY_DIR="${DASHDECK_KEY_DIR:-$HOME/.dashdeck}"
KEY="$KEY_DIR/shields-engine-signing.pem"

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
if [[ -e "$KEY" ]]; then
  echo "Signing key already exists: $KEY" >&2
else
  openssl genpkey -algorithm ed25519 -out "$KEY"
  chmod 600 "$KEY"
  echo "Created signing key: $KEY" >&2
fi

echo "Public key (dashdeck.shieldsEnginePublicKey):" >&2
openssl pkey -in "$KEY" -pubout -outform DER | base64
