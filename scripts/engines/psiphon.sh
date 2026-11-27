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
VERSION=v0.0.14-beta-ios.0.20261126204245-9544cec63f8d   # commit 9544cec63f8d (master, 2026-11-26)
SUM="h1:pT4QzCjc6SEz74U4hSz4P/BOPDjsz47nLBD++rohlrg="
REV=9544cec63f8d7d1d5a8d284eebd2ebeb560df75f

SRC="$(fetch_module "$MODULE" "$VERSION" "$SUM")"
trap 'rm -rf "$SRC"' EXIT

# ConsoleClient only wires a DNS provider in its standalone TUN mode. MAXIMUS owns
# the Android TUN and supplies DNS from the underlying link to the SOCKS worker.
python3 - "$SRC/ConsoleClient/main.go" <<'PYTHON'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
source = path.read_text()
anchor = "\t// All config fields should be set before calling Commit."
if source.count(anchor) != 1:
    raise SystemExit("Psiphon DNS provider patch no longer matches ConsoleClient")
source = source.replace(anchor, """\tif config.DNSServerGetter == nil {
\t\tvar linkDNS []string
\t\tfor _, address := range strings.Split(os.Getenv("MAXIMUS_PSIPHON_LINK_DNS"), ",") {
\t\t\tif ip := net.ParseIP(address); ip != nil && !ip.IsUnspecified() && !ip.IsLoopback() {
\t\t\t\tlinkDNS = append(linkDNS, ip.String())
\t\t\t}
\t\t}
\t\tif len(linkDNS) > 0 {
\t\t\tconfig.DNSServerGetter = &tunProvider{dnsServers: linkDNS}
\t\t}
\t}

""" + anchor)
path.write_text(source.replace('\t"io/ioutil"\n', '\t"io/ioutil"\n\t"net"\n', 1))
PYTHON

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
