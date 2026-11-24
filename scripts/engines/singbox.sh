#!/usr/bin/env bash
# Official Android executables, pinned to each upstream release-asset SHA-256.
# Separate GPL-3.0-or-later program; source and license: THIRD_PARTY_NOTICES.md.
#
# Pinned releases: 1.14.4 (current), 1.14.3 (previous). Every ABI must be pinned for every
# listed release; add the version to docs/PINS.md in the same change. A rollback builds the
# previous pin without editing this file:
#   SINGBOX_VERSION=1.14.3 bash scripts/build-engines.sh
set -euo pipefail
: "${ABI:?set Android ABI}"
: "${OUT:?set output directory}"
version="${SINGBOX_VERSION:-1.14.4}"
case "$ABI" in
  arm64-v8a) arch=arm64 ;;
  armeabi-v7a) arch=arm ;;
  x86_64) arch=amd64 ;;
  *) echo "Unsupported Android ABI: $ABI (engine ABIs: arm64-v8a, armeabi-v7a, x86_64; see scripts/engine-abis.txt)" >&2; exit 2 ;;
esac
case "$version:$ABI" in
  # 1.14.4 (current) - https://github.com/SagerNet/sing-box/releases/tag/v1.14.4
  1.14.4:arm64-v8a) sha=fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f ;;
  1.14.4:armeabi-v7a) sha=a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9 ;;
  1.14.4:x86_64) sha=e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac ;;
  # 1.14.3 (previous) - rollback target, https://github.com/SagerNet/sing-box/releases/tag/v1.14.3
  1.14.3:arm64-v8a) sha=149569b5dbf01958e6598beb3a13078d6fc0e353ef06f4aff85f5f28bc23593b ;;
  1.14.3:armeabi-v7a) sha=0c0df4fbf85656896d5b604e9eb4e556c1a3f68dd615b0ece3f9f6bd5adab5d6 ;;
  1.14.3:x86_64) sha=a84a5aea86ad0588b76e23051e9e59405a7ed52358acfb9710cfaa055fe60e4a ;;
  *) echo "No pinned sing-box $version for ABI $ABI. Pinned versions: 1.14.4 (current), 1.14.3 (previous); see docs/PINS.md." >&2; exit 2 ;;
esac
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
archive="sing-box-$version-android-$arch"
curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 20 --max-time 300 \
  "https://github.com/SagerNet/sing-box/releases/download/v$version/$archive.tar.gz" -o "$work/engine.tar.gz"
printf '%s  %s\n' "$sha" "$work/engine.tar.gz" | sha256sum --check --status
tar -xOzf "$work/engine.tar.gz" "$archive/sing-box" > "$work/libsingbox.so"
test -s "$work/libsingbox.so"
mkdir -p "$OUT"
install -m 0755 "$work/libsingbox.so" "$OUT/libsingbox.so"
