#!/usr/bin/env bash
# Test the exact pinned client with every Android build patch, on the host JVM/Go CI runner.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
module=www.bamsoftware.com/git/dnstt.git@v1.20260501.0
go mod download -json "$module" > "$work/module.json"
python3 - "$work" <<'PY'
import json,sys,shutil
from pathlib import Path
work=Path(sys.argv[1]); m=json.loads((work/'module.json').read_text())
if m.get('Sum')!='h1:8i4tQWwBCrhsn6+c8fpjSIyaa+u0MOvdOa1FIxNOz6M=':
    raise SystemExit('Pinned dnstt checksum mismatch')
shutil.copytree(m['Dir'],work/'src')
for p in [work/'src', *(work/'src').rglob('*')]:
    p.chmod(p.stat().st_mode | 0o200)
PY
for patch_name in dnstt-query-rate dnstt-multipath dnstt-timing-jitter; do
    patch -d "$work/src" -p1 --batch < "$root/scripts/engines/$patch_name.patch"
done
patch -d "$work/src" -p1 --batch < "$root/scripts/server-tools/dnstt-server-domains.patch"
cp "$root/tools/dns-validation/querypace_test.go" "$work/src/dnstt-client/jitter_test.go"
cd "$work/src"
go test -race -count=1 ./...
