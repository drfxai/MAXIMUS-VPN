#!/usr/bin/env bash
# Downloads Psiphon's signed remote server list FOR REFERENCE ONLY and verifies it.
#
#   scripts/fetch-psiphon-servers.sh [OUT_DIR]      # default: build/psiphon-reference
#
# The list is Psiphon Inc. data and its redistribution terms are not stated anywhere we have read,
# so it is NOT bundled in the APK: the output lands under build/ (git-ignored) and nothing in the
# Gradle build or CI reads it. It exists to inspect what a fresh client would see (entry count,
# regions) and as groundwork if bundling is ever cleared.
#
# The URL and signing key come from the public Psiphon settings already in the repo
# (app/src/main/assets/psiphon/public-config.json: RemoteServerListURLs, base64-encoded as
# psiphon-tunnel-core's TransferURL expects, and RemoteServerListSignaturePublicKey).
# The file is zlib-compressed JSON {data, signingPublicKeyDigest, signature}; data is one
# hex-encoded server entry per line. The signature is RSA PKCS#1 v1.5 / SHA-256 over data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${PSIPHON_CONFIG:-$ROOT/app/src/main/assets/psiphon/public-config.json}"
OUT="${1:-$ROOT/build/psiphon-reference}"
MIN_ENTRIES="${MIN_ENTRIES:-50}"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 - "$CONFIG" "$TMP" <<'PYTHON'
import base64, json, sys
config = json.load(open(sys.argv[1]))
urls = []
for item in config.get("RemoteServerListURLs") or []:
    raw = item.get("URL", "") if isinstance(item, dict) else str(item)
    url = raw if raw.startswith("https://") else base64.b64decode(raw).decode()
    if not url.startswith("https://"):
        raise SystemExit("server list URL is not HTTPS")
    urls.append(url)
if not urls:
    raise SystemExit("no RemoteServerListURLs in " + sys.argv[1])
key = config.get("RemoteServerListSignaturePublicKey", "")
if not key:
    raise SystemExit("no RemoteServerListSignaturePublicKey in " + sys.argv[1])
open(sys.argv[2] + "/urls", "w").write("\n".join(urls) + "\n")
open(sys.argv[2] + "/key.der", "wb").write(base64.b64decode(key))
PYTHON

fetched=""
while read -r url; do
  echo "fetching ${url%%\?*}" >&2
  if curl -fsSL --retry 3 --retry-delay 2 --max-time 120 -D "$TMP/headers" -o "$TMP/list.z" "$url"; then
    fetched="$url"; break
  fi
done < "$TMP/urls"
[ -n "$fetched" ] || { echo "could not download the server list" >&2; exit 1; }

openssl pkey -pubin -inform DER -in "$TMP/key.der" -out "$TMP/key.pem" 2>/dev/null

python3 - "$TMP" "$OUT" "$MIN_ENTRIES" <<'PYTHON'
import base64, email.utils, hashlib, json, subprocess, sys, zlib
tmp, out, minimum = sys.argv[1], sys.argv[2], int(sys.argv[3])
package = json.loads(zlib.decompress(open(tmp + "/list.z", "rb").read()))
data = package["data"]
key_b64 = base64.b64encode(open(tmp + "/key.der", "rb").read())
digest = package.get("signingPublicKeyDigest")
# psiphon-tunnel-core hashes the base64 text of the key, not its DER bytes.
if digest and base64.b64decode(digest) != hashlib.sha256(key_b64).digest():
    raise SystemExit("signingPublicKeyDigest does not match the configured key")
open(tmp + "/data", "w").write(data)
open(tmp + "/sig", "wb").write(base64.b64decode(package["signature"]))
subprocess.run(["openssl", "dgst", "-sha256", "-verify", tmp + "/key.pem",
                "-signature", tmp + "/sig", tmp + "/data"], check=True, stdout=subprocess.DEVNULL)
entries = [line for line in data.splitlines() if line.strip()]
if len(entries) < minimum:
    raise SystemExit(f"only {len(entries)} entries (< {minimum})")
regions = {}
for line in entries:
    try:
        fields = bytes.fromhex(line).decode("utf-8", "replace").split(" ", 4)
        region = json.loads(fields[4]).get("region", "?") if len(fields) == 5 else "?"
    except Exception:
        region = "?"
    regions[region] = regions.get(region, 0) + 1
open(out + "/psiphon_servers.txt", "w").write("\n".join(entries) + "\n")
stamp = 0
for line in open(tmp + "/headers", errors="replace"):
    if line.lower().startswith("last-modified:"):
        parsed = email.utils.parsedate_to_datetime(line.split(":", 1)[1].strip())
        stamp = int(parsed.timestamp())
open(out + "/psiphon_servers.stamp", "w").write(f"{stamp}\n")
summary = ", ".join(f"{k} {v}" for k, v in sorted(regions.items(), key=lambda kv: -kv[1]))
print(f"verified {len(entries)} entries; regions: {summary}")
print(f"wrote {out}/psiphon_servers.txt (reference only, not bundled)")
PYTHON
