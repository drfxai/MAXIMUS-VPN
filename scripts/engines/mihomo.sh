#!/usr/bin/env bash
# Builds Mihomo (MetaCubeX) for Android as $OUT/libmihomo.so.
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/mihomo.sh
#
# License: GPL-3.0 (see THIRD_PARTY_NOTICES.md). The program runs as its own process and the app
# only talks to it over a local SOCKS port. Source: https://github.com/MetaCubeX/mihomo/tree/v1.19.34
set -euo pipefail
. "$(dirname "$0")/common.sh"

MODULE=github.com/metacubex/mihomo
VERSION=v1.19.34
SUM="h1:+nkzw4xRehvk2ldLHMelXszd0ES1ysE/ArL92KPfQno="

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT
build_main "$SRC" . mihomo "-X github.com/metacubex/mihomo/constant.Version=$VERSION"
