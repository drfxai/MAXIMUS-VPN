# Connectivity semantics (V1.0.1)

What each check in the app proves, and what it does not. The rule throughout: a result is only as
strong as the evidence behind it; a check that could not run is "not tested", never "failed".

## Probe kinds

| Kind | What it proves | Where it is used |
|---|---|---|
| Real request through a test core (`RealDelayProbe`, libXray `pingBatch`) | The profile carried an HTTP request end to end | Server tests, free list, races before connecting |
| Real request through the running tunnel (`LiveTunnelProbe`, `generate_204`, Cloudflare trace) | The current connection carries traffic | Verification after connect, health checks |
| Passive: bytes received by the app's sockets (`PassiveHealth`) | The native core is receiving data from the server | Skips a health check only right after a passed real request |
| Handshake (`ServerTester.testTransport`: DNS, TCP, TLS with the profile's SNI, WebSocket upgrade) | The endpoint answers; **not** that it carries traffic | Fallback when no core can run a real request |

A TCP or TLS handshake is never treated as proof that a profile works. Cloudflare Worker (BPB) edges in
particular answer every handshake, whether or not the worker behind them carries traffic.

## While connected

The running core refuses a second test core, and the app does not swap its dialer to test other
servers. So, while the VPN is on:

- **The server in use** reports the latency of the last request verified through the tunnel.
- **Any other server** shows "not tested" and keeps its last result. A check through the tunnel would
  only measure the current exit, and a Cloudflare-based exit cannot reach Cloudflare addresses.
- **Health checks run on a cadence.** A real request is made every 12 s; after a failed one, every
  3 s. Three failures in a row are needed before a switch, and a cooldown applies after each switch.
  - A tunnel verified in the last 45 s that is receiving data skips the check.
  - Checks made to confirm a suspected failure are never skipped.
  - The status check slows to 30 s after three passes in a row.
- **Latency alone never marks a working path dead.** A check fails only on an error or a timeout (3 s
  floor for a real request). A slow path that works (800–1500 ms is common on filtered networks) stays
  connected.

## Backups

A backup's state comes from its last real test:

- **Verified:** passed within the last 30 minutes.
- **Previously verified:** passed, but longer ago.
- **Not tested.**
- **Failed its last test.**

Backups are ranked by that state first, then by score. They are chosen on other failure domains than
the primary (`SmartFailoverPolicy`). Test results are not tied to a network, so "verified" means "carried
traffic recently on this phone", not "will work on this network". The actual switch always races
candidates with real requests before connecting.

In GOD MODE, while the current connection is failing or nothing works, the recovery watchdog checks the
user's own server every 30 s and switches back after three passes over a minute. It never drops a
healthy tunnel on handshake evidence.

## Network sessions (LAB)

A new LAB network session starts only when the network identity changes (Wi-Fi ↔ cellular, another
carrier, the network gone and back as another path, IPv4/IPv6 availability). Signal and bandwidth
updates, and losing an unrelated network (mobile data dropping while Wi-Fi stays), refresh nothing.

## Capabilities measured on the phone's own network

| Measured | Not measured (stays UNKNOWN, never assumed) |
|---|---|
| system DNS, TCP, TLS + HTTP/2 to a known site, UDP DNS, IPv4/IPv6 addresses | QUIC/HTTP/3, ECH, upload limits |

QUIC, ECH and upload limits need a trustworthy first-party endpoint or a runtime ECH handshake test.
Until those exist they are reported as unknown, and nothing is inferred from the carrier's name.

## DNS

- **Configured DoH:** the resolver set in Settings.
- **Runtime:** apps get a FakeDNS answer and the server resolves names through the tunnel. The core's own
  lookups use the configured DoH through the proxy, with no fallback.
- **GOD MODE:** server names are looked up only through DNS-over-HTTPS, with no plaintext fallback.
- **The diagnostics report:**
  - shows the VPN-side DNS path test;
  - reports "External DNS leak test: NOT TESTED" until an external observation exists.

  The app does not claim "no DNS leak".

## IPv6

Every TUN captures IPv6 (`::/0`). It is tunnelled where the engine supports it and blocked otherwise, so
it is never routed around the VPN.

## Kill switch

- **In-app "packet guard":** blocks traffic inside the tunnel while it is not connected.
- **Android lockdown:** "Always-on VPN" plus "Block connections without VPN" is the only protection
  that also covers the moment Android stops or restarts the app. Settings has a button that opens
  Android's VPN settings, and the diagnostics report shows the lockdown state.

## Certificates and fingerprints

- **`fingerprint = "unsafe"`:** Xray's name for Go's standard TLS instead of a browser imitation.
  Certificates are still verified, so it is not a security rejection.
- **`allowInsecure`:** never written to an Xray config. A profile marked insecure runs only with a
  pinned certificate (`pinnedPeerCertSha256`). Recovery, the LAB and the AI cannot turn certificate
  checks off.

## Official subscription

The official subscription is signed and served from independent mirrors; see
[SUBSCRIPTIONS.md](SUBSCRIPTIONS.md). A failed refresh never touches the connection or the servers on
the phone.

## AI boundary

The AI reads diagnostics and explains them. It cannot connect, disconnect, change DNS, the kill switch,
certificates or profiles, or promote a candidate (`AiBoundaryTest`). When a provider is paused, the log
names the category (auth, rate limit, quota, timeout, network, server, model not found). The VPN does
not depend on the AI.

## Community reports

Field reports (Telegram, forums) are hypotheses. They can suggest an approved experiment in the LAB.
They never change a configuration directly, and no script, binary or JSON from them is executed.

## Xray core

The app ships Xray-core/libXray **v26.9.9**. v26.9.30 is available upstream. It rebuilds the transports
on Finalmask's dialer, rewrites SS2022 and changes TUN inbound and FakeDNS defaults. That is too large a
change to adopt for this release without on-device regression testing; it is the candidate for the next
version.

## Field test matrix

None of these has been run on a real phone in Iran for this release. CI covers unit tests and an Android
15 emulator install/launch only.

| Scenario | Status |
|---|---|
| Irancell / MCI / TCI / other Wi-Fi, IPv4 and dual stack | NOT TESTED |
| TCP works, proxy traffic fails (must stay failed) | unit-tested (probe semantics), not field-tested |
| ECH works / fragment fails, and the reverse | NOT TESTED |
| IPv4 fails, IPv6 works | NOT TESTED |
| UDP blocked, TCP/H2 works; UDP/H3 works | NOT TESTED |
| Download works, upload constrained | NOT TESTED (no upload measurement yet) |
| Clean Cloudflare address goes bad | NOT TESTED |
| Wi-Fi signal updates keep one LAB session | code fix; field check pending (diagnostics report counts sessions) |
| Subscription origin blocked, tunnel works | unit-tested (signed mirror path), not field-tested |
| Subscription unavailable, last known servers usable | unit-tested |
| Samsung SM-F711B, Android 15 regression | NOT TESTED by CI; the user's diagnostic reports are the evidence |
