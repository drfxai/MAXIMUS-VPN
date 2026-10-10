# Cloudflare MASQUE (WARP over HTTP/3)

MASQUE runs Cloudflare WARP (WireGuard) tunnelled inside HTTP/3 (QUIC), reached over port 443, by the
bundled `usque` program. It gives a non-Iran Cloudflare exit on networks that block the plain
WireGuard UDP that ordinary WARP uses, because QUIC on 443 looks like ordinary HTTPS traffic.

## How it runs

The engine runs as its own program next to the app (`libusque.so`) and offers a local SOCKS5 proxy;
Xray proxies to that port, so Xray still owns the TUN, the DNS and the kill switch, and the app's own
traffic is excluded from the tunnel the way it is for every sidecar engine.

The device registers itself with Cloudflare once — an ECDSA key pair is made on the phone and only the
public half is sent, the same shape as the app's WARP registration — and `usque` keeps the result in
its own config file in the engine's work directory, reused on later connects. Registration runs as the
app's own child, which Android keeps out of the VPN, so it reaches Cloudflare over the phone's network.

## Where it is used

In GOD MODE's fallback order, MASQUE is tried right after ordinary WARP: when WARP's WireGuard UDP does
not carry traffic, the same Cloudflare exit is tried over MASQUE before Psiphon and Tor. It is only
taken when this build carries the program, and it is verified with a real request once it runs.

## Scope

This is the HTTP/3 (QUIC) MASQUE path only. An HTTP/2 MASQUE fallback would need the app to split the
TLS handshake itself, which it does not do, so it is not part of this change. `usque` has no
upstream-proxy option, so MASQUE is a single hop (or a chain's exit), not an inner chain hop.

## Files

- `scripts/engines/usque.sh`: builds `libusque.so` from the pinned `usque` module.
- `vpn/sidecar/MasqueSidecar.kt`: registration and the SOCKS launch.
- `vpn/engine/registry/EngineRegistry.MASQUE`: the engine descriptor.
- `RayVpnService`: the GOD MODE fallback step.

## Not verified

The `usque` program cross-compiles for Android (checked for arm64-v8a), but the MASQUE tunnel itself —
registration against Cloudflare and carrying traffic — has not been tested on a phone or against real
Cloudflare, which the cloud build machine cannot reach.
