#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$REPO_ROOT/app/libs"
DEST="$LIB_DIR/libXray.aar"
VERSION="v26.9.9"
SHA256="4998a8b56e4a78a164b5359d5690036f83da3b575465cea57ddf29c0149c345f"

STAMP="$DEST.verified"

# Reuse an AAR only if this script extracted it from the pinned archive: the stamp names the version,
# the archive hash and the AAR's own hash, and the AAR must still match it.
if [[ -s "$DEST" && -s "$STAMP" ]]; then
  read -r stamp_version stamp_zip stamp_aar < "$STAMP" || true
  if [[ "$stamp_version" == "$VERSION" && "$stamp_zip" == "$SHA256" ]] &&
     echo "$stamp_aar  $DEST" | sha256sum --check --status; then
    exit 0
  fi
fi
rm -f "$DEST" "$STAMP"

mkdir -p "$LIB_DIR"
ARCHIVE="$(mktemp)"
trap 'rm -f "$ARCHIVE" "$DEST.tmp"' EXIT
curl --fail --location --retry 3 --retry-delay 2 \
  "https://github.com/XTLS/libXray/releases/download/$VERSION/libxray-android.zip" \
  --output "$ARCHIVE"
echo "$SHA256  $ARCHIVE" | sha256sum --check --status

MEMBER="$(unzip -Z1 "$ARCHIVE" | awk '/(^|\/)libXray\.aar$/ { print; exit }')"
[[ -n "$MEMBER" ]] || { echo "libXray AAR not found in $VERSION archive" >&2; exit 1; }
unzip -p "$ARCHIVE" "$MEMBER" > "$DEST.tmp"
[[ -s "$DEST.tmp" ]] || { echo "Downloaded libXray AAR is empty" >&2; exit 1; }
mv "$DEST.tmp" "$DEST"
echo "$VERSION $SHA256 $(sha256sum "$DEST" | cut -d' ' -f1)" > "$STAMP"
echo "Prepared verified XTLS/libXray $VERSION AAR"
