#!/usr/bin/env bash
# Builds lyrebird, Tor's pluggable transports (obfs4, Snowflake, WebTunnel, meek), for Android as
# $OUT/liblyrebird.so. Tor itself comes from the tor-android library (BSD-3-Clause).
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/lyrebird.sh
#
# License: BSD-3-Clause, with GPL-3.0 parts (see THIRD_PARTY_NOTICES.md). It runs as its own process,
# started by Tor. Source: https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird
set -euo pipefail
# lyrebird 0.10.0 needs go >= 1.26.10 (its go.mod); common.sh keeps go1.26.8 for the others.
export GOTOOLCHAIN=go1.26.10
. "$(dirname "$0")/common.sh"

MODULE=gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird
VERSION=v0.0.0-20261104192811-59ac7e400c38   # tag 0.10.0, commit 59ac7e400c38 (2026-11-04)
SUM="h1:h9X9Tz+tNRIp4zjLqPR2KCkVZKZGFIQnQzmIlfEsRBI="

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT
# The WebRTC code (Snowflake) pulls in github.com/wlynxg/anet for Android, which reaches into
# net.zoneCache with //go:linkname; Go 1.23+ needs -checklinkname=0 for it (as for Psiphon).
build_main "$SRC" ./cmd/lyrebird lyrebird "-checklinkname=0"
