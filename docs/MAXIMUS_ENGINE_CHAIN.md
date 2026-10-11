# Engine chain (Psiphon / Tor)

A chain sends the phone's traffic through two bundled relay engines, one after the other, before it
reaches the internet. Two hops that are blocked in different ways are harder to cut off together than
either on its own.

## How it runs

The hops are ordered entry first (closest to the phone) and exit last. The phone's traffic enters the
entry hop, which dials the exit hop, and the exit hop reaches the site. Only engines that both accept
a local SOCKS5 inbound and can dial out through an upstream SOCKS5 proxy can be chained; here that is:

- **Psiphon** — `UpstreamProxyURL`
- **Tor** — `Socks5Proxy`

So the chain can run either way: **Psiphon → Tor** or **Tor → Psiphon**. Cloudflare WARP is an Xray
WireGuard outbound rather than a SOCKS sidecar, so it is not a hop here.

The exit hop starts first, so its port is already listening when the entry hop is told to dial through
it. Each hop gets its own loopback SOCKS port with a random per-connection login. Xray then proxies to
the entry hop exactly as it would a lone engine, so Xray still owns the TUN, the DNS and the kill
switch, and every hop's own server authentication is unchanged. If any hop fails to start, the hops
already started are stopped, so a half-built chain never carries traffic.

A chain is slower than a single hop.

## Building one

LAB's **Engine chain** page builds a chain: pick the order and, for the Psiphon hop, an exit country
(or Any). "Save this chain" stores it as a server, which then appears on the Servers screen to connect
to like any other.

## Files

- `vpn/sidecar/EngineChain.kt`: the chain model, validation and run plan.
- `vpn/sidecar/ChainRunner.kt`: starts the hops, wires each to the next, and the saved chain profile.
- `vpn/sidecar/SidecarContext.upstreamSocks`: the next hop's port, honoured by each engine's `prepare`.
- `PsiphonSidecar` / `TorSidecar`: set `UpstreamProxyURL` / `Socks5Proxy` when chained.
- `RayVpnService.startChain`: runs a chain profile.
- `ui/lab/LabChain.kt`: the builder page.
- Tests: `EngineChainTest`, `ChainRunnerTest`.

## Not verified

Not tested on a phone. The chain runtime (two engines running at once, each dialing the next) has only
been exercised by unit tests with stand-in engines.
