#!/usr/bin/env bash
# Official Android executables, pinned to each upstream release-asset SHA-256.
# Separate GPL-3.0-or-later program; source and license: THIRD_PARTY_NOTICES.md.
#
# Pinned releases: 1.14.5 (current), 1.14.4 (previous). Every ABI must be pinned for every
# listed release; add the version to docs/PINS.md in the same change. A rollback builds the
# previous pin without editing this file:
#   SINGBOX_VERSION=1.14.4 bash scripts/build-engines.sh
set -euo pipefail
: "${ABI:?set Android ABI}"
: "${OUT:?set output directory}"
version="${SINGBOX_VERSION:-1.14.5}"
case "$ABI" in
  arm64-v8a) arch=arm64 ;;
  armeabi-v7a) arch=arm ;;
  x86_64) arch=amd64 ;;
  *) echo "Unsupported Android ABI: $ABI (engine ABIs: arm64-v8a, armeabi-v7a, x86_64; see scripts/engine-abis.txt)" >&2; exit 2 ;;
esac
case "$version:$ABI" in
  # 1.14.5 (current) - https://github.com/SagerNet/sing-box/releases/tag/v1.14.5
  1.14.5:arm64-v8a) sha=bab11cbc7bb26a51ee25c993ed1b477047ed717489035d6de05cf8bbc9b70787 ;;
  1.14.5:armeabi-v7a) sha=1df06beb61c3bd83213db62239dca28e0c4d524f9b2c3a2638db60da38d5d588 ;;
  1.14.5:x86_64) sha=89715aee160c9f397206a8f8382ac72814454b2effbcdb132855345163cdb650 ;;
  # 1.14.4 (previous) - rollback target, https://github.com/SagerNet/sing-box/releases/tag/v1.14.4
  1.14.4:arm64-v8a) sha=fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f ;;
  1.14.4:armeabi-v7a) sha=a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9 ;;
  1.14.4:x86_64) sha=e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac ;;
  *) echo "No pinned sing-box $version for ABI $ABI. Pinned versions: 1.14.5 (current), 1.14.4 (previous); see docs/PINS.md." >&2; exit 2 ;;
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
