#!/usr/bin/env bash
# Builds Psiphon's ConsoleClient for Android as $OUT/libpsiphon.so.
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/psiphon.sh
#
# License: GPL-3.0 (see docs/engines/psiphon-dnstt.md for the source offer). The program runs as
# its own process and the app only talks to it over a local SOCKS port.
set -euo pipefail
. "$(dirname "$0")/common.sh"

MODULE=github.com/Psiphon-Labs/psiphon-tunnel-core
VERSION=v0.0.14-beta-ios.0.20260928190446-4d6eb0e8a7e9   # commit 4d6eb0e8a7e9 (2026-09-28)
SUM="h1:Ay/hVsv+zLoml03OvdAuzQXQ64t4N9DW0Sc8XG28BNE="
REV=4d6eb0e8a7e91d3f9a615f70ef65668b8b3595ec

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT

# go.mod points github.com/pion/dtls/v2 at ./replace/dtls, a nested module that the published
# module zip does not contain. That patched copy only matters with the
# PSIPHON_ENABLE_REFRACTION_NETWORKING build tag, which this build does not set, so the upstream
# release recorded in go.mod is used instead (its hash is checked against sum.golang.org).
if [ ! -d "$SRC/replace/dtls" ]; then
  (cd "$SRC" && GOFLAGS= go mod edit -dropreplace=github.com/pion/dtls/v2)
fi

# github.com/wlynxg/anet (pulled in for Android by the WebRTC code) reaches into net.zoneCache with
# //go:linkname, which Go 1.23+ refuses unless -checklinkname=0 is given (as anet's README says).
# The struct it mirrors is unchanged in Go 1.26's net package.
BI=github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo
build_main "$SRC" ./ConsoleClient psiphon \
  "-checklinkname=0 -X $BI.buildRepo=https://github.com/Psiphon-Labs/psiphon-tunnel-core -X $BI.buildRev=$REV"
