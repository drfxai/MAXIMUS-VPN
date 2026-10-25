#!/usr/bin/env bash
# Separate Apache-2.0 Soar engine with an original MAXIMUS SOCKS adapter.
set -euo pipefail
. "$(dirname "$0")/common.sh"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REV=a8af533d82284f5e6ab13b706552596c108bacf2
SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
git -C "$SRC" init -q
git -C "$SRC" fetch --depth=1 https://github.com/getlantern/soar.git "$REV"
git -C "$SRC" checkout --detach FETCH_HEAD
test "$(git -C "$SRC" rev-parse HEAD)" = "$REV"
mkdir -p "$SRC/cmd/maximus-client" "$OUT"
cp "$TASK_ROOT"/tools/soar-client/*.go "$SRC/cmd/maximus-client/"
output="$(cd "$OUT" && pwd)/libsoar.so"
(cd "$SRC" && go mod download && go mod verify && GOFLAGS=-mod=readonly go build -trimpath -buildvcs=false -ldflags '-s -w -buildid=' -o "$output" ./cmd/maximus-client)
test -s "$TASK_ROOT/app/src/main/assets/licenses/soar-Apache-2.0.txt"
