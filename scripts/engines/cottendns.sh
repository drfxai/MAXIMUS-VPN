#!/usr/bin/env bash
# CottenDNS is a separate MIT CLI; its Soar components retain Apache-2.0 notices.
# Build the reviewed source revision, never a prebuilt third-party APK or native library.
set -euo pipefail
. "$(dirname "$0")/common.sh"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REV=7db7b73658c791cce62300d29647e4d369d6e1e4
SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
git -C "$SRC" init -q
git -C "$SRC" fetch --depth=1 https://github.com/WhiteDNS/CottenDNS.git "$REV"
git -C "$SRC" checkout --detach FETCH_HEAD
test "$(git -C "$SRC" rev-parse HEAD)" = "$REV"
python3 "$TASK_ROOT/scripts/engines/cotten-strict-dns.py" "$SRC"
mkdir -p "$OUT"
output="$(cd "$OUT" && pwd)/libcottendns.so"
(cd "$SRC" && go mod download && go mod verify && \
  GOFLAGS=-mod=readonly go build -trimpath -buildvcs=false -ldflags '-s -w -buildid=' -o "$output" ./cmd/client)
# Include upstream licensing in the application assets and source distribution.
test -s "$TASK_ROOT/app/src/main/assets/licenses/cottendns-MIT.txt"
test -s "$TASK_ROOT/app/src/main/assets/licenses/cottendns-Soar-Apache-2.0.txt"
echo "Built CottenDNS $REV for $ABI"
