# Experimental MAXIMUS DNS Gateway

**Status: prototype only, disabled in the Android UI. Do not bind public UDP/53 yet.**

The Go module is a small DNS-over-UDP authoritative suffix router. It is *not* a recursive resolver or a general replacement for CoreDNS. It forwards whole DNS packets only to explicitly configured loopback UDP endpoints and refuses unknown zones.

Example review-only configuration:

```json
{
  "listen": "127.0.0.1:5353",
  "routes": [
    { "zone": "t.example.invalid", "upstream": "127.0.0.1:5301" },
    { "zone": "s.example.invalid", "upstream": "127.0.0.1:5302" }
  ]
}
```

Local tests: `cd tools/dns-gateway && go test -race ./...`.

Design constraints:
- Invalid/unknown DNS zones are rejected or REFUSED, with no public recursive forwarding.
- Each backend must be a different local loopback socket; public IP targets, overlapping suffixes and recursive forwarding are rejected.
- Fixed two-second upstream timeout and bounded 128 concurrent requests.
- DNS transaction ID and response bit checked. No client endpoint metadata is logged.
- UDP only (no TCP/53). Packet size limited to 4 KiB, and DNS compression in the question is deliberately not supported.
- Compatibility with the server binaries for DNSTT, SOAR and Cotten has **not** been established. In particular, each backend must support its own loopback bind and port, correct EDNS behavior and complete tunnel flows. Never infer compatibility from a syntactically accepted config.
- No production deployment, firewall edit, domain registration, remote package installation or Cloudflare modification is performed by this module.

Before enabling, run: controlled multi-engine integration on independent IPs; captured real DNS queries without logging private payloads; EDNS, fragmentation, IPv6, TCP fallback and adversarial query tests; anti-amplification/per-source limits; process sandbox and service restoration checks; authenticated, user-approved installation.
