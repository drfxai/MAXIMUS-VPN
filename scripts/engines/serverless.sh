#!/usr/bin/env bash
# Patterniha's separate Xray fork: required for v57 finalmask and OOB semantics.
set -euo pipefail
export GOTOOLCHAIN=go1.27.0
source "$(dirname "$0")/common.sh"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REV=f9903959b9ae47538f5c7100f6416fb9fbcd2317
source_dir="$TASK_ROOT/build/serverless-source"
if [[ ! -f "$source_dir/UPSTREAM_REVISION" ]]; then
 mkdir -p "$source_dir"
 git -C "$source_dir" init -q
 git -C "$source_dir" fetch --depth=1 https://github.com/patterniha/Xray-core.git "$REV"
 git -C "$source_dir" checkout --detach FETCH_HEAD
 test "$(git -C "$source_dir" rev-parse HEAD)" = "$REV"
 printf '%s\n' "$REV" > "$source_dir/UPSTREAM_REVISION"
fi
mkdir -p "$OUT" "$TASK_ROOT/build/transport-offer"
(cd "$source_dir" && go build -mod=readonly -trimpath -buildmode=pie -ldflags='-s -w -buildid= -checklinkname=0' -o "$OUT/libserverless.so" ./main)
# Include dependency sources for a reproducible corresponding-source offer.
if [[ ! -d "$source_dir/vendor" ]]; then (cd "$source_dir" && go mod vendor); fi
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --exclude=.git -C "$source_dir" \
 -czf "$TASK_ROOT/build/transport-offer/Serverless-source.tar.gz" .
