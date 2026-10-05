# Shared steps for building a sidecar engine program from a pinned Go module.
# Sourced by the engine scripts in this directory; not run on its own.
#
# Inputs (environment):
#   ABI  Android ABI to build for: arm64-v8a, armeabi-v7a or x86_64
#   OUT  directory the lib<binary>.so file is written to
#
# The module source comes from `go mod download`, which checks it against the Go checksum
# database (sum.golang.org); the script then also compares the hash with the one pinned here.

set -euo pipefail

: "${ABI:?set ABI to arm64-v8a, armeabi-v7a or x86_64}"
: "${OUT:?set OUT to the output directory}"

case "$ABI" in
  arm64-v8a)   export GOARCH=arm64 ;;
  armeabi-v7a) export GOARCH=arm GOARM=7 ;;
  x86_64)      export GOARCH=amd64 ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

export GOOS=android CGO_ENABLED=0
# Go links android/arm64 programs itself; for the other ABIs it needs the NDK's C linker (cgo).
if [ "$ABI" != arm64-v8a ]; then
  ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
  if [ -z "$ndk" ] || [ ! -d "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
    echo "Building for $ABI needs the Android NDK (set ANDROID_NDK_HOME)" >&2
    exit 2
  fi
  case "$ABI" in
    armeabi-v7a) triple=armv7a-linux-androideabi ;;
    x86_64)      triple=x86_64-linux-android ;;
  esac
  # API 24 is the app's minSdk.
  export CGO_ENABLED=1 CC="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${triple}24-clang"
fi
# One pinned Go toolchain for every engine, fetched through the Go proxy and checked like a module
# when the local Go differs. psiphon-tunnel-core needs at least go 1.26.0.
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.8}"
if [ "${GONOSUMDB:-}${GOPRIVATE:-}${GOINSECURE:-}" != "" ] || [ "${GOSUMDB:-}" = "off" ]; then
  echo "Refusing to build with the Go checksum database turned off for any module" >&2
  exit 2
fi

# fetch_module MODULE VERSION EXPECTED_H1 -> prints a writable copy of the module source
fetch_module() {
  local module="$1" version="$2" expected="$3" json dir sum work
  json="$(cd / && GOFLAGS= GOOS= GOARCH= GOARM= go mod download -json "$module@$version")"
  dir="$(printf '%s' "$json" | sed -n 's/^[[:space:]]*"Dir": "\(.*\)",$/\1/p')"
  sum="$(printf '%s' "$json" | sed -n 's/^[[:space:]]*"Sum": "\(.*\)",$/\1/p')"
  if [ -z "$dir" ] || [ ! -d "$dir" ]; then
    echo "go mod download did not return a source directory for $module@$version" >&2
    printf '%s\n' "$json" >&2
    exit 1
  fi
  if [ "$sum" != "$expected" ]; then
    echo "Hash mismatch for $module@$version: got $sum, pinned $expected" >&2
    exit 1
  fi
  work="$(mktemp -d)"
  cp -R "$dir/." "$work/"
  chmod -R u+w "$work"
  printf '%s\n' "$work"
}

# build_main SRC_DIR PACKAGE BINARY [extra ldflags]
build_main() {
  local src="$1" pkg="$2" binary="$3" extra="${4:-}"
  mkdir -p "$OUT"
  local out
  out="$(cd "$OUT" && pwd)/lib$binary.so"
  (cd "$src" && GOFLAGS=-mod=mod go build -trimpath -buildvcs=false -ldflags "-s -w -buildid= $extra" -o "$out" "$pkg")
  echo "Built $out ($(wc -c < "$out") bytes) for $ABI"
}
