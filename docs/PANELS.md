# Panel installation and configuration automation

## 3X-UI on your own server

1. **Test connection** reads the server's SSH host key (no credentials are sent). Verify the
   SHA-256 fingerprint with your provider, then install.
2. The SSH login is retried with growing timeouts and a reduced key-exchange list, because a
   stalled password handshake is common on busy servers and mobile networks. Wrong passwords and
   host-key mismatches are reported immediately and never retried.
3. The signed 3X-UI installer runs unattended. A re-run reuses an existing installation.
4. Android blocks plain-HTTP connections, so the installer also creates a self-signed certificate
   over the authenticated SSH session, applies it to the panel and records its SHA-256
   fingerprint. The app only talks to the panel when it presents exactly that certificate
   (certificate pinning). Browsers will show a self-signed warning for the panel link.
5. Host firewalls (ufw / firewalld) are opened for the panel port and the usual proxy ports.
6. The panel API is checked from the phone before the install is reported as finished.

The result card shows the panel link, generated username and generated password, each with a
copy button, plus the outcome of the checks.

### Creating configurations

**Quick Config** creates a VLESS + Reality + Vision inbound:

- Reality keys come from the panel itself.
- Port 443 is preferred, then 8443, then random free ports. Ports already used are skipped.
- The inbound is read back from the panel and compared with what was requested.
- The port is probed from the phone. If it does not answer, the inbound is removed and the next
  port is tried; if no port answers, the config is kept with a firewall hint.
- The link exported by the panel is used when complete; otherwise an equivalent link is built.
- The new profile is pinged from the device and the result is shown.

**Custom inbound** offers WebSocket, xHTTP, Reality and RAW. TLS requires a certificate on the
server and is refused with an explanation when the panel has none.

## BPB worker on Cloudflare

- Username, password and the VLESS identity are generated per deployment (or taken from the
  optional fields). The password is written to the worker's KV namespace and read back.
- After deployment the login endpoint and a VLESS WebSocket handshake are tested; the result is
  displayed with the credentials.
- **Add to VPN profiles** builds the profile from the deployed identity (UUID, host, WebSocket
  path with early data) and pings it.
