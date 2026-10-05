# Shared helpers for the engine build scripts. Sourced, not run.
# Each engine is built from a pinned Go module version. `go mod download` checks the source against
# the Go checksum database, and the script checks it again against the hash pinned here.

go_arch_for_abi() {
  case "$1" in
    arm64-v8a) echo "GOARCH=arm64" ;;
    armeabi-v7a) echo "GOARCH=arm GOARM=7" ;;
    x86_64) echo "GOARCH=amd64" ;;
    x86) echo "GOARCH=386" ;;
    *) echo "unknown ABI $1" >&2; return 1 ;;
  esac
}

# fetch_module <module> <version> <h1 hash>: prints a writable copy of the verified module source.
fetch_module() {
  local module="$1" version="$2" sum="$3" json dir got work
  json="$(cd "${TMPDIR:-/tmp}" && GOFLAGS=-mod=mod go mod download -json "$module@$version")"
  dir="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')"
  got="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Sum"])')"
  if [[ "$got" != "$sum" ]]; then
    echo "$module@$version: source hash $got does not match the pinned $sum" >&2
    return 1
  fi
  work="$(mktemp -d)"
  cp -r "$dir/." "$work/"
  chmod -R u+w "$work"
  echo "$work"
}
