#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 "$root/tools/dns-validation/prepare_pacer.py" "$work"
patch -d "$work" -p1 --batch < "$root/scripts/engines/dnstt-timing-jitter.patch"
cp "$root/tools/dns-validation/querypace_test.go" "$work/dnstt-client/"
cd "$work/dnstt-client"
GO111MODULE=off GOTOOLCHAIN=local go test -race -count=1 .
