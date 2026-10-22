# DNS and transport recovery

Based on main `ee06291cec09653a1eb45d852752e7b6487f39cc`, 2026-10-22.
The public version and Android signing configuration are unchanged.

## Entry point

Open **LAB → Transport tools**. The page offers:

- A user-initiated scan of at most 32 explicit public IP/port targets, four sockets at a time, two TCP connection attempts each. The active physical network's socket factory is used. VPN-active scans are disabled. Results from a changed network are discarded. Stop cancels queued work; an in-progress connect is bounded by 1200 ms.
- A saved, unverified VLESS WebSocket TLS clone with independent endpoint IP, TLS SNI and HTTP Host. The server operator must configure a compatible front and origin. Authentication, port and WebSocket path come from the selected profile. A new identity is assigned, old test results are cleared, certificate verification stays enabled, and imported credentials are never logged. Use the normal Servers screen to connect and measure actual tunnel traffic.
- An opt-in, experimental TLS first-write splitting option for the next WARP device registration. Existing encrypted accounts are reused. A registration POST is not blindly retried after an ambiguous failure. The first TLS handshake write is split into 128-byte writes separated by 2 ms. Bytes, trust manager and hostname verification are unchanged. The OS may coalesce writes; physical TCP packet boundaries are not guaranteed. Android provider/device validation remains required.

The new page currently uses English labels, matching the existing LAB's English copy. Full translated copy for this new page remains outstanding.

## Requested feature status

| Requested name | Implementation and boundary |
| --- | --- |
| SNI spoofing | An explicit SNI/Host fronted-profile builder is available. It does not silently replace a server's certificate identity with a random decoy and does not forge raw packets. This is a compatible fronting workflow, not completion of all techniques called “SNI spoofing.” |
| Spoof Tunnel | The builder saves a normal VLESS/WS/TLS profile routed through the existing engine. A server accepting this route must already exist. No new universal Spoof Tunnel wire protocol or server has been introduced. |
| L3 allowlist scanner | Bounded TCP reachability scanning is implemented. Results explicitly do not prove IP-layer allowlisting, UDP reachability or usable VPN traffic. A raw-packet L3 backend is not implemented. |
| Spoofed carriers | Not implemented. A normal Android TCP socket cannot forge an on-wire source IP or mobile-carrier identity. A defined privileged/native backend, wire protocol and compatible server are still required. |
| DNS timing jitter | `dnstt-timing-jitter.patch` extends the exact pinned dnstt client after the rate and multipath patches. Each interval gains a random 0..25% positive delay, with a deterministic positive fallback if secure randomness fails. No subtractive jitter. Scheduling is based on actual wake time to prevent timer catch-up bursts. The native binary must be rebuilt to include this. |
| clean-IP scanner upgrades | TCP variability and success counts now use repeated TCP connection samples only. TLS-handshake and HTTP-request durations are separate fields. The existing TLS and origin/HTTP/WebSocket gates remain. |
| Fragment-based WARP registration | Experimental application-write splitting is wired to the real HTTPS registration path through a per-device LAB toggle, disabled by default. This is not IP fragmentation or evidence of successful registration under a particular firewall. |

These are deliberately different levels of support. A saved profile, open TCP socket, running binary and real bidirectional tunnel are not interchangeable evidence.

## DNS fixes carried forward from the reviewed ZIP

- Never return a known block-page IP just because all DoH attempts failed; fail resolution instead.
- Prefer a public address from a mixed system-DNS answer.
- Bound DoH response sizes; reject error/truncated JSON answers and invalid IPv4 octets; disable redirects.
- Validate DNS framing, compression bounds, transaction, opcode and the complete name/type/class question before accepting wire responses. This is not DNSSEC authentication.
- Connect UDP resolver probes to their intended source; validate TCP responses as well.
- Check TCP-only resolvers for NXDOMAIN manipulation, AAAA and TXT rather than treating TCP connectivity alone as health.
- Periodically re-admit one excluded DoH resolver after 60 seconds, retaining network-specific history.

## Verification

Local executable checks:

- `bash tools/dns-validation/check.sh`: 20,051 DNS framing/matching/malformed-packet checks, plus 11 TLS checks including real TLS exchange, hostname rejection and untrusted-certificate rejection.
- `bash tools/dns-validation/check-pacer.sh`: standard-library Go race tests for positive jitter bounds and the rate ceiling.
- `bash tools/dns-validation/check-dnstt.sh`: downloads the pinned dnstt module, checks the exact h1 sum, applies all three patches, and runs the actual client tests with the race detector.
- Local Kotlin/JVM compilation of the changed DNS/fronting/scanner core and 13 JUnit tests passed, using the existing JVM stubs for Android-only type references. This does not compile the Compose UI.
- `DnsHardeningTest` and `FrontedTunnelTest` run with the existing Android unit-test workflow.
- `.github/workflows/transport-recovery.yml` runs the standalone DNS/TLS and native pacing/client checks on pushes and PRs.

The local Android build could download Gradle after proxy configuration but then stopped resolving the Foojay settings plugin. Android compilation, lint, emulator, device and operator-field tests must be checked in CI or on a configured Android development host. No APK release is implied by this source change.

## TLS provider references

The wrapper has no independent SocketImpl/file descriptor and preserves the original socket's streams and network binding. Android 35 Conscrypt chooses a stream/engine socket for wrapped sockets without a usable descriptor; actual supported-device behavior must still be tested:
https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/com/android/org/conscrypt/OpenSSLSocketFactoryImpl.java

This is original implementation code; no upstream source was copied.
