#!/usr/bin/env python3
"""Authenticated WARP provisioning and mandatory gateway pins for the exact pinned source."""
import pathlib, sys
root = pathlib.Path(sys.argv[1])
src = root / 'aether/src'
def replace(name, old, new):
    path = src / name
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'Pinned Aether source mismatch: {name}')
    path.write_text(text.replace(old, new))
# Fingerprint customization must never turn verification off for HTTPS account calls.
replace('tls.rs', 'builder.set_verify(SslVerifyMode::NONE);',
'''builder.set_verify(SslVerifyMode::PEER);''')
text = (src / 'tls.rs').read_text()
start = text.index('fn verify_enabled() -> bool {')
end = text.index('\nfn team_run()', start)
text = text[:start] + 'fn verify_enabled() -> bool { true }\n' + text[end:]
# Reject missing pins instead of silently accepting a certificate without authentication.
old = '''        builder.set_verify(SslVerifyMode::PEER);
        announce_once(
            "tls verification: none (--tls-verify set but no pins configured)".to_string(),
        );'''
assert old in text
text = text.replace(old, '''        return Err(AetherError::Tls("Missing gateway SPKI pins".into()));''')
(src / 'tls.rs').write_text(text)
# Chrome's requested root IDs are not proof that Android trusts those roots. Let the
# authenticated API server supply its ordinary chain, checked against Android's CA bundle.
# Keep the transport fingerprint (including MASQUE's separate SPKI policy) unchanged.
replace('https.rs', '    fingerprint.apply(&mut builder, ALPN_H2_HTTP1)?;', '''    let mut api_fingerprint = fingerprint.clone();
    api_fingerprint.trust_anchors = None;
    api_fingerprint.apply(&mut builder, ALPN_H2_HTTP1)?;
    let bundle = std::env::var("MAXIMUS_CA_BUNDLE")
        .map_err(|_| AetherError::Tls("Missing trusted certificate bundle".into()))?;
    builder.set_ca_file(bundle).map_err(|e| AetherError::Tls(e.to_string()))?;''')
replace('https.rs', '    fingerprint.prepare(&mut config, ALPN_H2_HTTP1)?;', '''    api_fingerprint.prepare(&mut config, ALPN_H2_HTTP1)?;
    config.set_verify_hostname(true);''')
replace('https.rs', '    // TLS server-certificate verification disabled (unconditional), as the fingerprint has it:\n    // the server name may be neither the HTTP host nor the address.',
    '    // Account HTTPS authenticates its host using the Android CA bundle below.')
# Consumer registration always uses its authenticated API host for both HTTP and TLS.
replace('https.rs', '    let (address, port) = request.address.unwrap_or((request.host, request.port));', '''    if request.sni.is_some_and(|sni| sni != request.host) {
        return Err(AetherError::Tls("Account SNI must match authenticated host".into()));
    }
    let (address, port) = request.address.unwrap_or((request.host, request.port));''')
# Keep the account bootstrap proxy separate from the actual WARP transport uplink.
replace('https.rs', '    match crate::upstream::configured() {', '''    let api = std::env::var("MAXIMUS_API_UPSTREAM").ok()
        .map(|value| crate::upstream::Upstream::parse(&value)).transpose()?;
    match api.or_else(crate::upstream::configured) {''')
# A Direct OOB helper already changes the wire pattern. Fragmenting its input a
# second time differs from the Android TLS reachability probe and can break it.
# Retry only a failed TLS handshake, before HTTP bytes (and registration) were sent.
replace('https.rs', '    tokio::time::timeout(timeout, exchange(request, fingerprint, ech, fragment))', r'''    let bridged = std::env::var_os("MAXIMUS_API_UPSTREAM").is_some();
    let fragment = if bridged { FragmentConfig::disabled() } else { fragment };
    tokio::time::timeout(timeout, async {
        let mut ech = ech;
        let first = exchange(request, fingerprint, ech.as_deref_mut(), fragment).await;
        match first {
            Err(ref error) if maximus_api_tls_retry(error) => {
                eprintln!("MAXIMUS_AETHER_API_TLS:compatible-retry");
                let compatible = Fingerprint {
                    groups: "X25519:P-256".to_string(),
                    grease: false,
                    permute_extensions: false,
                    ..Fingerprint::default()
                };
                // Same CA/hostname authentication and explicit ECH key. No HTTP
                // request existed on the failed TLS connection, so no signup is replayed.
                exchange(request, &compatible, ech, fragment).await
            }
            other => other,
        }
    })''')
# Bound only the handshake, where no POST can have been written. A whole-request
# timeout remains non-retryable because the server may already have registered it.
replace('https.rs', '        match tokio_boring::connect(config, request.sni.unwrap_or(request.host), stream).await {', r'''        let handshake = tokio::time::timeout(Duration::from_secs(8),
            tokio_boring::connect(config, request.sni.unwrap_or(request.host), stream))
            .await.map_err(|_| AetherError::Tls(format!(
                "handshake with {}: pre-HTTP timeout", authority(address, port))))?;
        match handshake {''')
replace('https.rs', 'async fn exchange(', r'''fn maximus_api_tls_retry(error: &AetherError) -> bool {
    let AetherError::Tls(message) = error else { return false; };
    let lower = message.to_ascii_lowercase();
    message.starts_with("handshake with ") &&
        !["certificate", "hostname", "unknown ca", "unknown_ca", "ip address mismatch", "ech"].iter().any(|word| lower.contains(*word))
}

#[cfg(test)]
mod maximus_api_retry_tests {
    use super::*;
    #[test]
    fn only_pre_http_handshake_errors_can_retry() {
        assert!(maximus_api_tls_retry(&AetherError::Tls("handshake with api: connection reset".into())));
        assert!(maximus_api_tls_retry(&AetherError::Tls("handshake with api: pre-HTTP timeout".into())));
        for text in ["handshake with api: CERTIFICATE_VERIFY_FAILED", "handshake with api: hostname mismatch",
            "handshake with api: unknown ca", "handshake with api: TLSV1_ALERT_UNKNOWN_CA",
            "handshake with api: IP address mismatch", "handshake with api: ECH_REJECTED", "Missing trusted certificate bundle"] {
            assert!(!maximus_api_tls_retry(&AetherError::Tls(text.into())));
        }
        // Includes response timeouts: POST might already have registered the device.
        assert!(!maximus_api_tls_retry(&AetherError::Api("registration did not answer".into())));
        assert!(!maximus_api_tls_retry(&AetherError::Api("h2 answer failed".into())));
    }
}

async fn exchange(''')

# Android account HTTPS adapter: authenticated loopback IPC, only the two WARP
# account hosts. Explicit ECH remains on native TLS rather than silently losing ECH.
replace('https.rs', '    let (address, port) = request.address.unwrap_or((request.host, request.port));', r'''    if ech.is_none() && matches!(request.host, "api.devices.cloudflare.com" | "api.cloudflareclient.com") {
        if let Ok(address) = std::env::var("MAXIMUS_API_HTTP") {
            let relay: std::net::SocketAddr = address.parse()
                .map_err(|_| AetherError::Api("Invalid account adapter address".into()))?;
            if !relay.ip().is_loopback() { return Err(AetherError::Api("Account adapter must be loopback".into())); }
            let token = std::env::var("MAXIMUS_API_HTTP_TOKEN")
                .map_err(|_| AetherError::Api("Account adapter authentication missing".into()))?;
            if token.len() < 32 || !token.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
                return Err(AetherError::Api("Invalid account adapter authentication".into()));
            }
            let mut headers = request.headers.to_vec();
            headers.push(("X-Maximus-Api-Token".into(), token));
            let local = Request { method: request.method, host: request.host, port: request.port,
                address: None, sni: None, path: request.path, headers: &headers, body: request.body };
            return over_http1(TcpStream::connect(relay).await?, &local).await;
        }
    }
    let (address, port) = request.address.unwrap_or((request.host, request.port));''')

# Require per-session RFC1929 credentials on the engine itself, including UDP associations.
replace('socks.rs', '    sock.write_all(&[VER, 0x00]).await?;\n    Ok(())', r'''    let expected_user = std::env::var("MAXIMUS_SOCKS_USER").unwrap_or_default();
    let expected_pass = std::env::var("MAXIMUS_SOCKS_PASS").unwrap_or_default();
    if expected_user.is_empty() || expected_pass.is_empty() || !methods.contains(&2) {
        sock.write_all(&[VER, 0xff]).await?;
        return Err(AetherError::Other("SOCKS authentication required".into()));
    }
    sock.write_all(&[VER, 2]).await?;
    let version = sock.read_u8().await?;
    let user_len = sock.read_u8().await? as usize;
    let mut user = vec![0u8; user_len];
    sock.read_exact(&mut user).await?;
    let pass_len = sock.read_u8().await? as usize;
    let mut pass = vec![0u8; pass_len];
    sock.read_exact(&mut pass).await?;
    let eq = |a: &[u8], b: &[u8]| -> bool {
        let mut difference = a.len() ^ b.len();
        for i in 0..a.len().max(b.len()) {
            difference |= usize::from(a.get(i).copied().unwrap_or(0) ^ b.get(i).copied().unwrap_or(0));
        }
        difference == 0
    };
    let valid = (version == 1) & eq(&user, expected_user.as_bytes()) & eq(&pass, expected_pass.as_bytes());
    sock.write_all(&[1, if valid { 0 } else { 1 }]).await?;
    if !valid { return Err(AetherError::Other("SOCKS credential refused".into())); }
    Ok(())''')
# An auth challenge also proves that a listener is serving; it does not grant proxy access.
replace('socks.rs', 'Some(reply == [VER, 0x00])', 'Some(reply == [VER, 0xff])')
# A local-only options validation entry for Android packaging checks; it opens no listeners.
replace('lib.rs', '    upstream::check_setting()?;', '''    upstream::check_setting()?;
    if std::env::var("MAXIMUS_VALIDATE_OPTIONS").as_deref() == Ok("1") {
        println!("MAXIMUS_AETHER_OPTIONS_OK");
        return Ok(());
    }''')
# Emit fixed stage events even when upstream logging is suppressed. A saved identity
# still refreshes its account before gateway selection; neither event proves traffic.
replace('lib.rs', '    upstream::check_setting()?;', '''    upstream::check_setting()?;
    eprintln!("MAXIMUS_AETHER_STAGE:account");''')
for function in ('run_masque', 'run_wireguard'):
    path = src / 'lib.rs'
    text = path.read_text()
    start = text.index(f'async fn {function}(')
    body = text.index(') -> Result<()> {', start) + len(') -> Result<()> {')
    text = text[:body] + '\n    eprintln!("MAXIMUS_AETHER_STAGE:identity");' + text[body:]
    path.write_text(text)
replace('lib.rs', '    let mode = prober::ScanMode::parse(mode_str);', '''    eprintln!("MAXIMUS_AETHER_STAGE:scan");
    let mode = prober::ScanMode::parse(mode_str);''')
replace('lib.rs', '    let mode = wg_prober::WgScanMode::parse(mode_str);', '''    eprintln!("MAXIMUS_AETHER_STAGE:scan");
    let mode = wg_prober::WgScanMode::parse(mode_str);''')
replace('lib.rs', '        log::info!("[+] using cloudflare edge {peer}");', '''        eprintln!("MAXIMUS_AETHER_STAGE:transport");
        log::info!("[+] using cloudflare edge {peer}");''')
# Mark the precise SOCKS listener only when it is being served.
replace('socks.rs', '    let listen = listener.borrow().local_addr()?;', '''    let listen = listener.borrow().local_addr()?;
    eprintln!("MAXIMUS_AETHER_SOCKS_READY:{}", listen.port());''')
replace('socks.rs', '    log::info!("[+] {kind} listening on {listen}");', '''    log::info!("[+] {kind} listening on {listen}");
    eprintln!("MAXIMUS_AETHER_SOCKS_READY:{}", listen.port());''')
replace('psiphon.rs', '    let socks_relay = crate::socks::forward(socks_listener, "psiphon socks5", running.socks);',
'''    eprintln!("MAXIMUS_AETHER_SOCKS_READY:{}", listen.port());
    let socks_relay = crate::socks::forward(socks_listener, "psiphon socks5", running.socks);''')
# The bundled relay clients authenticate to the tunnel listener as external app clients do.
with (src / 'upstream.rs').open('a') as output:
    output.write(r'''
pub fn maximus_loopback_proxy(address: std::net::SocketAddr) -> String {
    let user = std::env::var("MAXIMUS_SOCKS_USER").unwrap_or_default();
    let pass = std::env::var("MAXIMUS_SOCKS_PASS").unwrap_or_default();
    if user.is_empty() || pass.is_empty() { return format!("socks5://{address}"); }
    format!("socks5://{}:{}@{}", percent_encode(&user), percent_encode(&pass), address)
}
''')
replace('tor.rs', 'let parsed: ProxyProtocol = format!("socks5://{proxy}")', '''let setting = if through.is_some() { crate::upstream::maximus_loopback_proxy(proxy) }
                else { format!("socks5://{proxy}") };
            let parsed: ProxyProtocol = setting''')
replace('psiphon.rs', 'Some(format!("socks5://{through}"))', 'Some(crate::upstream::maximus_loopback_proxy(through))')
replace('lib.rs', 'let tunnel = upstream::Upstream::parse(&format!("socks5h://{through}"))?;',
        'let tunnel = upstream::Upstream::parse(&upstream::maximus_loopback_proxy(through))?;')
# carrier_upstream also connects to the authenticated Tor listener. Psiphon uses its own proxy.
replace('lib.rs', '    let setting = format!("socks5://{socks}");', '''    let setting = if name == "tor" {
        upstream::maximus_loopback_proxy(socks)
    } else { format!("socks5://{socks}") };''')
(root / 'MAXIMUS_POLICY').write_text('WARP API: CA + hostname; MASQUE: mandatory upstream SPKI pins\n')
print('Aether authenticated registration and gateway pin policy applied')
