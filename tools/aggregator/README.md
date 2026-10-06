# Public config aggregator

Builds the app's free configuration list from public sources, checks every entry, and signs the result
so the app can tell the signed list apart from anything else served at that address.

    sources/sources.json   the sources to read, each with a stable id, a kind and a note
    scripts/aggregate.py   fetch, validate, de-duplicate, write and sign
    scripts/pipeline.py    normalized candidates, deterministic score, diverse selection, history
    scripts/verify.py      real requests through a pinned Xray core, three rounds per server
    tests/                 run with: python3 -m unittest discover tools/aggregator/tests
    output/                free.txt, free-base64.txt, configs.json, history.json, manifest.json,
                           manifest.sig, and quarantine.json (kept as a run artifact, never published)

## Pipeline

    collect from every source (metadata per source: id, url, fetch time and status, candidates, valid)
    → validate and security-check (rejection reason stored in quarantine.json, without credentials)
    → normalize to one format → de-duplicate by connection fingerprint (never the display name)
    → DNS and TCP from the runner → three real requests through Xray → services the exit reaches
    → deterministic score with reasons → at most 30, chosen for diversity

**What the checks prove.** The runner is a GitHub server outside Iran. A config that passes is
`GLOBAL_VERIFIED`; one that does not is `GLOBAL_FAILED`. Nothing the runner sees says a config works
from an Iranian network, so every published config is `UNKNOWN_IRAN_STATUS` until phones measure it
(the app keeps that evidence per config: `vpn/hub/FreeConfigEvidence.kt`). The YouTube, Telegram and X
checks only show that the server's exit reaches those services.

**Security filter.** Refused: malformed links or JSON, local, private, reserved or obfuscated
addresses (IPv4 and IPv6), invalid ports and credentials, unencrypted VLESS/VMess/Trojan, disabled
certificate checks (`allowInsecure`, `insecure`, `skip-cert-verify`, `verify=0`), `fp=unsafe`,
incomplete REALITY, unsupported transports, non-AEAD Shadowsocks ciphers, and duplicates. Share links
cannot carry DNS or routing rules, and full JSON configurations are not accepted from sources at all.

**Score (0 to 100).** Reliability 30 (share of the three rounds that went through), stability 15
(latency spread), HTTP 10 (services reached, global only), security 15 (REALITY above TLS), latency
10 (full marks up to 300 ms, zero at 2 s, so speed never dominates), Iran evidence 15 (neutral while
unknown) and history 5 (how often the server passed earlier runs, from the last `history.json`).
Each published config carries its score and the reason for every part in `configs.json`. No AI takes
part in qualifying a config.

**Diversity.** Best score first, but at most 3 servers per failure domain (Cloudflare as one domain,
otherwise the /16 IPv4 network, IPv6 /32 or the domain), 10 per protocol/transport/security, 12 per
source, at most 70% CDN or non-CDN while the other kind exists, and one of each address family when
both exist. The caps are relaxed in steps only when they would leave the list short of 30.

The workflow `.github/workflows/free-configs.yml` runs every six hours (and by hand): it tests the
aggregator, builds the list, drops servers that do not accept a TCP connection from the runner, then
sends a real request (`generate_204`, three rounds) through every remaining candidate with a pinned Xray core
(`scripts/verify.py`). Each server that carried at least two of the three then opens YouTube
(`www.youtube.com/generate_204`), Telegram (`api.telegram.org`) and X (`x.com`) through the same core;
any HTTP answer over a verified TLS connection counts. A server that opens none of the three is dropped.
The rest are scored and chosen as described under Pipeline, and their names end with the
services they opened (`DE · VLESS 12 · YT TG X`), which the app shows as badges and filters. The list holds **30 at most** (sources take turns, so one source cannot fill it). Formats Xray
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
