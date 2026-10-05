#!/usr/bin/env bash
# Builds Mihomo (MetaCubeX, GPL-3.0) for one Android ABI as $OUT/libmihomo.so.
# Source: https://github.com/MetaCubeX/mihomo/tree/v1.19.32
set -euo pipefail
source "$(dirname "$0")/common.sh"

MODULE="github.com/metacubex/mihomo"
VERSION="v1.19.32"
SUM="h1:uD7ZC3P77isWD554NNvtee65L+99+/C5hyc+Lk8rVEk="

: "${ABI:?ABI is required}" "${OUT:?OUT is required}"
src="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$src"' EXIT
mkdir -p "$OUT"
(cd "$src" && env CGO_ENABLED=0 GOOS=android $(go_arch_for_abi "$ABI") GOFLAGS=-mod=mod GOTOOLCHAIN=auto \
  go build -trimpath -ldflags "-s -w -buildid= -X github.com/metacubex/mihomo/constant.Version=$VERSION" \
  -o "$OUT/libmihomo.so" .)
echo "Built Mihomo $VERSION for $ABI"
