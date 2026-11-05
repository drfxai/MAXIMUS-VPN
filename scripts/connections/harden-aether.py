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
replace('https.rs', '    fingerprint.apply(&mut builder, ALPN_H2_HTTP1)?;', '''    fingerprint.apply(&mut builder, ALPN_H2_HTTP1)?;
    let bundle = std::env::var("MAXIMUS_CA_BUNDLE")
        .map_err(|_| AetherError::Tls("Missing trusted certificate bundle".into()))?;
    builder.set_ca_file(bundle).map_err(|e| AetherError::Tls(e.to_string()))?;''')
replace('https.rs', '    fingerprint.prepare(&mut config, ALPN_H2_HTTP1)?;', '''    fingerprint.prepare(&mut config, ALPN_H2_HTTP1)?;
    config.set_verify_hostname(true);''')
# Consumer registration always uses its authenticated API host for both HTTP and TLS.
replace('https.rs', '    let (address, port) = request.address.unwrap_or((request.host, request.port));', '''    if request.sni.is_some_and(|sni| sni != request.host) {
        return Err(AetherError::Tls("Account SNI must match authenticated host".into()));
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
# The listener is bound before registration/scanning. Emit a fixed, secret-free
# marker only once a tunnel's netstack is actually serving it, even at RUST_LOG=warn.
replace('lib.rs', '    async fn serve(&self, stack: &netstack::StackHandle) -> std::convert::Infallible {', '''    async fn serve(&self, stack: &netstack::StackHandle) -> std::convert::Infallible {
        eprintln!("MAXIMUS_AETHER_SOCKS_READY");''')
(root / 'MAXIMUS_POLICY').write_text('WARP API: CA + hostname; MASQUE: mandatory upstream SPKI pins\n')
print('Aether authenticated registration and gateway pin policy applied')
