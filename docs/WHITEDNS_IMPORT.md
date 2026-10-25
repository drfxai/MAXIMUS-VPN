# Optional WhiteDNS profiles and manual resolvers

Baseline: main f7a77a563c45ff56c6e5a20b38c062849f95e4f5; PR 125 base work
at dff24086b6c6abc2da61ba744f66d81fc1d2f7d4 passed Checks 40134392359.
Version, signing and release triggers are unchanged.

Import one or more `cottendns://` / `soar://` links in the existing Import screen.
Only WhiteDNS schema `whitedns.profile`, version 1 is accepted. No public account,
server credential or example resolver list is bundled or automatically activated.
Use Edit Configuration on the imported node to paste one literal resolver IP per
line (IPv4/IPv6, optional port), up to 64. Existing profile resolvers are retained
until the user saves edits. Empty, duplicate, hostname and CIDR lists are rejected.

CottenDNS standard links explicitly select its native `legacy` protocol with
method 5, not CottenMax. The existing `maximus-cotten://` format keeps its explicit
mode and supports round-trip sharing. CottenMax cannot accept hard query rate
budgets at pinned e50a43ef75ad10ae2ffbfa106d13c50de3e77624: its previously emitted
4 qps setting was rejected by upstream `validateCottenMaxClient`. Max now disables
that unsupported budget; native Cotten retains 4 qps. Neither mode automatically
falls back to the other. This is not a claim of a universal anti-censorship rate.

SOAR uses the separate Apache-2.0 getlantern/soar client at
`a8af533d82284f5e6ab13b706552596c108bacf2` (v0.2.3). The original MAXIMUS helper
exposes authenticated loopback SOCKS CONNECT, passes destination names to Soar,
and uses only the supplied resolvers. Upstream resolver discovery flags in links
are deliberately overridden by this manual-only policy, explained in the editor.
No system/bootstrap resolvers or range scans are appended. The transport setting
controls DNS TCP paths (`auto`, `on`, `only`, `off`), not application UDP support.

Xray remains the only TUN owner. SOAR local listening is only process readiness;
existing EngineProbe/VpnService real traffic checks remain necessary. Unsupported
carrier chaining is rejected. The helper has bounded concurrent connections and
signal/cancellation shutdown. Secrets use private runtime files, not arguments.

Source review covered the upstream schema in WhiteDNS/Soar-Server
`internal/panel/cotten_profiles.go` and `profile_settings.go`, Soar client Config,
NewClient/DialContext/Close, and Cotten's mode validation. This is an integration
review, not an independent cryptographic audit. SOAR upstream describes its
protocol as pre-release; compatibility must be retested on engine upgrades.

Validation added: Kotlin import/mode/round-trip/resolver/redaction tests;
SOAR helper authentication/unsupported UDP tests and encrypted stream round-trip
through a local authoritative Soar server; upstream Go tests and three Android
ABI builds in soar-engine.yml. CI results must be checked for the final SHA.
No device, Iran network, Android TUN end-to-end, external DNS-leak or provided
public-server result is asserted by these tests. No release or merge is performed.
