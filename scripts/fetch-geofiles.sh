#!/usr/bin/env bash
# Re-downloads the bundled Iran GeoFiles from Chocolate4U/Iran-v2ray-rules (GPL-3.0 data files) and
# verifies each one twice: against the release's own .sha256sum file and against the hash pinned here.
# To move to a newer release, change TAG, run with --update-pins, review the diff, and commit.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/geo"
TAG="202611211258"
BASE="https://github.com/Chocolate4U/Iran-v2ray-rules/releases/download/$TAG"

declare -A PINNED=(
  [geoip-lite.dat]="2b5073fd9f9335c94899fb5d5628f92dccb70b010507f6f5f6974f92c77bcb1f"
  [geosite-lite.dat]="8e63e42bc9139f123e749e69a21d03f459463c34c41da6df78c549e539b6343f"
  [geoip-services.dat]="93971c1c668652ac977f7b41448dfeeee68b60d485361c46f8d533f24e969c33"
)
FILES=(geoip-lite.dat geosite-lite.dat geoip-services.dat)

UPDATE_PINS=0
[[ "${1:-}" == "--update-pins" ]] && UPDATE_PINS=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

for name in "${FILES[@]}"; do
  curl --fail --silent --show-error --location --retry 3 --retry-delay 2 "$BASE/$name" --output "$TMP/$name"
  curl --fail --silent --show-error --location --retry 3 --retry-delay 2 "$BASE/$name.sha256sum" --output "$TMP/$name.sha256sum"
  published="$(awk '{print $1; exit}' "$TMP/$name.sha256sum")"
  actual="$(sha256sum "$TMP/$name" | cut -d' ' -f1)"
  [[ "$actual" == "$published" ]] || { echo "$name: hash $actual does not match release sha256sum $published" >&2; exit 1; }
  if [[ "$UPDATE_PINS" == 0 && "$actual" != "${PINNED[$name]}" ]]; then
    echo "$name: hash $actual does not match pinned ${PINNED[$name]} (use --update-pins after changing TAG)" >&2
    exit 1
  fi
  PINNED[$name]="$actual"
done

for name in "${FILES[@]}"; do mv "$TMP/$name" "$DEST/$name"; done

# Manifest read by the app (GeoAssets) to decide whether the copy in filesDir is current.
{
  echo "{"
  echo "  \"source\": \"Chocolate4U/Iran-v2ray-rules\","
  echo "  \"license\": \"GPL-3.0\","
  echo "  \"tag\": \"$TAG\","
  echo "  \"files\": {"
  last=$(( ${#FILES[@]} - 1 ))
  for i in "${!FILES[@]}"; do
    n="${FILES[$i]}"; sep=","; [[ $i == "$last" ]] && sep=""
    echo "    \"$n\": \"${PINNED[$n]}\"$sep"
  done
  echo "  }"
  echo "}"
} > "$DEST/manifest.json"

echo "GeoFiles $TAG verified into $DEST"
[[ "$UPDATE_PINS" == 1 ]] && { echo "New pins:"; for n in "${FILES[@]}"; do echo "  [$n]=\"${PINNED[$n]}\""; done; }
exit 0
