<div align="center">

# MAXIMUS VPN

### Your own servers. Your own rules. One app.

**A next-generation Android VPN client that connects, builds and manages your private network — from a single tap.**

[![Latest release](https://img.shields.io/github/v/release/drfxai/MAXIMUS-VPN?style=for-the-badge&color=6C4CF1&label=Release)](https://github.com/drfxai/MAXIMUS-VPN/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/drfxai/MAXIMUS-VPN/total?style=for-the-badge&color=1E6BFF)](https://github.com/drfxai/MAXIMUS-VPN/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-00C853?style=for-the-badge&logo=android&logoColor=white)](https://github.com/drfxai/MAXIMUS-VPN/releases/latest)
[![Xray](https://img.shields.io/badge/Core-Xray-111827?style=for-the-badge)](THIRD_PARTY_NOTICES.md)

### [⬇ Download MAXIMUS VPN](https://github.com/drfxai/MAXIMUS-VPN/releases/latest)

</div>

---

## Why MAXIMUS VPN?

Most VPN apps give you a connect button and someone else's servers. **MAXIMUS VPN gives you the whole stack.**
Install a panel on your own server, deploy a private Cloudflare endpoint, generate working configurations,
find the fastest clean IPs and connect — all without opening a terminal.

<table>
<tr>
<td width="50%" valign="top">

### ⚡ One-tap server setup
Enter your VPS address and password. MAXIMUS VPN installs **3X-UI**, secures it with HTTPS,
opens the firewall, generates the login and verifies everything works.

</td>
<td width="50%" valign="top">

### ☁️ Private Cloudflare endpoint
Deploy a **BPB panel** to your own Cloudflare account in seconds. Username, password and the private
login link are created for you and shown ready to copy.

</td>
</tr>
<tr>
<td valign="top">

### 🛡️ Configs that actually work
**Quick Config** creates a VLESS + Reality + Vision inbound on your server, checks it on the panel,
tests the port and pings it — then adds it to the app automatically.

</td>
<td valign="top">

### 🌐 Clean IP scanner
Scan Cloudflare's edge, rank addresses by latency and stability, and bind the best one to your
configuration with a single tap.

</td>
</tr>
</table>

---

## Features

**Connectivity**
- VLESS, VMess, Trojan and Shadowsocks with the native **Xray** core
- Reality, TLS, WebSocket, gRPC, HTTP/2 and xHTTP transports
- Works with hostname and IP servers, including Cloudflare Workers endpoints
- Hysteria2, WireGuard and AmneziaWG (junk packets) on the same core
- Universal import: links, Base64 subscriptions, Xray JSON, Clash/Mihomo YAML and WireGuard `.conf` files
- Finds a working route before connecting: split TLS handshake, another browser fingerprint, ECH, UDP junk packets, or another protocol on the same server
- Subscriptions that survive blocking: mirrors, CDN copies of GitHub-hosted files, DoH lookups, an offline copy, and a refresh through the tunnel after connecting
- Telegram bot for distribution (`tools/telegram-bot`, a Cloudflare Worker)

**Server & panel workspace**
- 3X-UI installer with pinned HTTPS, generated credentials and firewall setup
- BPB Worker deployment on Cloudflare with generated login and subscription import
- Quick Config and Custom Inbound builder (Reality, WebSocket, xHTTP, RAW)
- Live server status and panel management from your phone

**Performance**
- Clean IP scanner with ranked results
- Server benchmarking, latency tests and automatic failover
- Top-10 ranking of your fastest nodes

**Protection**
- Built on Android `VpnService` with Always-on VPN and lockdown support
- Traffic protection while connecting, with a one-tap reset
- DNS and routing controls, live diagnostics and connection logs
- Panel credentials and tokens encrypted with the Android Keystore

**Smart tools**
- AI Agent for network diagnostics and guidance
- Light and dark themes, modern Jetpack Compose interface

---

## Download

| File | For |
|---|---|
| `MAXIMUSVPN-V1.0.0-arm64-v8a.apk` | Most modern Android phones (recommended) |
| `MAXIMUSVPN-V1.0.0-universal.apk` | Any supported Android device |
| `SHA256SUMS` | Verify your download |

**[Get the latest release →](https://github.com/drfxai/MAXIMUS-VPN/releases/latest)**

> Official builds are published **only** on this repository's Releases page. Avoid APK mirrors and repackaged copies.

## Get started in three steps

1. **Install** the APK and allow the VPN permission.
2. **Add servers** — import a link or subscription, or open **Panels** to install your own 3X-UI or BPB panel.
3. **Connect** — pick a node, tap the power button, done.

---

## Build from source

Requirements: JDK 17 and the Android SDK (platform 36.1, build-tools 36.0.0).

```bash
bash scripts/fetch-libxray-android.sh   # fetch and verify the native Xray runtime
bash gradlew :app:testDebugUnitTest
bash gradlew :app:assembleDebug
bash gradlew -PtargetAbi=arm64-v8a :app:assembleDebug   # ARM64-only build
```

## Security

Found a vulnerability? Please report it privately — see [SECURITY.md](SECURITY.md).
Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Panel automation details: [docs/PANELS.md](docs/PANELS.md).

---

<div align="center">

### MAXIMUS VPN

**Connect intelligently. Build freely. Stay in control.**

Made by **DrFXAi**

</div>
