#!/usr/bin/env bash
# Builds dnstt-client for Android as $OUT/libdnstt.so.
#
#   ABI=arm64-v8a OUT=build/engines/arm64-v8a scripts/engines/dnstt.sh
#
# dnstt is dedicated to the public domain (CC0 1.0, see COPYING in the module).
#
# dnstt-query-rate.patch adds a query rate cap to the client (-qps, default 4 per second, data and
# polls together): some networks block a client that sends more than about 5 DNS queries a second.
set -euo pipefail
. "$(dirname "$0")/common.sh"

MODULE=www.bamsoftware.com/git/dnstt.git
VERSION=v1.20260501.0   # tag commit 0c5c52a57d899c05428c116898941761a2ed83c2
SUM="h1:8i4tQWwBCrhsn6+c8fpjSIyaa+u0MOvdOa1FIxNOz6M="

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT
patch -d "$SRC" -p1 --forward --batch --silent < "$(dirname "$0")/dnstt-query-rate.patch"

build_main "$SRC" ./dnstt-client dnstt
