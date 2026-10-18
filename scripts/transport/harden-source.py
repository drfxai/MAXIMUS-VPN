#!/usr/bin/env python3
"""Apply the MAXIMUS TLS policy to the pinned, separately built transport source."""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
src = root / 'core/aether/src'
def replace(path, old, new):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'Pinned source changed: expected pattern missing in {path.name}')
    path.write_text(text.replace(old, new))

# Every MASQUE handshake uses the source's explicit SPKI allowlist, including probes.
for name in ('quic.rs', 'main.rs', 'prober.rs'):
    path = src / name
    text = path.read_text()
    text = text.replace('pin_endpoint: false,', 'pin_endpoint: true,')
    text = text.replace('expected_pins: &[],', 'expected_pins: crate::consts::MASQUE_PINS,')
    text = text.replace('expected_pins: Vec::new(),', 'expected_pins: crate::consts::MASQUE_PINS.iter().map(|p| p.to_vec()).collect(),')
    text = text.replace('config.verify_peer(false);', 'config.verify_peer(true);')
    path.write_text(text)

path = src / 'tls.rs'
text = path.read_text()
start = text.index('        // No pin-based verification configured')
end = text.index('\n    }\n    Ok(())', start)
text = text[:start] + '        return Err(AetherError::Tls("Server authentication requires configured SPKI pins".into()));' + text[end:]
text = text.replace('    if pin_endpoint && !expected_pins.is_empty() {', '''    if expected_pins.iter().any(|p| p.len() != 32) {
        return Err(AetherError::Tls("Invalid SPKI pin length".into()));
    }
    if pin_endpoint && !expected_pins.is_empty() {''', 1)
text += '''
pub fn policy_self_test() -> Result<()> {
    let mut b = SslContextBuilder::new(SslMethod::tls()).map_err(|e| AetherError::Tls(e.to_string()))?;
    if install_verification(&mut b, false, &[]).is_ok() || install_verification(&mut b, true, &[]).is_ok()
        || install_verification(&mut b, true, &[&[0u8; 2]]).is_ok() {
        return Err(AetherError::Tls("Invalid TLS policy accepted".into()));
    }
    install_verification(&mut b, true, crate::consts::MASQUE_PINS)?;
    if b.build().verify_mode() != SslVerifyMode::PEER {
        return Err(AetherError::Tls("Peer verification not enabled".into()));
    }
    Ok(())
}
#[cfg(test)]
mod maximus_tls_policy {
    use super::*;
    #[test]
    fn missing_pins_are_rejected() {
        let mut builder = SslContextBuilder::new(SslMethod::tls()).unwrap();
        assert!(install_verification(&mut builder, false, &[]).is_err());
        assert!(install_verification(&mut builder, true, &[]).is_err());
    }
    #[test]
    fn malformed_pins_are_rejected() {
        let mut builder = SslContextBuilder::new(SslMethod::tls()).unwrap();
        assert!(install_verification(&mut builder, true, &[&[0u8; 2]]).is_err());
    }
    #[test]
    fn explicit_pins_install_peer_verification() {
        let mut builder = SslContextBuilder::new(SslMethod::tls()).unwrap();
        install_verification(&mut builder, true, crate::consts::MASQUE_PINS).unwrap();
        assert_eq!(builder.build().verify_mode(), SslVerifyMode::PEER);
    }
}
'''
path.write_text(text)
# The allowlisted endpoint certificate is selected with its actual service name.
replace(src / 'consts.rs', 'pub const CONNECT_SNI: &str = "www.cloudflare.com";',
        'pub const CONNECT_SNI: &str = "consumer-masque.cloudflareclient.com";')
replace(src / 'consts.rs', 'pub const L4_CONNECT_SNI: &str = "www.cloudflare.com";',
        'pub const L4_CONNECT_SNI: &str = "consumer-masque.cloudflareclient.com";')
# Account provisioning carries tokens: disable the upstream unauthenticated fronting path entirely.
path = src / 'apifront.rs'
replace(path, 'config.set_verify_hostname(false);', 'config.set_verify_hostname(true);')
replace(path, 'config.set_verify(boring::ssl::SslVerifyMode::NONE);',
        'return Err(AetherError::Tls("Unverified account fronting is disabled".into()));')
path = src / 'account.rs'
start = path.read_text().index("fn api_camo() -> &'static str {")
text = path.read_text()
end = text.index('\nasync fn routed_account_call', start)
text = text[:start] + '''fn api_camo() -> &'static str {
    // reqwest validates the API's CA chain and hostname. Never fall back to unverified fronting.
    "external"
}
''' + text[end:]
path.write_text(text)
# Distinct product name; attribution and upstream license remain in the source offer.
path = src / 'cli.rs'
text = path.read_text().replace('Usage: aether [OPTIONS]', 'Usage: matrix-transport [OPTIONS]')
start = text.index('            "-v" | "--version" => {')
end = text.index('\n            "-h" | "--help"', start)
text = text[:start] + '''            "--self-test" => {
                crate::tls::policy_self_test()?;
                println!("Matrix Transport TLS policy self-test passed");
                std::process::exit(0);
            }
            "-v" | "--version" => {
                println!("Matrix Transport secure-v1 (Aether-derived 1.6.0)");
                std::process::exit(0);
            }
''' + text[end:]
path.write_text(text)
# Use the normal checksum-verified registry instead of a developer's alternate mirror.
(root / 'core/aether/.cargo/config.toml').write_text('')
path = root / 'core/build-android.sh'
text = path.read_text().replace('build --release --lib', 'build --locked --release --bin aether')
text = text.replace('/release/libaether.so', '/release/aether')
path.write_text(text)
# Fail closed on a partial patch; comments are not treated as executable statements.
for path in src.glob('*.rs'):
    for line in path.read_text().splitlines():
        if line.lstrip().startswith('//'):
            continue
        if 'SslVerifyMode::NONE' in line or 'verify_peer(false)' in line or 'pin_endpoint: false,' in line:
            raise SystemExit(f'Unauthenticated TLS code remains in {path.name}')
(root / 'MAXIMUS_TRANSPORT_POLICY').write_text('secure-v1\n')
print('Transport TLS policy applied; missing pins and unverified account fronting are rejected.')
