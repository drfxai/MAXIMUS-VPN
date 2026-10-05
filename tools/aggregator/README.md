# Public config aggregator

Builds the app's free configuration list from public sources, checks every entry, and signs the result
so the app can tell the signed list apart from anything else served at that address.

    sources/sources.json   the sources to read, with a kind and a note
    scripts/aggregate.py   fetch, validate, de-duplicate, write and sign
    tests/                 run with: python3 -m unittest discover tools/aggregator/tests
    output/                free.txt, free-base64.txt, manifest.json, manifest.sig

The workflow `.github/workflows/free-configs.yml` runs every six hours (and by hand): it tests the
aggregator, authenticates recent end-to-end measurements from Iranian networks, drops servers that
do not accept a TCP connection from the runner, considers at most 500 eligible entries per source,
and keeps at most **30** in all, with at most two entries per hostname. It ranks qualified entries by
network coverage, worst-network success rate, worst-network p95 latency and worst-network throughput,
replaces every display name, signs the manifest and force-pushes the four files as a single commit to
the `free-configs` branch. Without `HUB_SIGNING_KEY` it builds the list but publishes nothing.

The app adds the list once per install as the "MAXIMUS Free" subscription
(`https://raw.githubusercontent.com/drfxai/MAXIMUS-VPN/free-configs/free.txt`; jsDelivr, Statically and
Githack copies are tried when that address is blocked). From whichever address answers, it reads
`manifest.json` and `manifest.sig` beside the list, checks the signature against the public key built
into it (`HubManifest.PUBLIC_KEY_DER_BASE64`, from `BuildConfig.HUB_PUBLIC_KEY`), then the list's
SHA-256. An unsigned, stale or changed copy is refused and the next address is tried; when all fail,
the curated free list is withdrawn instead of restoring an unverified/expired snapshot. Favorites and
the selected profile are retained as personal profiles, outside the free list. Other subscriptions
keep their existing offline fallback. Only configs that are encrypted, check certificates and run on
an engine in the app are imported. The app enforces the 30-entry cap independently, rejects legacy
TCP-only manifests, and checks the signed measurement expiry before import. A signed empty list
revokes previously offered free entries.

## Iran admission policy and deployment prerequisite

`iran-proxy-v1` requires proof on **two distinct Iranian ASNs**, no older than six hours. Each network
must report at least five real proxy requests, at least 90% success, at least two different HTTPS
targets, verified TLS, authenticated proxy traffic, confirmed proxy egress, p95 latency at most
2500 ms and sustained download throughput at least 2 Mbps. These are admission thresholds, not
promises of future speed. Proof is bound to SHA-256 of the exact original URI before `#`, not the
coarser deduplication key. A change in Reality short ID, credentials, transport or any other option
requires new measurements. VMess proof binds the entire original encoded URI.

The keys registry `sources/iran-probes.json` is intentionally empty: **no live Iranian network was
tested while implementing this policy**. Do not enroll invented keys, ASNs or reports. A hostname
country lookup identifies the server's location, not whether a user in Iran can reach it. A GitHub
hosted runner abroad, a TCP handshake, and a config described as "Iran" are insufficient evidence.

An operator must enroll independently controlled probes on actual Iranian ISP connections. For
example, use a mobile connection and a fixed-line connection on different ASNs. Verify each probe's
direct, pre-tunnel network origin independently, then bind its ID, ASN and Ed25519 public key in the
registry on the default branch. A signature authenticates the enrolled operator's assertions; it
cannot by itself prove geography or truthful performance. Keep private probe keys on the probes,
separate from the publication signing key. Do not publish subscriber IPs or device identifiers.

For each candidate, the probe operator must run the actual configuration using the app's compatible
proxy engine, send certificate-validated HTTPS requests *through that proxy* to two operator-chosen
test origins with known responses, and verify egress against the direct connection. Also download a
known-size payload through the proxy, compute sustained Mbps and p95 time-to-response, and record
failures as well as successes. Ordinary TCP probes cannot produce these fields. Tests should include
the Android engine/device path before a release; Linux-only results do not certify Android behavior.
This change implements the verifier, not provisioning or a live measurement collector.

Store each probe's signed report in a `reports.json` array on the `iran-probes` branch. GitHub Actions
reads this branch, verifies signatures using the separately controlled registry, admits/ranks candidates,
and signs the output using `HUB_SIGNING_KEY`. An absent branch/file fails the workflow before
publication. An empty report array or no qualifying candidates publishes a signed empty list when
the publication key exists; there is no fallback to TCP-only publication.

Registry entry (replace placeholders with independently verified probe information):

```json
{"probe-id":{"country":"IR","asn":44244,"public_key":"BASE64_ED25519_RAW_PUBLIC_KEY"}}
```

Report envelope schema (illustrative values; **not a real measurement**):

```json
{
  "payload": {
    "policy":"iran-proxy-v1", "probe_id":"probe-id", "country":"IR", "asn":44244,
    "results":[{
      "config_sha256":"SHA256_OF_EXACT_URI_BEFORE_FRAGMENT",
      "measured_at":"2026-10-06T00:00:00Z", "attempts":5, "successes":5,
      "https_targets":2, "proxy_authenticated":true, "tls_verified":true,
      "egress_confirmed":true, "p95_ms":600, "download_mbps":8.0
    }]
  },
  "signature":"BASE64_ED25519_SIGNATURE_OF_CANONICAL_PAYLOAD"
}
```

Canonical signing bytes use Python `json.dumps(payload, sort_keys=True, separators=(",", ":"),
allow_nan=False).encode()` (UTF-8, default ASCII escaping). Record `measured_at` per configuration
after its test, not just once for a potentially long probe run. The newest measurement on an ASN
wins, including failed results. Enroll at least two ASNs before expecting a nonempty publication.

Local verification/dry run:

```sh
IRAN_REPORTS=/path/to/authentic-reports.json python3 tools/aggregator/scripts/aggregate.py
python3 -m unittest discover -s tools/aggregator/tests
```

Deploy the publisher and the new Android build together after enrollment. Older installed builds
cannot enforce the new expiry policy and may retain an old offline copy. No updated APK is produced
by this aggregator change alone. The Android free screen uses lazy keyed rows and only runs local
native tests on explicit user request; it no longer tests the whole list on every visit or refresh.

## Keys

ECDSA P-256 (SHA256withECDSA), which every supported Android version verifies.

    openssl ecparam -name prime256v1 -genkey -noout -out hub-signing.key   # keep off the repository

Paste the whole of `hub-signing.key` (with its BEGIN and END lines) as the repository's Actions secret
`HUB_SIGNING_KEY`, then delete the file. The release workflow derives the public key from that secret and
builds it into the app, so no code change is needed.

The private key lives only in the repository's Actions secrets (`HUB_SIGNING_KEY`). It is never
committed, logged or printed. Signing happens only in the aggregator workflow:

    HUB_SIGNING_KEY="$(cat hub-signing.key)" python3 tools/aggregator/scripts/aggregate.py

Without the key the script still builds and checks the list using enrolled Iran evidence and writes
the manifest, unsigned, for a dry run. Signing without that evidence object is refused.
