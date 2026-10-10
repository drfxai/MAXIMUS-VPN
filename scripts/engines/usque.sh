#!/usr/bin/env bash
# Builds usque (Cloudflare WARP over MASQUE) for Android as $OUT/libusque.so.
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/usque.sh
#
# usque speaks Cloudflare's MASQUE protocol (WireGuard tunnelled over HTTP/3 / QUIC) and offers a
# local SOCKS5 proxy, so it reaches a non-Iran Cloudflare exit without the WireGuard UDP that some
# networks block. It runs as its own process and the app only talks to it over a local SOCKS port.
# License: MPL-2.0. Source: https://github.com/Diniboy1123/usque/tree/v1.5.0
set -euo pipefail
. "$(dirname "$0")/common.sh"

MODULE=github.com/Diniboy1123/usque
VERSION=v1.5.0
SUM="h1:0cQtuGjKm73w5i2CEvvUSSE1lfPvsHIKrJzpVQdGcF8="

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT
build_main "$SRC" . usque "-X github.com/Diniboy1123/usque/cmd.Version=$VERSION"
