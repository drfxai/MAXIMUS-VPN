# Public config aggregator

Builds the app's free configuration list from public sources, checks every entry, and signs the result
so the app can tell the signed list apart from anything else served at that address.

    sources/sources.json   the sources to read, with a kind and a note
    scripts/aggregate.py   fetch, validate, de-duplicate, write and sign
    tests/                 run with: python3 -m unittest discover tools/aggregator/tests
    output/                free.txt, free-base64.txt, manifest.json, manifest.sig

The app reads `manifest.json` and `manifest.sig`, checks the signature against the public key built into
it (`HubManifest.PUBLIC_KEY_DER_BASE64`), then checks each file's SHA-256 against the manifest. An
unsigned or changed list is refused; the app keeps the last list it verified.

## Keys

ECDSA P-256 (SHA256withECDSA), which every supported Android version verifies.

    openssl ecparam -name prime256v1 -genkey -noout -out hub-signing.key   # keep off the repository
    openssl ec -in hub-signing.key -pubout -outform DER | base64 -w0       # paste into HubManifest

The private key lives only in the repository's Actions secrets (`HUB_SIGNING_KEY`). It is never
committed, logged or printed. Signing happens only in the aggregator workflow:

    HUB_SIGNING_KEY="$(cat hub-signing.key)" python3 tools/aggregator/scripts/aggregate.py

Without the key the script still builds and checks the list and writes the manifest, unsigned, for a
dry run.
