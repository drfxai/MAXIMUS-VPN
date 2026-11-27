#!/usr/bin/env bash
# Builds usque (Cloudflare WARP over MASQUE) for Android as $OUT/libusque.so.
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/usque.sh
#
# usque speaks Cloudflare's MASQUE protocol (WireGuard tunnelled over HTTP/3 / QUIC) and offers a
# local SOCKS5 proxy, so it reaches a non-Iran Cloudflare exit without the WireGuard UDP that some
# networks block. It runs as its own process and the app only talks to it over a local SOCKS port.
# License: MIT. Source: https://github.com/Diniboy1123/usque/tree/v4.2.1
#
# v4 tags keep the module path github.com/Diniboy1123/usque (no /v4 suffix), so the Go proxy refuses
# them as module versions. The source is fetched with git at the tag's commit, the commit is checked
# after checkout, and every dependency is checked against the tree's own go.sum (-mod=readonly).
set -euo pipefail
. "$(dirname "$0")/common.sh"

VERSION=v4.2.1
REV=6aa03fc97d12848dce34eedbd187fb1077b5d1ea   # tag v4.2.1 (2026-07-19)

SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
git -C "$SRC" init -q
git -C "$SRC" fetch -q --depth=1 https://github.com/Diniboy1123/usque.git "$REV"
git -C "$SRC" checkout -q --detach FETCH_HEAD
test "$(git -C "$SRC" rev-parse HEAD)" = "$REV"
mkdir -p "$OUT"
out="$(cd "$OUT" && pwd)/libusque.so"
(cd "$SRC" && GOFLAGS=-mod=readonly go build -trimpath -buildvcs=false \
  -ldflags "-s -w -buildid= -X github.com/Diniboy1123/usque/cmd.version=$VERSION -X github.com/Diniboy1123/usque/cmd.commit=$REV" -o "$out" .)
echo "Built $out ($(wc -c < "$out") bytes) for $ABI"
