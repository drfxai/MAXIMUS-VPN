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

## Network-aware runs

Before reviving, a run looks at the network the phone is on:

- **Firewall order** (`NetworkFirewalls`). The network key's MCC+MNC names the network, not the SIM.
  - Irancell (43235): patterniha's F&F first (`irancell-ff`: tlshello-0-len fragment, Go TLS via the `unsafe` fingerprint with certificate checking on, the semi-python cipher list, ALPN `http/1.1` for WebSocket/HTTPUpgrade and `h2,http/1.1` for XHTTP/gRPC/h2, the config's own ECH removed), then the same at a clean Cloudflare IPv4 (`irancell-ff-ip`: a validated edge, else 188.114.98.0), then the community Irancell recipe (Firefox cipher list) and the other fragment recipes. `bpb-fragment-original` is skipped there, since F&F supersedes it.
  - Hamrah-e-Aval (43211): with IPv6 on the network, `mci-v6-clean` first (Chrome fingerprint, no fragment, no cipher list, no ECH, a Cloudflare IPv6 edge: a validated one, else 2a06:98c1:3101::6812:202f); then the Iran ClientHello split and ECH. Other fragment recipes are skipped, since the community reports fragment fully blocked there.
  - Recipes limited to a firewall type or to IPv6 (`RecoveryContext`, `RecoveryProfile.allowedIn`) are never tried when the network's firewall or IPv6 is unknown. A recipe that removed the config's own ECH says so in its result row ("ECH removed") and the recovery ledger records it.
  - Rightel (43220): ECH first, fragment last.
  - Wi-Fi and other networks: the usual order.
  - The user can override the detected network on the page (Auto, Irancell, MCI, Rightel, Other).
  - The preference only adds to the ledger's measured success rate, so this network's own results win once there are some.
- **ECH key check** (`EchKeyCheck`). One RFC 8484 GET per Cloudflare ECH resolver asks for the HTTPS record of `cloudflare-ech.com`. Recipes through a resolver that returned no ECH key are skipped. When none returned it, nothing is skipped, since the check itself may be what was blocked.
- **Clean IPs.** When no clean Cloudflare address is validated on this network and some config is dead, the existing clean-IP scanner (`CleanIpOptimizer`) runs once through the first dead config: TCP, TLS with the config's SNI, and the config's own WebSocket or HTTP exchange. Up to 6 addresses that pass are recorded in the endpoint scores for this network, which makes them endpoint recipes.

The page shows the network, the resolvers that returned the ECH key and the clean IPs used.

## Files

- `vpn/lab/ConfigRevival.kt`: the run.
- `ui/lab/LabRevive.kt`: the page.
- `RayApplication.configRevival`: wiring.
- `RecoveryProfile.kt`: the new profiles `ech-cloudflare*` and `bpb-fragment-v1`.
- `BpbFix.FINAL_MASK_V1`: the v1 mask.
- `vpn/connectivity/NetworkFirewalls.kt`, `EchKeyCheck.kt`: network order and the ECH key check.
- `BpbFix.FIREFOX_CIPHER_SUITES` and the `irancell-fragment` profile.
- `BpbFix.SEMI_PYTHON_CIPHER_SUITES`, the `irancell-ff`, `irancell-ff-ip` and `mci-v6-clean` profiles, `RecoveryContext.kt` (tests: `FirewallRecipesTest`, `RecoveryContextTest`).
- Tests: `ConfigRevivalTest`.

## Not verified

- Nothing has been tested against real Cloudflare or on a phone in Iran.
- The cloud build machine cannot reach Cloudflare, so the Cloudflare ECH recipe is checked only up to the Xray config it produces.
