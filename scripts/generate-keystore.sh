#!/usr/bin/env bash
#
# ShareThis — One-Click Release Keystore Generator (Linux / macOS)
#
# Generates a release keystore (.jks) and prints the single-line Base64 string
# you paste into the GitHub secret ANDROID_KEYSTORE_BASE64. Also prints the
# other three secrets and a local-signing snippet for ~/.gradle/gradle.properties.
#
# Usage:
#   ./scripts/generate-keystore.sh [options]
#
# Options:
#   --output FILE        Keystore path (default: sharethis-release.jks)
#   --alias NAME         Key alias (default: upload)
#   --storepass PASS     Keystore password (default: random 20-char)
#   --keypass PASS       Key password (default: same as --storepass)
#   --dname DN           Distinguished name (default: CN=ShareThis, OU=App, O=ShareThis, L=City, ST=State, C=IN)
#   --yes, -y            Overwrite existing keystore without asking
#   --help, -h           Show this help
#
# Examples:
#   ./scripts/generate-keystore.sh
#   ./scripts/generate-keystore.sh --alias upload --storepass 'MyStrongPass123!' --yes
#
# Requirements: JDK's `keytool` on PATH (any JDK 8+).
#
set -euo pipefail

OUTPUT="sharethis-release.jks"
ALIAS="upload"
STOREPASS=""
KEYPASS=""
DNAME="CN=ShareThis, OU=App, O=ShareThis, L=City, ST=State, C=IN"
ASSUME_YES=0

usage() {
  # Print the header comment block (everything up to `set -euo pipefail`).
  awk '/^set -euo pipefail/{exit} NR>=3{print}' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --output)    OUTPUT="$2"; shift 2 ;;
    --alias)     ALIAS="$2"; shift 2 ;;
    --storepass) STOREPASS="$2"; shift 2 ;;
    --keypass)   KEYPASS="$2"; shift 2 ;;
    --dname)     DNAME="$2"; shift 2 ;;
    --yes|-y)    ASSUME_YES=1; shift ;;
    --help|-h)   usage; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------- prerequisites
if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: 'keytool' not found. Install any JDK (8+) and retry." >&2
  echo "  Ubuntu/Debian: sudo apt install default-jdk" >&2
  echo "  macOS:         brew install openjdk" >&2
  exit 1
fi

# ------------------------------------------------------- passwords (or random)
rand_pass() {
  # 20 URL-safe chars from /dev/urandom (portable, no openssl dependency)
  LC_ALL=C tr -dc 'A-Za-z0-9_-' < /dev/urandom | head -c 20
  echo
}

if [ -z "$STOREPASS" ]; then
  STOREPASS="$(rand_pass)"
  echo "(generated random keystore password — shown below, save it!)"
fi
if [ -z "$KEYPASS" ]; then
  KEYPASS="$STOREPASS"
fi

# ------------------------------------------------------------- overwrite guard
if [ -f "$OUTPUT" ] && [ "$ASSUME_YES" -ne 1 ]; then
  printf "File '%s' already exists. Overwrite? [y/N] " "$OUTPUT"
  read -r reply < /dev/tty || reply=""
  case "$reply" in
    [yY][eE][sS]|[yY]) ;;
    *) echo "Aborted — existing keystore left untouched."; exit 1 ;;
  esac
fi

# ------------------------------------------------------------------ generation
echo "Generating keystore '$OUTPUT' (alias '$ALIAS')..."
keytool -genkeypair -v \
  -keystore "$OUTPUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STOREPASS" -keypass "$KEYPASS" \
  -dname "$DNAME"

echo
echo "Verifying..."
keytool -list -v -keystore "$OUTPUT" -storepass "$STOREPASS" -alias "$ALIAS" | head -20

# ------------------------------------------------ portable single-line base64
# NOTE: `base64 -w0` is GNU-only and fails on macOS, so pipe through tr instead.
B64_FILE="${OUTPUT}.base64.txt"
base64 "$OUTPUT" | tr -d '\n\r' > "$B64_FILE"
echo >> "$B64_FILE"

# Best-effort clipboard copy
if command -v pbcopy >/dev/null 2>&1; then
  tr -d '\n\r' < "$B64_FILE" | pbcopy && echo "(Base64 copied to clipboard via pbcopy)"
elif command -v xclip >/dev/null 2>&1; then
  tr -d '\n\r' < "$B64_FILE" | xclip -selection clipboard && echo "(Base64 copied to clipboard via xclip)"
elif command -v xsel >/dev/null 2>&1; then
  tr -d '\n\r' < "$B64_FILE" | xsel --clipboard --input && echo "(Base64 copied to clipboard via xsel)"
fi

B64_LEN=$(wc -c < "$B64_FILE" | tr -d ' ')

# ---------------------------------------------------------------------- report
cat <<EOF

======================================================================
 DONE! Keystore ready: $OUTPUT
======================================================================

GitHub Repository Secrets  (Settings -> Secrets and variables -> Actions):

  ANDROID_KEYSTORE_BASE64 .... <contents of $B64_FILE> ($B64_LEN chars, single line)
  ANDROID_KEYSTORE_PASSWORD .. $STOREPASS
  ANDROID_KEY_ALIAS .......... $ALIAS
  ANDROID_KEY_PASSWORD ....... $KEYPASS

The Base64 file was also saved to: $B64_FILE

Optional local signing — append to ~/.gradle/gradle.properties:

  KEYSTORE_PATH=$PWD/$OUTPUT
  KEYSTORE_PASSWORD=$STOREPASS
  KEY_ALIAS=$ALIAS
  KEY_PASSWORD=$KEYPASS

Next steps:
  1. BACK UP '$OUTPUT' somewhere safe (USB drive / password manager).
     If you lose it, you can NEVER update your Play Store listing again.
  2. Paste the 4 secrets above into GitHub (paste the .base64.txt CONTENT
     as ANDROID_KEYSTORE_BASE64 — not the file itself).
  3. Tag a release:  git tag v1.0.0 && git push origin v1.0.0

SECURITY: never commit the .jks or .base64.txt — both are git-ignored.
======================================================================
EOF
