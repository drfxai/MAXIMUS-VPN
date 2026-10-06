# Public config aggregator

Builds the app's free configuration list from public sources, checks every entry, and signs the result
so the app can tell the signed list apart from anything else served at that address.

    sources/sources.json   the sources to read, with a kind and a note
    scripts/aggregate.py   fetch, validate, de-duplicate, write and sign
    tests/                 run with: python3 -m unittest discover tools/aggregator/tests
    output/                free.txt, free-base64.txt, manifest.json, manifest.sig

The workflow `.github/workflows/free-configs.yml` runs every six hours (and by hand): it tests the
aggregator, builds the list, drops servers that do not accept a TCP connection from the runner, then
sends a real request (`generate_204`, twice) through every remaining candidate with a pinned Xray core
(`scripts/verify.py`). Each server that carried both requests then opens YouTube
(`www.youtube.com/generate_204`), Telegram (`api.telegram.org`) and X (`x.com`) through the same core;
any HTTP answer over a verified TLS connection counts. A server that opens none of the three is dropped.
The rest are kept with the most of those sites first, then the fastest, and their names end with the
sites they opened (`DE · VLESS 12 · YT TG X`), which the app shows as badges and filters. The list holds **30 at most** (sources take turns, so one source cannot fill it). Formats Xray
cannot run as a plain outbound (Hysteria2, WireGuard, Shadowsocks plugins, mKCP) are not published,
because nothing here could show they work. The workflow then replaces every display name, signs the
manifest and force-pushes the four files as a single commit to the `free-configs` branch. Without
`HUB_SIGNING_KEY` it builds the list but publishes nothing, and fewer than 10 survivors leave the last
published list in place. The runner tests from its own network: the app tests the list again on the
phone and shows what answers there.

The app adds the list once per install as the "MAXIMUS Free" subscription
(`https://raw.githubusercontent.com/drfxai/MAXIMUS-VPN/free-configs/free.txt`; jsDelivr, Statically and
Githack copies are tried when that address is blocked). From whichever address answers, it reads
`manifest.json` and `manifest.sig` beside the list, checks the signature against the public key built
into it (`HubManifest.PUBLIC_KEY_DER_BASE64`, from `BuildConfig.HUB_PUBLIC_KEY`), then the list's
SHA-256. An unsigned, stale or changed copy is refused and the next address is tried; when all fail,
the last verified copy stays in use. Only configs that are encrypted, check certificates and run on an
engine in the app are imported.

## Keys

ECDSA P-256 (SHA256withECDSA), which every supported Android version verifies.

    openssl ecparam -name prime256v1 -genkey -noout -out hub-signing.key   # keep off the repository

Paste the whole of `hub-signing.key` (with its BEGIN and END lines) as the repository's Actions secret
`HUB_SIGNING_KEY`, then delete the file. The release workflow derives the public key from that secret and
builds it into the app, so no code change is needed.

The private key lives only in the repository's Actions secrets (`HUB_SIGNING_KEY`). It is never
committed, logged or printed. Signing happens only in the aggregator workflow:

    HUB_SIGNING_KEY="$(cat hub-signing.key)" python3 tools/aggregator/scripts/aggregate.py

Without the key the script still builds and checks the list and writes the manifest, unsigned, for a
dry run.
