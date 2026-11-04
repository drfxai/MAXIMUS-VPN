# Galaxy Connections — v1.0.4 / Android build 45

The Connect tab implements Galaxy Dark and Galaxy Light, sharing the existing persisted theme switch. The original approved concept has a cosmic backdrop, a code-rendered orbital planet, segmented Aether/Direct selection, translucent panels, semantic status colors and a four-destination navigation bar. Nodes, Top 10, Network Lab and AI Agent remain reachable from the Connections overflow and existing routes. Layouts scroll on short screens and with large accessibility fonts. Telemetry is from RayVpnService, never concept sample values.

## Runtime and privacy boundaries

Aether runs as its own pinned executable with authenticated loopback SOCKS5. Auto tries MASQUE H2 then H3 serially. Each candidate must pass DNS-over-HTTPS, certificate verification and a real HTTPS response before the normal VPN session starts. Explicit WireGuard and WireGuard-over-MASQUE are available. Registration uses authenticated CA and hostname verification; MASQUE uses the upstream SPKI allowlist. No unverified registration/fronting fallback is allowed. This may reduce reachability on networks where only unauthenticated fronting works.

The native identity lives in private, excluded-from-backup Android app storage outside disposable LAB directories. It is not shipped in the APK. Renewal registers into a staging directory, then atomically replaces the selected identity only on successful registration. Failed and cancelled renewal keeps the working identity. No raw native output from these engines enters the app logs. Cleartext identity material exists in app-private files while the native program needs it; this is Android sandbox storage, not a claim of encrypted identity files.

Direct uses Patterniha's Xray fork with pinned v57 fragA/fragB/OOB profiles. Loopback auth, DoH and geo-file dependent routing are adapted; finalmask and OOB instructions are retained. This is an adapted configuration, not byte-for-byte compatibility with upstream geo classifications. It keeps the ISP public IP. Auto only tries the three direct variants. Moving to Aether requires the explicit, initially disabled VPN fallback switch; ordinary exact sessions do not select unrelated exits.

The host app retains TUN capture, its DNS policy, connection verification, network handoff and kill switch. The checks panel reports only separately run diagnostics, not inferred DNS/TLS success from the presence of a listener. Real traffic verification is bounded; a passing small request does not guarantee that every website or a sustained upload works.

## Verification and limits

`ServerlessConnectionsTest` covers native configuration adaptation, authentication, family constraints, profile isolation and chunked DoH response parsing. `GalaxyConnectionsScreenshotTest` renders all three pages in both themes at 360dp width. The Galaxy engines workflow builds the two pinned programs for arm64-v8a, armeabi-v7a and x86_64. Release packaging requires both executables in every ABI and publishes corresponding sources with checksums.

A filtered Iranian network and the user's physical Galaxy Z Flip 3 are not available in CI. Device/operator acceptance therefore remains separate from compilation, screenshot review and native packaging checks. No guaranteed access, exit country or speed is claimed.
