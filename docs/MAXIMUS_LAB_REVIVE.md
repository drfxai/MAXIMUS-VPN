# LAB: Revive configs

LAB's **Revive configs** page tries to bring dead Cloudflare-fronted TLS configs back to life. That covers BPB and other Workers, Pages, and CDN-proxied servers, including imported free configs. These configs often stop working because the network blocks the way they connect, not because the server is gone.

The idea comes from Proxy Builder (github.com/Hidden-Node/proxy-builder), which rewrites links by hand with ECH, fragment and fingerprint settings. LAB applies the same kinds of settings automatically, and only keeps a recipe after a real request through it succeeds.

## How a run works

1. **Pick the configs.** A config qualifies when it uses TLS (not REALITY), keeps certificate checking on, and runs over WebSocket, HTTPUpgrade, XHTTP, gRPC or HTTP/2. A run takes at most 12 configs.
2. **Test them as they are.** Each config gets one real request first. A config that answers is reported as **Working** and left alone.
3. **Try recipes on the dead ones.** Each dead config gets up to 10 recipes, which is two real-request batches. Every derived copy passes `RecoverySecurityGate` before it is tried.
4. **Keep the fastest that works.** The winning recipe is recorded in the recovery ledger for this config on this network. The next time that config connects, the VPN tries the recipe first. If the recipe stops working, it is withdrawn and the saved config is used again.

Saved configs are never edited. The VPN must be off during a run, because each real request needs the Xray core free.

## Recipes

| Recipe | Settings | Source |
|---|---|---|
| ECH via Cloudflare | `echConfigList = cloudflare-ech.com+https://{1.1.1.1, 8.8.8.8, 9.9.9.9}/dns-query`, fingerprint `chrome` | Proxy Builder ECH tab (it uses UDP DNS; LAB uses DoH, see below) |
| Fragment v2 | FIX BPB's original mask (`0,104,1` / `114,1`, split 11), `unsafe` fingerprint, cipher list, HTTP/1.1 | FIX BPB, Proxy Builder "v2" |
| Fragment v1 | `5,94,1` / `109,1`, split 355, plus the same Go TLS settings as v2 | Proxy Builder "v1", new here |
| Fragment without the empty record, ClientHello splits | existing FIX BPB masks | FIX BPB |
| Fingerprints, HTTP/1.1 | `firefox` / `chrome`; ALPN `http/1.1` | existing |
| ECH (own key) | `<sni>+https://1.1.1.1/dns-query` | existing |
| Clean Cloudflare IP | address replaced by an edge IP already validated on this network; SNI unchanged | existing |

Proxy Builder's Chain Builder is not part of this page.

## Security

The security gate now accepts two things it refused before:

- **Cloudflare's shared ECH name.** ECH may now look up `cloudflare-ech.com` as well as the server's own name. The certificate is still checked against the config's unchanged SNI.
- **More fragment splits.** The split limit rose from 64 to 512, so the v1 recipe (355 splits) passes.

Everything else stays refused:

- plain UDP DNS for ECH, since "never downgrade protected DNS" still holds,
- host-name resolvers,
- another site's ECH name,
- `allowInsecure`,
- any change to SNI, Host, path, UUID or security mode.

## Files

- `vpn/lab/ConfigRevival.kt`: the run.
- `ui/lab/LabRevive.kt`: the page.
- `RayApplication.configRevival`: wiring.
- `RecoveryProfile.kt`: the new profiles `ech-cloudflare*` and `bpb-fragment-v1`.
- `BpbFix.FINAL_MASK_V1`: the v1 mask.
- Tests: `ConfigRevivalTest`.

## Not verified

- Nothing has been tested against real Cloudflare or on a phone in Iran.
- The cloud build machine cannot reach Cloudflare, so the Cloudflare ECH recipe is checked only up to the Xray config it produces.
