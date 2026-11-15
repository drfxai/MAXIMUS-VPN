//! MAXIMUS: WARP account API lanes for networks that filter the API ClientHello (Irancell, MCI).
//!
//! Part of the separately built, modified Aether executable (AGPL-3.0-only); never linked into the
//! app. Ported and adapted from UAC SNI Spoofer (Floxu1/UAC-SNI-Spoofer-Android, commit 200de3b,
//! `core/aether/src/oob.rs` and `core/aether/src/relay.rs`, AGPL-3.0-only), with the identity race
//! idea of WhiteDNS/WhiteAestherMobile (v1.16.0 "OOB API lane": several ClientHello shapes race and
//! only the winner sends the API request).
//!
//! Lanes, raced against each other for each account call (register, enroll, refresh):
//! - `direct`: Aether's own call, unchanged (configured fingerprint, `--fragment`, ECH when given).
//! - `oob:<method>/<tls>@v4|v6`: the ClientHello to api.cloudflareclient.com sent with a TCP urgent
//!   byte (MSG_OOB) desync, the TTL=1 variants letting the first segment die at the first hop, so
//!   the DPI never sees an in-order hello; the server's TCP strips the urgent byte and reads exactly
//!   the hello. Pinned to Cloudflare's API addresses only. TLS 1.2 without ALPN first, as on Android
//!   in UAC (the TLS 1.3 exchange returned 0 bytes there).
//! - `relay@<family>`: the call wrapped in JSON and posted to the user's own Cloudflare Worker
//!   (`AETHER_API_RELAY`, an https URL), which forwards it to the API from Cloudflare's edge. The
//!   relay host is reached on built-in Cloudflare edges (IPv4 and IPv6) and on its DoH-by-IP answers.
//!
//! Only TLS handshakes race; nothing is written to a losing connection, and the request is written
//! once, on the winner. A failure after the winner's handshake is never replayed (the account POST
//! may already have registered). Certificates are always verified against Android's CA bundle with
//! the hostname checked, on every lane. The winning lane is printed as
//! `MAXIMUS_AETHER_API_LANE:<label>` (no addresses, no hosts) and goes first in the later calls.

use std::future::Future;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::Mutex;
use std::task::{Context, Poll};
use std::time::Duration;

use boring::ssl::{ConnectConfiguration, SslConnector, SslMethod, SslVersion};
use tokio::io::{AsyncRead, AsyncWrite, Interest, ReadBuf};
use tokio::net::TcpStream;
use tokio::task::JoinSet;

use super::{configuration, dial, over_http1, over_http2, Request, Response};
use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};
use crate::tls::Fingerprint;

// MAXIMUS-PURE-BEGIN (std only; also compiled alone by scripts/connections/test-aether-api-lanes.py)

/// api.cloudflareclient.com A / AAAA, as UAC pins them (2026-10-23, Google and Cloudflare DoH).
pub const OOB_API_HOST: &str = "api.cloudflareclient.com";
pub const OOB_EDGES_V4: [&str; 2] = ["104.16.24.84", "104.16.192.82"];
pub const OOB_EDGES_V6: [&str; 2] = ["2606:4700::6810:1854", "2606:4700::6810:c052"];
/// Cloudflare anycast edges that serve any proxied host, workers.dev included (UAC relay.rs). The
/// IPv6 pair races from the first moment: on an MCI hotspot every IPv4 handshake to Cloudflare
/// timed out while IPv6 answered at once (UAC, 2026-10-22).
pub const RELAY_EDGES: [&str; 4] = [
    "104.21.60.172",
    "172.67.198.113",
    "2606:4700:3035::6815:3cac",
    "2606:4700:3037::ac43:c671",
];

/// The ClientHello send methods (UAC `OobMethod`, the Android order). Byte logic mirrors UAC's.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OobMethod {
    /// TTL=1, `ch[0]` as urgent, TTL restored, 50 ms, then the full hello.
    DisoobByte0Ttl1,
    /// off = SNI host start + 5. TTL=1, `ch[..off]`, `ch[off]` as urgent, TTL restored, 50 ms,
    /// then `ch[off..]`.
    OobInSni5Ttl1,
    /// `ch[0]` as urgent (TTL untouched), 30 ms, then the full hello. Flaky, so late.
    OobByte0NoTtl,
    /// TTL=1, `ch[0]` with a normal send (no MSG_OOB), TTL restored, 50 ms, then the full hello.
    DisoobByte0Ttl1Plain,
    /// As DisoobByte0Ttl1, then `ch[0]` as its own normal segment, then `ch[1..]`.
    DisoobByte0Ttl1Split,
}

pub const METHOD_ORDER: [OobMethod; 5] = [
    OobMethod::DisoobByte0Ttl1,
    OobMethod::OobInSni5Ttl1,
    OobMethod::OobByte0NoTtl,
    OobMethod::DisoobByte0Ttl1Plain,
    OobMethod::DisoobByte0Ttl1Split,
];

impl OobMethod {
    pub fn name(self) -> &'static str {
        match self {
            OobMethod::DisoobByte0Ttl1 => "disoob-byte0-ttl1",
            OobMethod::OobInSni5Ttl1 => "oob-insni+5-ttl1",
            OobMethod::OobByte0NoTtl => "oob-byte0-nottl",
            OobMethod::DisoobByte0Ttl1Plain => "disoob-byte0-ttl1-plain",
            OobMethod::DisoobByte0Ttl1Split => "disoob-byte0-ttl1-split",
        }
    }

    pub fn from_name(name: &str) -> Option<OobMethod> {
        METHOD_ORDER.iter().copied().find(|m| m.name() == name.trim())
    }

    pub fn uses_ttl1(self) -> bool {
        !matches!(self, OobMethod::OobByte0NoTtl)
    }

    /// SNI-relative split (`None` = byte 0).
    pub fn sni_rel(self) -> Option<usize> {
        match self {
            OobMethod::OobInSni5Ttl1 => Some(5),
            _ => None,
        }
    }

    /// Pause after the desync prefix, before the rest goes normally (UAC Android: 50 / 30 ms).
    pub fn pause_ms(self) -> u64 {
        if self.uses_ttl1() {
            50
        } else {
            30
        }
    }
}

/// `AETHER_OOB_METHODS` (comma list) when it names known methods, otherwise METHOD_ORDER.
pub fn methods_from(raw: Option<&str>) -> Vec<OobMethod> {
    let picked: Vec<OobMethod> = raw
        .unwrap_or("")
        .split(',')
        .filter_map(OobMethod::from_name)
        .collect();
    if picked.is_empty() {
        METHOD_ORDER.to_vec()
    } else {
        picked
    }
}

/// First occurrence of the SNI host name bytes in the hello.
pub fn find_sni(buf: &[u8], host: &str) -> Option<usize> {
    let name = host.as_bytes();
    if name.is_empty() || name.len() > buf.len() {
        return None;
    }
    buf.windows(name.len()).position(|w| w == name)
}

/// Split offset of `method` in `buf` (SNI not found or out of range: 0), always `< buf.len()`
/// for a non-empty buffer.
pub fn split_offset(buf: &[u8], method: OobMethod, sni_host: &str) -> usize {
    match method.sni_rel() {
        None => 0,
        Some(rel) => {
            let off = find_sni(buf, sni_host).unwrap_or(0) + rel;
            if off >= buf.len() {
                0
            } else {
                off
            }
        }
    }
}

const CLOUDFLARE_V4: [([u8; 4], u8); 15] = [
    ([173, 245, 48, 0], 20),
    ([103, 21, 244, 0], 22),
    ([103, 22, 200, 0], 22),
    ([103, 31, 4, 0], 22),
    ([141, 101, 64, 0], 18),
    ([108, 162, 192, 0], 18),
    ([190, 93, 240, 0], 20),
    ([188, 114, 96, 0], 20),
    ([197, 234, 240, 0], 22),
    ([198, 41, 128, 0], 17),
    ([162, 158, 0, 0], 15),
    ([104, 16, 0, 0], 13),
    ([104, 24, 0, 0], 14),
    ([172, 64, 0, 0], 13),
    ([131, 0, 72, 0], 22),
];
const CLOUDFLARE_V6: [([u16; 2], u8); 7] = [
    ([0x2400, 0xcb00], 32),
    ([0x2606, 0x4700], 32),
    ([0x2803, 0xf800], 32),
    ([0x2405, 0xb500], 32),
    ([0x2405, 0x8100], 32),
    ([0x2a06, 0x98c0], 29),
    ([0x2c0f, 0xf248], 32),
];

/// Whether `ip` is in Cloudflare's published ranges: a resolver answer outside them is never dialed.
pub fn is_cloudflare_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let value = u32::from(v4);
            CLOUDFLARE_V4.iter().any(|(net, bits)| {
                let mask = u32::MAX << (32 - u32::from(*bits));
                value & mask == u32::from_be_bytes(*net) & mask
            })
        }
        IpAddr::V6(v6) => {
            let seg = v6.segments();
            let head = (u32::from(seg[0]) << 16) | u32::from(seg[1]);
            CLOUDFLARE_V6.iter().any(|(net, bits)| {
                let mask = u32::MAX << (32 - u32::from(*bits));
                let net = (u32::from(net[0]) << 16) | u32::from(net[1]);
                head & mask == net & mask
            })
        }
    }
}

/// The relay of `AETHER_API_RELAY`: its host and path. Only `https://host[:443][/path]`, with a
/// DNS name host and a plain path, so nothing in it can bend where the call goes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayTarget {
    pub host: String,
    pub path: String,
}

pub fn parse_relay(raw: &str) -> Option<RelayTarget> {
    let rest = raw.trim().strip_prefix("https://")?;
    let (authority, path) = match rest.find('/') {
        Some(at) => (&rest[..at], &rest[at..]),
        None => (rest, "/"),
    };
    let host = match authority.rsplit_once(':') {
        Some((host, "443")) => host,
        Some(_) => return None,
        None => authority,
    }
    .to_ascii_lowercase();
    let label_ok = |label: &str| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
    };
    let labels: Vec<&str> = host.split('.').collect();
    if host.len() > 253 || labels.len() < 2 || !labels.iter().all(|l| label_ok(l)) {
        return None;
    }
    if labels.last().is_some_and(|tld| tld.bytes().all(|b| b.is_ascii_digit())) {
        return None;
    }
    if path.len() > 256
        || !path
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'-' | b'_' | b'.' | b'~'))
        || path.contains("..")
    {
        return None;
    }
    Some(RelayTarget {
        host,
        path: path.to_string(),
    })
}

/// What one lane does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LaneKind {
    Direct,
    Oob { method: OobMethod, tls12: bool, v6: bool },
    Relay { edge: usize },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaneSpec {
    pub label: String,
    pub kind: LaneKind,
    pub delay_ms: u64,
}

/// When the first OOB method starts, and the gap to the next one.
pub const OOB_FIRST_MS: u64 = 1_000;
pub const OOB_STEP_MS: u64 = 300;

/// How long the previous winner runs alone before the others join it.
pub const WINNER_HEAD_START_MS: u64 = 1_500;

/// The lanes of one call, with their start delays: `direct` and the relay edges at once, then, from
/// 1 s on, one OOB method every 300 ms (TLS 1.2 without ALPN, an IPv4 and an IPv6 API edge each),
/// then the first method once more with TLS 1.3. Starting late, the OOB lane wins only where the
/// ordinary call does not answer (as WhiteAestherMobile's lane does). The lane that won the last
/// call goes alone first.
pub fn plan(oob: bool, relay: bool, methods: &[OobMethod], winner: Option<&str>) -> Vec<LaneSpec> {
    let mut lanes = vec![LaneSpec {
        label: "direct".to_string(),
        kind: LaneKind::Direct,
        delay_ms: 0,
    }];
    if relay {
        for (edge, raw) in RELAY_EDGES.iter().enumerate() {
            let family = if raw.contains(':') { "v6" } else { "v4" };
            lanes.push(LaneSpec {
                label: format!("relay@{family}"),
                kind: LaneKind::Relay { edge },
                delay_ms: 0,
            });
        }
    }
    if oob {
        let mut at = OOB_FIRST_MS;
        for method in methods {
            for v6 in [false, true] {
                lanes.push(LaneSpec {
                    label: format!("oob:{}/tls12@{}", method.name(), if v6 { "v6" } else { "v4" }),
                    kind: LaneKind::Oob { method: *method, tls12: true, v6 },
                    delay_ms: at,
                });
            }
            at += OOB_STEP_MS;
        }
        if let Some(first) = methods.first() {
            lanes.push(LaneSpec {
                label: format!("oob:{}/tls13@v4", first.name()),
                kind: LaneKind::Oob { method: *first, tls12: false, v6: false },
                delay_ms: at,
            });
        }
    }
    if let Some(won) = winner {
        if lanes.iter().any(|lane| lane.label == won) {
            for lane in &mut lanes {
                lane.delay_ms = if lane.label == won {
                    0
                } else {
                    lane.delay_ms + WINNER_HEAD_START_MS
                };
            }
        }
    }
    lanes
}

/// A lane label as printed: the plan's own alphabet only.
pub fn printable_label(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= 64
        && label
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"-+/:@.".contains(&b))
}

/// `AETHER_API_LANE_HANDSHAKE_MS`, clamped to 2-15 s, default 7 s.
pub fn handshake_ms(raw: Option<&str>) -> u64 {
    raw.and_then(|v| v.trim().parse::<u64>().ok())
        .map(|v| v.clamp(2_000, 15_000))
        .unwrap_or(7_000)
}

// MAXIMUS-PURE-END

fn truthy(name: &str) -> bool {
    std::env::var(name).is_ok_and(|v| crate::fragment::is_truthy(&v))
}

fn oob_enabled() -> bool {
    truthy("AETHER_API_OOB")
}

fn relay_target() -> Option<RelayTarget> {
    let raw = std::env::var("AETHER_API_RELAY").ok()?;
    if raw.trim().is_empty() {
        return None;
    }
    let target = parse_relay(&raw);
    if target.is_none() {
        static SAID: std::sync::Once = std::sync::Once::new();
        SAID.call_once(|| eprintln!("MAXIMUS_AETHER_API_LANE:relay-invalid"));
    }
    target
}

/// Whether this account call races lanes: a WARP API host on a direct connection (no bridge, no
/// loopback adapter, no upstream proxy), with the OOB lane or a relay switched on.
pub(super) fn applies(request: &Request<'_>) -> bool {
    if !matches!(
        request.host,
        "api.devices.cloudflare.com" | "api.cloudflareclient.com"
    ) {
        return false;
    }
    if request.sni.is_some_and(|sni| sni != request.host) {
        return false;
    }
    if std::env::var_os("MAXIMUS_API_UPSTREAM").is_some()
        || std::env::var_os("MAXIMUS_API_HTTP").is_some()
        || crate::upstream::configured().is_some()
    {
        return false;
    }
    oob_enabled() || relay_target().is_some()
}

static WINNER: Mutex<Option<String>> = Mutex::new(None);

fn winner() -> Option<String> {
    WINNER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .clone()
}

fn remember_winner(label: &str) {
    *WINNER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(label.to_string());
}

// ---- OOB socket desync (UAC oob.rs, adapted) ----

const FALLBACK_TTL: u32 = 64;

fn current_ttl(sock: &socket2::SockRef<'_>, v6: bool) -> u32 {
    let value = if v6 { sock.unicast_hops_v6() } else { sock.ttl_v4() };
    match value {
        Ok(ttl) if ttl > 0 && ttl < 256 => ttl,
        _ => FALLBACK_TTL,
    }
}

fn set_ttl(sock: &socket2::SockRef<'_>, v6: bool, ttl: u32) -> io::Result<()> {
    if v6 {
        let hops = sock.set_unicast_hops_v6(ttl);
        let _ = sock.set_ttl_v4(ttl);
        hops
    } else {
        sock.set_ttl_v4(ttl)
    }
}

fn send_all_now(sock: &socket2::SockRef<'_>, data: &[u8]) -> io::Result<()> {
    if data.is_empty() {
        return Ok(());
    }
    let sent = sock.send(data)?;
    if sent != data.len() {
        return Err(io::Error::other(format!(
            "oob: short prefix send ({sent} of {} bytes)",
            data.len()
        )));
    }
    Ok(())
}

fn ensure_ttl(sock: &socket2::SockRef<'_>, v6: bool, want: u32) -> io::Result<()> {
    let now = current_ttl(sock, v6);
    if now != want {
        return Err(io::Error::other(format!("oob: TTL is {now}, want {want}")));
    }
    Ok(())
}

/// The desync prefix of `method`. Returns the offset in `buf` from which the rest goes normally
/// after the pause. The TTL is always restored (and read back) before anything else is sent.
fn oob_prefix_send(tcp: &TcpStream, buf: &[u8], method: OobMethod, sni_host: &str) -> io::Result<usize> {
    if buf.len() < 2 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "oob: hello too short"));
    }
    let sock = socket2::SockRef::from(tcp);
    let v6 = tcp.peer_addr()?.is_ipv6();
    let off = split_offset(buf, method, sni_host);
    let saved = current_ttl(&sock, v6);
    let _ = sock.set_out_of_band_inline(false);

    if method == OobMethod::DisoobByte0Ttl1Plain {
        set_ttl(&sock, v6, 1)?;
        if let Err(error) = ensure_ttl(&sock, v6, 1) {
            let _ = set_ttl(&sock, v6, saved);
            return Err(error);
        }
        let sent = send_all_now(&sock, &buf[..1]);
        let restored = set_ttl(&sock, v6, saved);
        sent?;
        restored?;
        ensure_ttl(&sock, v6, saved)?;
        return Ok(0);
    }

    let ttl1 = method.uses_ttl1();
    if ttl1 {
        set_ttl(&sock, v6, 1)?;
        if let Err(error) = ensure_ttl(&sock, v6, 1) {
            let _ = set_ttl(&sock, v6, saved);
            return Err(error);
        }
    }
    let result = (|| -> io::Result<()> {
        send_all_now(&sock, &buf[..off])?;
        let sent = sock.send_out_of_band(&buf[off..off + 1])?;
        if sent != 1 {
            return Err(io::Error::other("oob: urgent byte was not sent"));
        }
        Ok(())
    })();
    if ttl1 {
        let restored = set_ttl(&sock, v6, saved);
        result?;
        restored?;
        ensure_ttl(&sock, v6, saved)?;
    } else {
        result?;
    }
    if method == OobMethod::DisoobByte0Ttl1Split && off == 0 {
        send_all_now(&sock, &buf[..1])?;
        return Ok(1);
    }
    Ok(off)
}

enum OobState {
    Prefix(OobMethod),
    Pause(Pin<Box<tokio::time::Sleep>>, usize),
    Off,
}

/// A TCP stream whose first real write (the ClientHello) goes out with an OOB desync.
struct OobStream {
    inner: TcpStream,
    state: OobState,
    sni: String,
}

impl AsyncRead for OobStream {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_read(cx, buf)
    }
}

impl AsyncWrite for OobStream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if let OobState::Prefix(method) = this.state {
            if buf.len() > 1 {
                let sent = loop {
                    match this.inner.poll_write_ready(cx) {
                        Poll::Pending => return Poll::Pending,
                        Poll::Ready(Err(error)) => {
                            this.state = OobState::Off;
                            return Poll::Ready(Err(error));
                        }
                        Poll::Ready(Ok(())) => {}
                    }
                    let inner = &this.inner;
                    let sni = this.sni.as_str();
                    match inner.try_io(Interest::WRITABLE, || oob_prefix_send(inner, buf, method, sni)) {
                        Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                        other => break other,
                    }
                };
                match sent {
                    Err(error) => {
                        this.state = OobState::Off;
                        return Poll::Ready(Err(error));
                    }
                    Ok(resume) => {
                        this.state = OobState::Pause(
                            Box::pin(tokio::time::sleep(Duration::from_millis(method.pause_ms()))),
                            resume,
                        );
                    }
                }
            }
        }
        if let OobState::Pause(sleep, resume) = &mut this.state {
            // The TLS layer retries the same hello buffer until bytes are taken.
            if sleep.as_mut().poll(cx).is_pending() {
                return Poll::Pending;
            }
            let resume = (*resume).min(buf.len());
            this.state = OobState::Off;
            return match Pin::new(&mut this.inner).poll_write(cx, &buf[resume..]) {
                Poll::Ready(Ok(n)) => Poll::Ready(Ok(resume + n)),
                Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
                // Bytes before `resume` already left: report them. With 0 nothing was taken.
                Poll::Pending if resume > 0 => Poll::Ready(Ok(resume)),
                Poll::Pending => Poll::Pending,
            };
        }
        Pin::new(&mut this.inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

/// The stream under a lane's TLS: Aether's own (fragmenting when `--fragment`) or the OOB one.
enum LaneStream {
    Plain(FragmentingStream<TcpStream>),
    Oob(OobStream),
}

impl std::fmt::Debug for LaneStream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            LaneStream::Plain(_) => "LaneStream::Plain",
            LaneStream::Oob(_) => "LaneStream::Oob",
        })
    }
}

impl AsyncRead for LaneStream {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            LaneStream::Plain(stream) => Pin::new(stream).poll_read(cx, buf),
            LaneStream::Oob(stream) => Pin::new(stream).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for LaneStream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        match self.get_mut() {
            LaneStream::Plain(stream) => Pin::new(stream).poll_write(cx, buf),
            LaneStream::Oob(stream) => Pin::new(stream).poll_write(cx, buf),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            LaneStream::Plain(stream) => Pin::new(stream).poll_flush(cx),
            LaneStream::Oob(stream) => Pin::new(stream).poll_flush(cx),
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            LaneStream::Plain(stream) => Pin::new(stream).poll_shutdown(cx),
            LaneStream::Oob(stream) => Pin::new(stream).poll_shutdown(cx),
        }
    }
}

type LaneTls = tokio_boring::SslStream<LaneStream>;

/// The compatible ClientHello of the OOB lanes: no GREASE, fixed extension order, X25519/P-256.
fn compatible_fingerprint() -> Fingerprint {
    Fingerprint {
        groups: "X25519:P-256".to_string(),
        grease: false,
        permute_extensions: false,
        trust_anchors: None,
        ..Fingerprint::default()
    }
}

/// The OOB lane's TLS: TLS 1.2 without ALPN (UAC's Android first choice) or TLS 1.2-1.3 with
/// http/1.1. Verified against Android's CA bundle, hostname checked, as every account call.
fn oob_configuration(tls12: bool) -> Result<ConnectConfiguration> {
    let tls = |e: boring::error::ErrorStack| AetherError::Tls(e.to_string());
    let fingerprint = compatible_fingerprint();
    let alpn: &[u8] = if tls12 { b"" } else { b"\x08http/1.1" };
    let mut builder = SslConnector::builder(SslMethod::tls()).map_err(tls)?;
    fingerprint.apply(&mut builder, alpn)?;
    if tls12 {
        builder
            .set_max_proto_version(Some(SslVersion::TLS1_2))
            .map_err(tls)?;
    }
    let bundle = std::env::var("MAXIMUS_CA_BUNDLE")
        .map_err(|_| AetherError::Tls("Missing trusted certificate bundle".into()))?;
    builder.set_ca_file(bundle).map_err(tls)?;
    let mut config = builder.build().configure().map_err(tls)?;
    fingerprint.prepare(&mut config, alpn)?;
    config.set_verify_hostname(true);
    Ok(config)
}

async fn direct_handshake(
    sni: String,
    address: String,
    port: u16,
    fingerprint: Fingerprint,
    ech: Option<Vec<u8>>,
    fragment: FragmentConfig,
) -> std::result::Result<LaneTls, String> {
    let mut config = configuration(&fingerprint).map_err(|e| e.to_string())?;
    if let Some(list) = ech.as_deref() {
        crate::tls::ensure_offerable(list).map_err(|e| e.to_string())?;
        config.set_ech_config_list(list).map_err(|e| e.to_string())?;
    }
    let tcp = dial(&address, port).await.map_err(|e| e.to_string())?;
    let _ = tcp.set_nodelay(true);
    let stream = LaneStream::Plain(FragmentingStream::new(tcp, fragment));
    let tls = tokio_boring::connect(config, &sni, stream)
        .await
        .map_err(|e| format!("handshake: {e}"))?;
    if ech.is_some() && !tls.ssl().ech_accepted() {
        return Err("the handshake went without ECH".into());
    }
    Ok(tls)
}

async fn oob_handshake(address: SocketAddr, method: OobMethod, tls12: bool) -> std::result::Result<LaneTls, String> {
    let config = oob_configuration(tls12).map_err(|e| e.to_string())?;
    let tcp = crate::egress::tcp_connect(address)
        .await
        .map_err(|e| format!("connect: {e}"))?;
    let _ = tcp.set_nodelay(true);
    let stream = LaneStream::Oob(OobStream {
        inner: tcp,
        state: OobState::Prefix(method),
        sni: OOB_API_HOST.to_string(),
    });
    tokio_boring::connect(config, OOB_API_HOST, stream)
        .await
        .map_err(|e| format!("handshake: {e}"))
}

async fn relay_handshake(host: String, address: SocketAddr, fingerprint: Fingerprint) -> std::result::Result<LaneTls, String> {
    let config = configuration(&fingerprint).map_err(|e| e.to_string())?;
    let tcp = crate::egress::tcp_connect(address)
        .await
        .map_err(|e| format!("connect: {e}"))?;
    let _ = tcp.set_nodelay(true);
    let stream = LaneStream::Plain(FragmentingStream::new(tcp, FragmentConfig::disabled()));
    tokio_boring::connect(config, &host, stream)
        .await
        .map_err(|e| format!("handshake: {e}"))
}

/// DoH-by-IP (no resolver name to look up first) for the relay host: A and AAAA from Cloudflare
/// and Google, Cloudflare addresses only. Certificates are checked against the IP address. Its
/// own small client, never `https::send`, so the account call path is not re-entered.
async fn doh_query(server: &'static str, path: &'static str, host: String, kind: &'static str, rtype: u64) -> Vec<IpAddr> {
    let Ok(ip) = server.parse::<IpAddr>() else { return Vec::new() };
    let query = async {
        let config = configuration(&Fingerprint::default()).ok()?;
        let tcp = crate::egress::tcp_connect(SocketAddr::new(ip, 443)).await.ok()?;
        let _ = tcp.set_nodelay(true);
        let stream = LaneStream::Plain(FragmentingStream::new(tcp, FragmentConfig::disabled()));
        let tls = tokio_boring::connect(config, server, stream).await.ok()?;
        let path = format!("{path}?name={host}&type={kind}");
        let headers = vec![("Accept".to_string(), "application/dns-json".to_string())];
        let request = Request {
            method: "GET",
            host: server,
            port: 443,
            address: None,
            sni: None,
            path: &path,
            headers: &headers,
            body: None,
        };
        let response = if tls.ssl().selected_alpn_protocol() == Some(b"h2") {
            over_http2(tls, &request).await.ok()?
        } else {
            over_http1(tls, &request).await.ok()?
        };
        (response.status == 200).then(|| doh_answers(&response.body, rtype))
    };
    tokio::time::timeout(Duration::from_secs(3), query)
        .await
        .ok()
        .flatten()
        .unwrap_or_default()
}

async fn doh_lookup(host: String) -> Vec<IpAddr> {
    let servers = [("1.1.1.1", "/dns-query"), ("1.0.0.1", "/dns-query"), ("8.8.8.8", "/resolve")];
    let mut queries = Vec::new();
    for (server, path) in servers {
        for (kind, rtype) in [("A", 1u64), ("AAAA", 28u64)] {
            queries.push(doh_query(server, path, host.clone(), kind, rtype));
        }
    }
    let mut out: Vec<IpAddr> = Vec::new();
    for ip in futures::future::join_all(queries).await.into_iter().flatten() {
        if is_cloudflare_ip(ip) && !out.contains(&ip) {
            out.push(ip);
        }
    }
    out
}

fn doh_answers(body: &[u8], rtype: u64) -> Vec<IpAddr> {
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else {
        return Vec::new();
    };
    value
        .get("Answer")
        .and_then(|v| v.as_array())
        .map(|answers| {
            answers
                .iter()
                .filter(|a| a.get("type").and_then(|t| t.as_u64()) == Some(rtype))
                .filter_map(|a| a.get("data").and_then(|d| d.as_str()))
                .filter_map(|d| d.trim().parse::<IpAddr>().ok())
                .collect()
        })
        .unwrap_or_default()
}

enum Event {
    Ready(String, Option<RelayTarget>, LaneTls),
    Failed(String, String),
    Resolved(Vec<IpAddr>),
}

fn spawn_lane<F>(set: &mut JoinSet<Event>, label: String, delay: Duration, handshake: Duration, relay: Option<RelayTarget>, future: F)
where
    F: Future<Output = std::result::Result<LaneTls, String>> + Send + 'static,
{
    set.spawn(async move {
        tokio::time::sleep(delay).await;
        match tokio::time::timeout(handshake, future).await {
            Ok(Ok(tls)) => Event::Ready(label, relay, tls),
            Ok(Err(why)) => Event::Failed(label, why),
            Err(_) => Event::Failed(label, "handshake timed out".to_string()),
        }
    });
}

/// Races the lanes for `request`; only the first finished TLS handshake carries it.
pub(super) async fn race(
    request: &Request<'_>,
    fingerprint: &Fingerprint,
    ech: Option<&mut Vec<u8>>,
    fragment: FragmentConfig,
) -> Result<Response> {
    let oob = oob_enabled();
    let relay = relay_target();
    let methods = methods_from(std::env::var("AETHER_OOB_METHODS").ok().as_deref());
    let lanes = plan(oob, relay.is_some(), &methods, winner().as_deref());
    let handshake = Duration::from_millis(handshake_ms(
        std::env::var("AETHER_API_LANE_HANDSHAKE_MS").ok().as_deref(),
    ));
    let latest = lanes.iter().map(|lane| lane.delay_ms).max().unwrap_or(0);
    let (address, port) = request.address.unwrap_or((request.host, request.port));
    let sni = request.sni.unwrap_or(request.host).to_string();
    let ech = ech.as_deref().cloned();

    let mut set: JoinSet<Event> = JoinSet::new();
    let mut relay_tried: Vec<SocketAddr> = Vec::new();
    for lane in &lanes {
        let delay = Duration::from_millis(lane.delay_ms);
        match &lane.kind {
            LaneKind::Direct => spawn_lane(
                &mut set,
                lane.label.clone(),
                delay,
                handshake,
                None,
                direct_handshake(sni.clone(), address.to_string(), port, fingerprint.clone(), ech.clone(), fragment),
            ),
            LaneKind::Oob { method, tls12, v6 } => {
                let edges: &[&str] = if *v6 { &OOB_EDGES_V6 } else { &OOB_EDGES_V4 };
                let index = methods.iter().position(|m| m == method).unwrap_or(0) + usize::from(!*tls12);
                let Ok(ip) = edges[index % edges.len()].parse::<IpAddr>() else { continue };
                spawn_lane(
                    &mut set,
                    lane.label.clone(),
                    delay,
                    handshake,
                    None,
                    oob_handshake(SocketAddr::new(ip, 443), *method, *tls12),
                );
            }
            LaneKind::Relay { edge } => {
                let (Some(target), Ok(ip)) = (relay.clone(), RELAY_EDGES[*edge].parse::<IpAddr>()) else { continue };
                let address = SocketAddr::new(ip, 443);
                relay_tried.push(address);
                spawn_lane(
                    &mut set,
                    lane.label.clone(),
                    delay,
                    handshake,
                    Some(target.clone()),
                    relay_handshake(target.host.clone(), address, fingerprint.clone()),
                );
            }
        }
    }
    if let Some(target) = relay.clone() {
        let host = target.host.clone();
        set.spawn(async move { Event::Resolved(doh_lookup(host).await) });
    }

    let deadline = tokio::time::sleep(Duration::from_millis(latest) + handshake + Duration::from_millis(500));
    tokio::pin!(deadline);
    let mut failures: Vec<String> = Vec::new();
    let mut won: Option<(String, Option<RelayTarget>, LaneTls)> = None;
    loop {
        tokio::select! {
            joined = set.join_next() => match joined {
                None => break,
                Some(Ok(Event::Ready(label, relay, tls))) => {
                    won = Some((label, relay, tls));
                    break;
                }
                Some(Ok(Event::Failed(label, why))) => failures.push(format!("{label}: {why}")),
                Some(Ok(Event::Resolved(ips))) => {
                    if let Some(target) = relay.clone() {
                        for ip in ips {
                            let address = SocketAddr::new(ip, 443);
                            if relay_tried.contains(&address) {
                                continue;
                            }
                            relay_tried.push(address);
                            let label = format!("relay@{}", if ip.is_ipv6() { "v6" } else { "v4" });
                            spawn_lane(&mut set, label, Duration::ZERO, handshake, Some(target.clone()),
                                relay_handshake(target.host.clone(), address, fingerprint.clone()));
                        }
                    }
                }
                Some(Err(_)) => {}
            },
            _ = &mut deadline => break,
        }
    }
    // Losers never wrote a byte of HTTP; drop them now.
    set.abort_all();

    let Some((label, relay_used, tls)) = won else {
        eprintln!("MAXIMUS_AETHER_API_LANE:none");
        let last = failures.last().cloned().unwrap_or_else(|| "no lane answered in time".to_string());
        return Err(AetherError::Api(format!(
            "api lanes: no lane finished a TLS handshake ({} lanes; last {last})",
            lanes.len()
        )));
    };
    if printable_label(&label) {
        eprintln!("MAXIMUS_AETHER_API_LANE:{label}");
    }
    remember_winner(&label);
    let h2 = tls.ssl().selected_alpn_protocol() == Some(b"h2");
    match relay_used {
        Some(target) => relay_send(tls, h2, &target, request).await,
        None if label.starts_with("oob:") => {
            // The OOB lanes speak to the API by the name they were verified on.
            let pinned = Request {
                method: request.method,
                host: OOB_API_HOST,
                port: 443,
                address: None,
                sni: None,
                path: request.path,
                headers: request.headers,
                body: request.body,
            };
            if h2 {
                over_http2(tls, &pinned).await
            } else {
                over_http1(tls, &pinned).await
            }
        }
        None => {
            if h2 {
                over_http2(tls, request).await
            } else {
                over_http1(tls, request).await
            }
        }
    }
}

/// The relay envelope (UAC's): `{"ok":true,"status":200,"body":"..."}` or `{"ok":false,"error":"..."}`.
fn unwrap_envelope(value: &serde_json::Value) -> std::result::Result<(u16, Vec<u8>, Option<u64>), String> {
    if value.get("ok").and_then(|v| v.as_bool()) != Some(true) {
        let reason = value
            .get("error")
            .and_then(|v| v.as_str())
            .unwrap_or("relay refused the call");
        return Err(reason.chars().take(200).collect());
    }
    let status = value
        .get("status")
        .and_then(|v| v.as_u64())
        .filter(|s| (100..=599).contains(s))
        .ok_or_else(|| "relay answer has no API status".to_string())?;
    let body = value
        .get("body")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .as_bytes()
        .to_vec();
    let retry = value.get("retry_after").and_then(|v| v.as_u64());
    Ok((status as u16, body, retry))
}

async fn relay_send(tls: LaneTls, h2: bool, target: &RelayTarget, request: &Request<'_>) -> Result<Response> {
    let mut headers = serde_json::Map::new();
    for (name, value) in request.headers.iter() {
        headers.insert(name.clone(), serde_json::Value::String(value.clone()));
    }
    let body = request.body.map(|b| String::from_utf8_lossy(b).into_owned());
    let payload = serde_json::json!({
        "method": request.method,
        "host": request.host,
        "path": request.path,
        "headers": headers,
        "body": body,
    });
    let bytes = serde_json::to_vec(&payload).map_err(|e| AetherError::Api(format!("relay encode: {e}")))?;
    let wire_headers = vec![
        ("Content-Type".to_string(), "application/json".to_string()),
        ("Accept".to_string(), "application/json".to_string()),
    ];
    let wire = Request {
        method: "POST",
        host: &target.host,
        port: 443,
        address: None,
        sni: None,
        path: &target.path,
        headers: &wire_headers,
        body: Some(&bytes),
    };
    let answer = if h2 {
        over_http2(tls, &wire).await?
    } else {
        over_http1(tls, &wire).await?
    };
    let value: serde_json::Value = serde_json::from_slice(&answer.body).map_err(|_| {
        AetherError::Api(format!("relay answered HTTP {} without its envelope", answer.status))
    })?;
    let (status, body, retry) = unwrap_envelope(&value).map_err(|why| AetherError::Api(format!("relay: {why}")))?;
    let mut headers = http::HeaderMap::new();
    if let Some(seconds) = retry {
        headers.insert(http::header::RETRY_AFTER, http::HeaderValue::from(seconds));
    }
    Ok(Response {
        status,
        headers,
        body,
        protocol: answer.protocol,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_envelope_is_unwrapped_into_the_api_answer() {
        let ok = serde_json::json!({"ok": true, "status": 404, "body": "{}"});
        assert_eq!(unwrap_envelope(&ok).unwrap(), (404, b"{}".to_vec(), None));
        let refused = serde_json::json!({"ok": false, "error": "route not allowed"});
        assert_eq!(unwrap_envelope(&refused).unwrap_err(), "route not allowed");
        let no_status = serde_json::json!({"ok": true, "body": "{}"});
        assert!(unwrap_envelope(&no_status).is_err());
    }

    #[test]
    fn doh_answers_keep_the_asked_type_only() {
        let body = br#"{"Answer":[{"type":5,"data":"x.workers.dev."},{"type":1,"data":"104.21.1.2"},{"type":28,"data":"2606:4700::1"}]}"#;
        assert_eq!(doh_answers(body, 1), vec!["104.21.1.2".parse::<IpAddr>().unwrap()]);
        assert_eq!(doh_answers(body, 28), vec!["2606:4700::1".parse::<IpAddr>().unwrap()]);
        assert!(doh_answers(b"not json", 1).is_empty());
    }
}
