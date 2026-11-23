# Unlock mode (anti-sanction DNS)

Unlock is the fifth mode on the Tunnel tab (Daily, **Unlock**, GOD, Trade, Ghost). It needs no configuration: Connect
points the phone's DNS at an anti-sanction "smart DNS" server, so services that refuse Iranian users because of
sanctions (Gemini, AI Studio, OpenAI APIs, parts of Google's developer sites) become reachable. Nothing else is
tunnelled.

## What it does — and does not do

- **Only DNS changes.** The smart DNS answers sanctioned names with the address of its own relay, which forwards the
  connection; every other name gets its normal answer. Apps then connect directly.
- **It does not bypass Iranian filtering.** Filtered sites (YouTube, Telegram, …) are answered normally and stay
  blocked on the network. Use a tunnel mode for those.
- **Privacy:** the relay operator sees which sanctioned site you open (the TLS SNI) and your IP. TLS stays
  end-to-end: the operator cannot read or change the content. Your ISP sees the DNS questions to the Unlock server.
- **Private DNS:** if Android's *Private DNS* is set to a provider hostname (strict mode), Android sends every lookup
  there over DNS-over-TLS and the Unlock server is never asked. Home and the Unlock DNS sheet show a warning; set
  Private DNS to *Automatic* or *Off*. Apps with their own DNS-over-HTTPS (for example Chrome's *Secure DNS*) bypass
  it too.
- **Always-on VPN works; "Block connections without VPN" does not.** With lockdown on, Android drops every packet
  outside the VPN interface — in Unlock mode that is all traffic — so Unlock refuses to start and says why.

## How DNS-only routing works

Unlock runs inside `RayVpnService` (one VPN service, so Always-on and the Quick Settings tile keep working). When the
mode is Unlock, Connect, Reconnect and Always-on restarts take a separate path (`connectUnlockLocked`) before any
profile, engine or traffic block is involved:

1. The servers are health-checked from the phone's own network (below) and one is picked.
2. A TUN is established with `UnlockDnsRoutePolicy`: address `172.19.0.1/30`, DNS server `172.19.0.2`, and **one
   route, `172.19.0.2/32`**. IPv6 is allowed to bypass (`allowFamily(AF_INET6)`), per-app rules apply as in the
   other modes. All other IPv4 and IPv6 traffic never enters the TUN and uses the phone's network directly.
3. `UnlockDnsSession` reads the DNS questions apps send to `172.19.0.2:53`, asks the chosen server over a
   **protected** socket (UDP/53, TCP/53 when UDP is silent or the answer is truncated), and writes each answer back
   as an IPv4/UDP packet. An unanswered question gets SERVFAIL so apps fail fast.
4. After three unanswered questions in a row an auto-picked session moves to the next usable server. A network
   change re-checks every server and replaces a dead one.

Unlock never holds the traffic block (`MaximusVpnSupervisor.Release.UNLOCK_MODE`): there is no tunnel whose absence
could leak. Every tunnel mode still uses `VpnRoutePolicy` and captures all IPv4 and IPv6 routes. Switching from a
connected tunnel mode to Unlock replaces the tunnel with the DNS-only session; switching away from a connected Unlock
session stops it, and Connect starts the new mode.

## Health check

Per server (`UnlockDnsHealth`):

| Step | Pass | Otherwise |
| :--- | :--- | :--- |
| Control: `one.one.one.one` | exactly `1.1.1.1` / `1.0.0.1`, from the address asked | Blocked (hijacked, intercepted or silent) |
| Unlock probe: `gemini.google.com` | public A records | Blocked |
| TCP connect to 443 on one of them | opens within 2.5 s | Blocked (relay unreachable) |

Passing servers are *Healthy*, or *Slow* above 400 ms DNS round trip, and ranked by round trip. Auto-pick (default)
uses the fastest usable one; with auto-pick off the user's choice is used. Checks run when Connect is pressed, when
the network changes, when a custom server is added, and from the sheet. The Home badges (Gemini, ChatGPT,
AI Studio, Android Dev) repeat the probe for each service through the active server: a check means the name resolved
and port 443 answered — not that the service accepted the account.

## The server list

- Bundled: `app/src/main/assets/unlock/dns.txt` (one IPv4 per line, `#` comments).
- Remote: the release workflow adds the same file as `unlock_dns.txt` to `MaximusResolverPack-<tag>.zip`. The app
  installs it with the resolver pack (Settings › Updates, or *Server list from GitHub* in the sheet): official release
  URL only, SHA-256 checked against the release digest, size limited (4 KiB, 1–32 public IPv4 entries), the previous
  pack kept for rollback. To replace dead servers, edit `dns.txt` and publish a release; installed apps pick it up from
  the pack. A pack without `unlock_dns.txt` (all packs before this feature) leaves the bundled list in use.
  Note: app builds older than Unlock mode only accept the six resolver parts, so they refuse packs that carry
  `unlock_dns.txt` and keep the resolver lists they have.
- Custom: up to 8 public IPv4 servers added in the sheet (validated like the bundled resolvers), removable there.

These servers are only used by Unlock mode; the DNS-tunnel resolver scanner and resolver parts do not use them.
