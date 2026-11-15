#!/usr/bin/env bash
# Build an experimental Radiance executable for Android without creating a second TUN.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export GOTOOLCHAIN=go1.26.10
. "$root/scripts/engines/common.sh"
# The Radiance dependency graph contains C++ code, so Go also needs the NDK C++ driver.
export CXX="${CC%clang}clang++"
test -x "$CXX"

rev=aced057665daaa4821e9f45e6133257969f4a8e7
source_dir="$(mktemp -d)"
trap 'rm -rf "$source_dir"' EXIT
git -C "$source_dir" init -q
git -C "$source_dir" fetch --depth=1 https://github.com/getlantern/radiance.git "$rev"
git -C "$source_dir" checkout --detach FETCH_HEAD
test "$(git -C "$source_dir" rev-parse HEAD)" = "$rev"
mkdir -p "$source_dir/cmd/maximus"
cp "$root/scripts/radiance/main.go" "$source_dir/cmd/maximus/main.go"

# The upstream mixed listener has no users in novpn mode. Require the local
# credentials supplied by the parent process; fail the build if upstream moved.
python3 - "$source_dir/vpn/boxoptions_novpn.go" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
needle = 'Options: &O.HTTPMixedInboundOptions{'
assert source.count(needle) == 1
source = source.replace('import (', 'import (\n\t"os"\n\t"github.com/sagernet/sing/common/auth"', 1)
source = source.replace(needle, needle + '\n\t\t\t\tUsers: []auth.User{{Username: os.Getenv("MAXIMUS_SOCKS_USER"), Password: os.Getenv("MAXIMUS_SOCKS_PASS")}},')
path.write_text(source)
PY
mkdir -p "$OUT"
output="$(cd "$OUT" && pwd)"
(
    cd "$source_dir"
    # Upstream Android builds use this flag for libbox's os.checkPidfdOnce link.
    go build -mod=readonly -trimpath -buildvcs=false \
        -tags novpn,with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_acme \
        -ldflags '-s -w -buildid= -checklinkname=0 -extldflags "-Wl,-z,max-page-size=16384 -static-libstdc++"' \
        -o "$output/libradiance.so" ./cmd/maximus
)

# Publish the corresponding source exactly once for all Android ABIs.
if [[ "$ABI" == arm64-v8a && -n "${RADIANCE_SOURCE_OFFER:-}" ]]; then
    mkdir -p "$RADIANCE_SOURCE_OFFER"
    source_offer="$(cd "$RADIANCE_SOURCE_OFFER" && pwd)"
    (cd "$source_dir" && go mod vendor)
    mkdir -p "$source_dir/maximus-build/scripts/engines" "$source_dir/maximus-build/scripts/radiance"
    cp "$root/scripts/engines/common.sh" "$source_dir/maximus-build/scripts/engines/"
    cp "$root/scripts/radiance/"{build-android.sh,main.go} "$source_dir/maximus-build/scripts/radiance/"
    printf '%s\n' "$rev" > "$source_dir/MAXIMUS_UPSTREAM_REVISION"
    tar --exclude=.git -czf "$source_offer/Radiance-source.tar.gz" -C "$source_dir" .
fi
