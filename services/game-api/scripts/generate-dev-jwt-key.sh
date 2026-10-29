#!/usr/bin/env sh
# Generates a local ES256 key bundle for running game-api outside Docker, so guest tokens
# survive restarts. Writes the PKCS#8 private key followed by its public key, the format
# game-api reads from JWT_SIGNING_KEY_PATH. dev-keys/ and *.pem are gitignored.
set -eu
out="${1:-dev-keys/jwt-es256.pem}"
mkdir -p "$(dirname "$out")"
umask 077
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out "$out"
openssl ec -in "$out" -pubout 2>/dev/null >> "$out"
echo "Wrote $out. Set JWT_SIGNING_KEY_PATH=$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"
