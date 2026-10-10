#!/usr/bin/env bash
# Builds the Linux server programs the Panels Install Center puts on a user's own server:
#   dnstt-server  (DNS tunnel, public domain)          www.bamsoftware.com/git/dnstt.git
#   hysteria      (Hysteria2 server, MIT)              github.com/apernet/hysteria/app/v2
#   maximus-socks (loopback SOCKS5 behind dnstt-server) tools/server-tools/socks (this repo)
#   xray          (Xray-core, MPL-2.0, Maximus Tunnel)   github.com/xtls/xray-core (tag v26.9.9)
# for linux/amd64 and linux/arm64, into $OUT (default build/server-tools).
#
# Every module is fetched through the Go proxy, checked against the Go checksum database and
# against the hash pinned below. The build is reproducible (fixed toolchain, -trimpath, no build
# id), so the output must match tools/server-tools/SHA256SUMS byte for byte; the app pins the same
# hashes and the install scripts refuse any other file. Run with VERIFY=0 only to print new sums
# after changing a version here.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT:-$ROOT/build/server-tools}"
VERIFY="${VERIFY:-1}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.8}"
if [ "${GONOSUMDB:-}${GOPRIVATE:-}${GOINSECURE:-}" != "" ] || [ "${GOSUMDB:-}" = "off" ]; then
  echo "Refusing to build with the Go checksum database turned off for any module" >&2
  exit 2
fi

DNSTT_MODULE=www.bamsoftware.com/git/dnstt.git
DNSTT_VERSION=v1.20260501.0
DNSTT_SUM="h1:8i4tQWwBCrhsn6+c8fpjSIyaa+u0MOvdOa1FIxNOz6M="

HY_VERSION=v2.13.0

# Xray-core's go.mod keeps the v1 module path, so its v26.9.9 tag is fetched by commit
# (52a412d9e2f5, the same release as the app's libXray). It needs a newer Go than the rest.
XRAY_MODULE=github.com/xtls/xray-core
XRAY_VERSION=v1.260327.1-0.20260908222543-52a412d9e2f5
XRAY_SUM="h1:BsUC2sCXcdVCb09SUh1iWku0ci779t4bUIlKUor1ZRI="
XRAY_TOOLCHAIN=go1.27.2

download() { # MODULE VERSION -> "DIR SUM"
  local json
  json="$(cd / && GOFLAGS= GOOS= GOARCH= go mod download -json "$1@$2")"
  printf '%s %s\n' \
    "$(printf '%s' "$json" | sed -n 's/^[[:space:]]*"Dir": "\(.*\)",$/\1/p')" \
    "$(printf '%s' "$json" | sed -n 's/^[[:space:]]*"Sum": "\(.*\)",$/\1/p')"
}

copy_checked() { # MODULE VERSION EXPECTED_SUM -> writable copy
  local dir sum work
  read -r dir sum < <(download "$1" "$2")
  if [ -n "$3" ] && [ "$sum" != "$3" ]; then
    echo "Hash mismatch for $1@$2: got $sum, pinned $3" >&2; exit 1
  fi
  work="$(mktemp -d)"; cp -R "$dir/." "$work/"; chmod -R u+w "$work"
  printf '%s\n' "$work"
}

HY_APP_SUM="h1:sNJUoka4hR5ruvF/SFop26JlOkgqWc48hX0P8/jOzVc="
HY_CORE_SUM="h1:6hWhxnbGAH04VpInXrq/AVoH+kscha4YECx7Tj2yc9Y="
HY_EXTRAS_SUM="h1:cenZ6WcsvwyNvRAUjqNxjDcyAJH7GeGP8DLuSRFSfyo="
[ "$VERIFY" = 1 ] || { HY_APP_SUM=""; HY_CORE_SUM=""; HY_EXTRAS_SUM=""; XRAY_SUM=""; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
DNSTT_SRC="$(copy_checked "$DNSTT_MODULE" "$DNSTT_VERSION" "$DNSTT_SUM")"
XRAY_SRC="$(GOTOOLCHAIN=$XRAY_TOOLCHAIN copy_checked "$XRAY_MODULE" "$XRAY_VERSION" "$XRAY_SUM")"
# Hysteria's app module points at its sibling modules with relative replaces; rebuild that layout.
mkdir -p "$work/hy"
mv "$(copy_checked github.com/apernet/hysteria/app/v2 "$HY_VERSION" "$HY_APP_SUM")" "$work/hy/app"
mv "$(copy_checked github.com/apernet/hysteria/core/v2 "$HY_VERSION" "$HY_CORE_SUM")" "$work/hy/core"
mv "$(copy_checked github.com/apernet/hysteria/extras/v2 "$HY_VERSION" "$HY_EXTRAS_SUM")" "$work/hy/extras"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
for arch in amd64 arm64; do
  (cd "$DNSTT_SRC" && CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOFLAGS=-mod=mod \
    go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$OUT/dnstt-server-linux-$arch" ./dnstt-server)
  (cd "$work/hy/app" && CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOFLAGS=-mod=mod \
    go build -trimpath -buildvcs=false -tags "" \
      -ldflags "-s -w -buildid= -X github.com/apernet/hysteria/app/v2/cmd.appVersion=$HY_VERSION -X github.com/apernet/hysteria/app/v2/cmd.appType=release" \
      -o "$OUT/hysteria-linux-$arch" .)
  (cd "$ROOT/tools/server-tools/socks" && CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOFLAGS=-mod=mod \
    go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$OUT/maximus-socks-linux-$arch" .)
  (cd "$XRAY_SRC" && GOTOOLCHAIN=$XRAY_TOOLCHAIN CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOFLAGS=-mod=mod \
    go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$OUT/xray-linux-$arch" ./main)
done
rm -rf "$DNSTT_SRC" "$XRAY_SRC"

(cd "$OUT" && sha256sum dnstt-server-linux-* hysteria-linux-* maximus-socks-linux-* xray-linux-* | sort -k2) > "$OUT/SHA256SUMS"
cat "$OUT/SHA256SUMS"
if [ "$VERIFY" = 1 ]; then
  diff -u "$ROOT/tools/server-tools/SHA256SUMS" "$OUT/SHA256SUMS" \
    || { echo "The build does not match the pinned tools/server-tools/SHA256SUMS" >&2; exit 1; }
  echo "Matches tools/server-tools/SHA256SUMS"
fi
