# Built-in connections in Maximus LAB

LAB opens separate Aether / WARP and Serverless Direct pages using the app's light and dark themes. Access checks show independent results for authenticated DNS, the HTTPS certificate and an HTTPS response. Connect becomes available after HTTPS passes on the current network. With explicit WARP fallback enabled, Direct can also start a session and delegate failed Direct attempts to the service fallback. The service checks access again when it starts the VPN.

Aether Auto tries MASQUE H2 then H3. Explicit WireGuard and WireGuard-over-MASQUE are also available. Registration verifies TLS, and MASQUE verifies the upstream gateway SPKI. The identity lives in app-private, backup-excluded storage. Renewal stages a new identity and replaces the existing one only after successful registration.

Serverless Direct uses Patterniha's Xray fork with the pinned fragA, fragB and OOB profiles. The adaptation keeps the DPI instructions and the hostname-based DoH mapping, with an IP-based DoH fallback. It removes routing rules that require external geo files. Direct uses the ISP's exit IP. WARP fallback requires explicit opt-in and applies to a Direct VPN session.

Both engines require authentication on their loopback SOCKS5 port. The LAB access checks exercise that port. They do not measure traffic from other apps through Android's TUN. The native workflow builds the engines for arm64-v8a, armeabi-v7a and x86_64, and release packaging checks both executables and includes the corresponding source archives.
