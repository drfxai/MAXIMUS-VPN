#!/usr/bin/env bash
# Build upstream AmneziaWG's userspace executable for the APK.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

revision=735cffbcb3030644c519b2934bf2e199f19bbc98
source_dir="$(mktemp -d)"
trap 'rm -rf "$source_dir"' EXIT

git -C "$source_dir" init -q
git -C "$source_dir" fetch --depth=1 https://github.com/amnezia-vpn/amneziawg-go.git "$revision"
git -C "$source_dir" checkout --detach FETCH_HEAD
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"

build_main "$source_dir" . amneziawg
