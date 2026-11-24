#!/usr/bin/env bash
# Builds Shir o Khorshid's tunnel core (the community Psiphon fork for Iran) as $OUT/libshirokhorshid.so.
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/shirokhorshid.sh
#
# Source: https://github.com/shirokhorshid/psiphon-tunnel-core, branch `shirokhorshid`, pinned to $REV below.
# License: GPL-3.0 (THIRD_PARTY_NOTICES.md). The program runs as its own process and the app only talks to it over
# a local SOCKS port. The fork keeps upstream's module path (github.com/Psiphon-Labs/psiphon-tunnel-core), so it
# cannot be fetched through the Go proxy: the exact commit is fetched with git and checked, and it is built from the
# repository's own vendor/ directory (go.sum still checks every vendored module). The corresponding source, with the
# one MAXIMUS patch, is staged as build/transport-offer/Shirokhorshid-source.tar.gz for the release.
set -euo pipefail
. "$(dirname "$0")/common.sh"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

REPO=https://github.com/shirokhorshid/psiphon-tunnel-core.git
REV=520f37c980df6909830ad541b42c305e648ed60d   # "cdn gates relaxed" (2026-06-18); merges upstream 4d6eb0e8
PATCH="$TASK_ROOT/scripts/engines/psiphon-link-dns.py"
patch_hash=$(sha256sum "$PATCH" | cut -c1-12)
source_dir="$TASK_ROOT/build/shirokhorshid-source-${REV:0:12}-$patch_hash"

if [[ ! -f "$source_dir/UPSTREAM_REVISION" ]]; then
  rm -rf "$source_dir"
  mkdir -p "$source_dir"
  git -C "$source_dir" init -q
  fetched=""
  for attempt in 1 2 3; do
    if git -C "$source_dir" fetch -q --depth=1 "$REPO" "$REV"; then fetched=1; break; fi
    sleep $((attempt * 5))
  done
  [[ -n "$fetched" ]] || { echo "Could not fetch $REPO at $REV" >&2; exit 1; }
  git -C "$source_dir" checkout -q --detach FETCH_HEAD
  test "$(git -C "$source_dir" rev-parse HEAD)" = "$REV"
  test -f "$source_dir/vendor/modules.txt"
  grep -q '^module github.com/Psiphon-Labs/psiphon-tunnel-core$' "$source_dir/go.mod"
  python3 "$PATCH" "$source_dir/ConsoleClient/main.go"
  cp "$PATCH" "$source_dir/MAXIMUS_PATCH.py"
  printf '%s\n%s\n' "$REV" "$REPO" > "$source_dir/UPSTREAM_REVISION"
fi

# github.com/wlynxg/anet reaches into net.zoneCache with //go:linkname (see scripts/engines/psiphon.sh).
BI=github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo
mkdir -p "$OUT"
out="$(cd "$OUT" && pwd)/libshirokhorshid.so"
(cd "$source_dir" && GOFLAGS=-mod=vendor go build -trimpath -buildvcs=false \
  -ldflags "-s -w -buildid= -checklinkname=0 -X $BI.buildRepo=https://github.com/shirokhorshid/psiphon-tunnel-core -X $BI.buildRev=$REV" \
  -o "$out" ./ConsoleClient)
echo "Built $out ($(wc -c < "$out") bytes) for $ABI"

mkdir -p "$TASK_ROOT/build/transport-offer"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --exclude=.git -C "$source_dir" \
  -czf "$TASK_ROOT/build/transport-offer/Shirokhorshid-source.tar.gz" .
