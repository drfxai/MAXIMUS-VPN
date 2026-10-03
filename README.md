# MAXIMUS VPN

An Android VPN client with native Xray integration, routing controls, and built-in diagnostics.

[Releases](https://github.com/drfxai/MAXIMUS-VPN/releases) · [Security](SECURITY.md) · [Documentation](docs/)

## Features

- Import and manage proxy profiles: VLESS, VMess, Trojan and Shadowsocks (Xray-compatible)
- Native Xray runtime for compatible profiles
- Built on Android `VpnService` with Always-on VPN / lockdown support
- Routing and DNS controls
- Live connection state, logs, latency and DNS/connectivity diagnostics
- Server benchmarking, comparison and automatic failover
- Panel tooling: 3X-UI management, Cloudflare-oriented workflows, clean-IP discovery, quick configuration generation
- Subscription management
- Jetpack Compose interface with light and dark themes

## Requirements

- Android 7.0 (API 24) or newer
- ARM64 build for most modern devices; a universal build for broader compatibility

## Download

Official builds are published only on the [Releases](https://github.com/drfxai/MAXIMUS-VPN/releases) page.

- `MAXIMUSVPN-V1.0.0-arm64-v8a.apk` — ARM64 devices
- `MAXIMUSVPN-V1.0.0-universal.apk` — all supported devices
- `SHA256SUMS` — verify the download before installing

Only install MAXIMUS VPN from this repository. Avoid APK mirrors and repackaged builds.

## Building from source

Requirements: JDK 17 and the Android SDK (platform 36.1, build-tools 36.0.0).

```bash
bash scripts/fetch-libxray-android.sh   # fetch and verify the native Xray runtime
bash gradlew :app:testDebugUnitTest
bash gradlew :app:assembleDebug
# ARM64-only build
bash gradlew -PtargetAbi=arm64-v8a :app:assembleDebug
```

## Security

The project is under active development. Automated tests do not prove the absence of leaks on every device, carrier or network. If you have strict privacy requirements, validate DNS, IPv4/IPv6 routing, failover and lockdown behavior on your own device. See [SECURITY.md](SECURITY.md) for details and known limitations.

Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

Developed by **DrFXAi**
