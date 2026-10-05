#!/usr/bin/env bash
# Builds lyrebird, Tor's pluggable transports (obfs4, Snowflake, WebTunnel, meek), for Android as
# $OUT/liblyrebird.so. Tor itself comes from the tor-android library (BSD-3-Clause).
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/lyrebird.sh
#
# License: BSD-3-Clause, with GPL-3.0 parts (see THIRD_PARTY_NOTICES.md). It runs as its own process,
# started by Tor. Source: https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird
set -euo pipefail
. "$(dirname "$0")/common.sh"

MODULE=gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird
VERSION=v0.0.0-20260921142919-75ef9b2c1f18   # commit 75ef9b2c1f18 (2026-09-21)
SUM="h1:yYIGFPSbU7zzI1HCwgIhTuYi5kiOg9JpvvC7WaQ5Xu4="

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT
# The WebRTC code (Snowflake) pulls in github.com/wlynxg/anet for Android, which reaches into
# net.zoneCache with //go:linkname; Go 1.23+ needs -checklinkname=0 for it (as for Psiphon).
build_main "$SRC" ./cmd/lyrebird lyrebird "-checklinkname=0"
