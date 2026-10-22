# Security Policy

## Supported versions

Only the latest release published under [Releases](https://github.com/drfxai/MAXIMUS-VPN/releases) receives fixes.

## Reporting a vulnerability

Please do **not** open a public issue for security problems. Use GitHub's **Report a vulnerability** button (Security tab → Advisories) to send a private report. Include the app version, Android version and device, steps to reproduce, and any logs with credentials removed.

## Scope and known limitations

- Maximus VPN is beta software. Validate DNS, IPv6, failover and kill-switch behavior on your own device before relying on it.
- Plain VLESS without TLS/REALITY is not confidential between the device and the server.
- For leak protection when the app is not running, enable Android **Always-on VPN** and **Block connections without VPN**.
- Secret Chat has no authenticated key exchange or peer transport yet and must not be used for private messaging.
- Every imported profile runs on the bundled Xray core or the Kotlin tunnel, including Hysteria2, WireGuard and the proxies of Clash/Mihomo YAML files (their proxy groups and rules are not applied). TUIC links and AmneziaWG servers that change WireGuard's packet format (S1/S2, H1–H4) are refused at import with the reason, because Xray cannot run them.

## Verifying downloads

Every release ships `SHA256SUMS`. Run `sha256sum --check SHA256SUMS` before installing, and only install APKs downloaded from this repository's Releases page.

A checksum file published next to the APK only shows that the download was not damaged. To check that an APK really comes from this project:

- **Signing certificate.** Every release is signed with the same key. Its SHA-256 fingerprint is:

  `83352d116d4bb06fc9e8c5e6347633412da2197410fe45fdb48c1ad8e4a9dcfe`

  Check it with `apksigner verify --print-certs MAXIMUSVPN-*.apk` (Android SDK build-tools). Android also refuses to install an update signed with a different key over an installed copy.
- **Build provenance.** Releases built after this note carry a GitHub attestation that ties each APK to the workflow run that built it: `gh attestation verify MAXIMUSVPN-*.apk --repo drfxai/MAXIMUS-VPN`.
