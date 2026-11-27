#!/usr/bin/env bash
# Build upstream AmneziaWG's userspace executable for the APK.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

revision=08fe3d33bbb116be09a6183d02609311b8b0c9e8
source_dir="$(mktemp -d)"
trap 'rm -rf "$source_dir"' EXIT

git -C "$source_dir" init -q
git -C "$source_dir" fetch --depth=1 https://github.com/amnezia-vpn/amneziawg-go.git "$revision"
git -C "$source_dir" checkout --detach FETCH_HEAD
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"

build_main "$source_dir" . amneziawg

# The CLI is retained for packaging. Android calls this JNI form of the same pinned core.
mkdir -p "$source_dir/cmd/maximus-android"
cp "$(dirname "${BASH_SOURCE[0]}")/../amneziawg/"{android.go,android.c} "$source_dir/cmd/maximus-android/"
library="$(cd "$OUT" && pwd)/libamneziawg_android.so"
(cd "$source_dir" && GOFLAGS=-mod=readonly go build -trimpath -buildvcs=false \
  -buildmode=c-shared -ldflags '-s -w -buildid= -extldflags "-Wl,-z,max-page-size=16384"' \
  -o "$library" ./cmd/maximus-android)
rm -f "${library%.so}.h"
