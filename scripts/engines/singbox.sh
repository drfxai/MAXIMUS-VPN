#!/usr/bin/env bash
# Official Android executables, pinned to each upstream release-asset SHA-256.
# Separate GPL-3.0-or-later program; source and license: THIRD_PARTY_NOTICES.md.
set -euo pipefail
: "${ABI:?set Android ABI}"
: "${OUT:?set output directory}"
version=1.14.4
case "$ABI" in
  arm64-v8a) arch=arm64; sha=fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f ;;
  armeabi-v7a) arch=arm; sha=a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9 ;;
  x86_64) arch=amd64; sha=e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac ;;
  *) echo "Unsupported Android ABI: $ABI" >&2; exit 2 ;;
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
