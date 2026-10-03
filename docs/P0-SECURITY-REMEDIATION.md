# P0 security remediation

This change is a security candidate, not a verified release. Run Android CI and the
physical-device tests below before publishing. Item 17 is completed as a read-only AI boundary: model-generated approval fields
cannot authorize any mutation. State-changing tools and their execution code are removed.

| Priority | Change | Availability / remaining verification |
| --- | --- | --- |
| 1 | Capture both address families on every TUN, including Kotlin profiles. | Kotlin drops IPv6; native forwards it. Plain VLESS with DoH uses Kotlin with verified TLS inside the VLESS stream. Verify no IPv6 packets on the physical uplink. |
| 2 | Android receives a virtual resolver. DNS uses exactly the selected resolver; no automatic custom/plain/system fallback. | Literal-IP resolver bootstrap only. HTTP/SOCKS DoH TCP/UDP adapter and Kotlin VLESS DoH need device validation. |
| 3 | HTTP/SOCKS DNS travels through CONNECT/SOCKS TCP; VLESS DNS through the selected VLESS proxy with verified TLS for DoH. | HTTP/SOCKS UDP data remains unsupported and blocked. |
| 4 | Establish a blocking TUN before selecting/attaching a profile; retain it during reconnect/error cleanup. | Proxy endpoints require literal IPs. Hostname bootstrap is disabled. |
| 5 | Service checks native engine liveness and retains traffic protection after engine failure. Kotlin I/O owns duplicate descriptors so its cleanup cannot close the retained TUN. | Process death and Android VPN revocation require OS Always-on VPN + Block connections without VPN. No application-only claim of process-death protection. |
| 6 | Reject unsafe fingerprints and imported allowInsecure=true. | Certificate errors fail the connection. |
| 7 | Require an independent SSH SHA256 pin; no TOFU or case-insensitive Base64 matching. | Installation is disabled, so no root password is transmitted. |
| 8 | Remove mutable curl-pipe-root execution. | Installer disabled until an immutable reviewed installer and its dependencies are pinned and verified. |
| 9 | Remove credential-bearing Worker source generation. | Worker deployment disabled until a reviewed package uses secret bindings without API-token injection. |
| 10 | Remove main/latest package downloads and fake Worker fallback. | Deployment blocked before Cloudflare resources are created. |
| 11 | No all-account/all-zone template or DNS/Pages/User edits. | Scoped Worker/KV template requires explicit account ID; otherwise open the neutral token page. |
| 12 | Public IDs cannot derive an encryption key. Existing peer send remains disabled and E2EE status false. | Authenticated key exchange and a reviewed forward-secret protocol remain unimplemented. |
| 13 | Sanitizer exceptions reject configs instead of executing the original. Apply controlled DNS to imports. | Hostname endpoints and unrecognized proxy outbounds fail closed. |
| 14 | Remove SOCKS/dokodemo/API/metrics network listeners from native execution. | TUN is the only application inbound. Verify ports 10808, 10809, 10085, 49227 are closed. |
| 15 | Disable bridge and LAN mesh activation; remove hardcoded bridge list. | Authenticated discovery/relay transport must precede re-enablement. |
| 16 | Diagnostics expose only allowlisted health counts; model-bound text/nested results remove common credentials and endpoints. | Arbitrary raw logs never enter tool results. User-supplied images remain intentional uploads and are not OCR-redacted. |
| 17 | Remove boolean authorization, all mutation declarations and mutation implementations; use an explicit read-only tool allowlist. Align the model prompt with that boundary. | Regression tests reject every former mutation, unknown/future tools and forged approval fields. AI suggestions require manual actions in the app. |

## Validation

Regression tests cover DoH response parsing, malformed/insecure imported configs,
listener removal, DNS fallback removal, endpoint/credential filtering, scoped token
URLs, public-ID crypto rejection, HTTP proxy DNS and socket-protection failure.

Local Gradle could not bootstrap in the restricted execution environment. GitHub
Android CI is the compilation/unit-test/build/lint gate. A successful compile alone
is not evidence that the VPN is leak-free.

## Required device checks

On Android 15 (including Samsung SM-F711B), enable OS Always-on and Block connections
without VPN. Capture the uplink while testing plain VLESS, HTTP, SOCKS and native
TLS/Reality profiles. Verify IPv4/IPv6, UDP/TCP DNS and configured DoH during first
connect, failed connect, server switch, network switch and reconnect. Force-stop or
crash the native core and then the app process. Confirm traffic remains blocked
under OS lockdown and that explicit user disconnect behaves as Android documents.
Inject invalid/mismatched TLS certificates and malformed imports; all must fail.
Confirm no direct uplink DNS and no local unauthenticated listeners.

Automatic installation/deployment, peer messaging, mesh/bridges and AI mutations
are deliberately unavailable while their trusted replacements are unfinished.
