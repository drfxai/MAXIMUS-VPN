"""The free list's selection pipeline: normalized candidates, deterministic scores and a diverse pick.

Everything here is measured or derived from the link itself. The runner that builds the list is a
server outside Iran, so its results are GLOBAL evidence only: a server that carried traffic from the
runner is GLOBAL_VERIFIED, never "works in Iran". Iran evidence comes from phones (see the app's
FreeConfigEvidence); until a config has some, its Iran status is UNKNOWN_IRAN_STATUS.

No AI or outside opinion decides anything here: the score is a fixed formula over measurements, and
every score carries the reasons it is made of.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import statistics
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone

# Lifecycle and status names, shared with the app (vpn/hub/FreeConfigStatus.kt).
GLOBAL_VERIFIED = "GLOBAL_VERIFIED"
GLOBAL_FAILED = "GLOBAL_FAILED"
GLOBAL_UNTESTED = "GLOBAL_UNTESTED"
UNKNOWN_IRAN_STATUS = "UNKNOWN_IRAN_STATUS"
LIFECYCLE_NEW = "NEW"
LIFECYCLE_GLOBAL_VERIFIED = "GLOBAL_VERIFIED"
LIFECYCLE_QUARANTINED = "QUARANTINED"

MAX_PUBLISHED = 30

# Score weights (sum 100). Latency is deliberately small: a stable 250 ms server must beat an
# unstable 80 ms one.
WEIGHTS = {
    "reliability": 30,   # share of real requests that went through
    "stability": 15,     # how steady the latency was across rounds
    "http": 10,          # services the exit reached (global evidence only)
    "security": 15,      # REALITY / TLS with a browser fingerprint
    "latency": 10,       # capped, piecewise
    "iran": 15,          # phone evidence; neutral while unknown
    "history": 5,        # earlier runs of this workflow
}

# Cloudflare's published IPv4 ranges (https://www.cloudflare.com/ips-v4). A config on one of these is
# a CDN config: one filtering change at Cloudflare can take all of them down together.
CLOUDFLARE_V4 = [ipaddress.ip_network(n) for n in (
    "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
    "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
    "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
)]
CDN_HOST_SUFFIXES = (".workers.dev", ".pages.dev", ".cloudfront.net", ".fastly.net", ".azureedge.net",
                     ".vercel.app", ".netlify.app", ".gcore.com")


@dataclass
class Measurement:
    """What the runner saw for one candidate. Every field is a measurement, not an opinion."""
    dns_ok: bool | None = None
    tcp_ok: bool | None = None
    tcp_ms: float | None = None
    resolved_ip: str | None = None
    attempts: int = 0
    successes: int = 0
    samples_ms: list[float] = field(default_factory=list)
    sites: list[str] = field(default_factory=list)
    failure_stage: str | None = None

    @property
    def latency_ms(self) -> float | None:
        return statistics.median(self.samples_ms) if self.samples_ms else None

    @property
    def jitter_ms(self) -> float | None:
        return statistics.pstdev(self.samples_ms) if len(self.samples_ms) >= 2 else None


@dataclass
class Candidate:
    link: str
    fingerprint: str
    protocol: str
    host: str
    port: int
    transport: str
    security: str
    sni: str
    source_id: str
    measurement: Measurement = field(default_factory=Measurement)
    family: str = "domain"          # ipv4 / ipv6 / domain (until resolved)
    cdn: bool = False
    failure_domain: str = ""
    global_status: str = GLOBAL_UNTESTED
    score: float = 0.0
    score_reasons: dict = field(default_factory=dict)


def normalize(link: str, endpoint: dict, fingerprint: str, source_id: str) -> Candidate:
    """One internal format for every protocol; the display name is never part of the identity."""
    scheme = {"hy2": "hysteria2", "wg": "wireguard"}.get(endpoint["scheme"], endpoint["scheme"])
    candidate = Candidate(
        link=link, fingerprint=fingerprint, protocol=scheme, host=endpoint["host"], port=int(endpoint["port"]),
        transport=(endpoint.get("type") or "tcp"), security=(endpoint.get("security") or "none"),
        sni=endpoint.get("sni", ""), source_id=source_id,
    )
    classify(candidate)
    return candidate


def _address(text: str):
    try:
        return ipaddress.ip_address(text.strip("[]"))
    except ValueError:
        return None


def classify(c: Candidate) -> None:
    """Address family, CDN or not, and the failure domain: the group of servers that one filtering
    change is likely to take down together (a CDN, an /16 IPv4 network, a /32 IPv6 block, a domain)."""
    ip = _address(c.measurement.resolved_ip or "") or _address(c.host)
    if ip is not None:
        c.family = "ipv6" if ip.version == 6 else "ipv4"
    host = c.host.lower()
    on_cloudflare = ip is not None and ip.version == 4 and any(ip in net for net in CLOUDFLARE_V4)
    fronted = c.transport in ("ws", "grpc", "httpupgrade", "xhttp", "splithttp", "h2") and c.security == "tls"
    c.cdn = on_cloudflare or host.endswith(CDN_HOST_SUFFIXES)
    if on_cloudflare or host.endswith((".workers.dev", ".pages.dev")):
        c.failure_domain = "cdn:cloudflare"
    elif c.cdn:
        c.failure_domain = "cdn:" + host.rsplit(".", 2)[-2]
    elif ip is not None and ip.version == 4:
        c.failure_domain = "net:" + str(ipaddress.ip_network(f"{ip}/16", strict=False))
    elif ip is not None:
        c.failure_domain = "net:" + str(ipaddress.ip_network(f"{ip}/32", strict=False))
    else:
        labels = host.split(".")
        c.failure_domain = "dom:" + ".".join(labels[-2:])
    if fronted and not c.cdn and c.sni and c.sni != host:
        # TLS to one name, connected to another: almost always a CDN front we could not name.
        c.cdn = True


def kind(c: Candidate) -> str:
    return f"{c.protocol}/{c.transport}/{c.security}"


def global_status(m: Measurement) -> str:
    if m.attempts == 0:
        return GLOBAL_UNTESTED
    return GLOBAL_VERIFIED if m.successes >= max(2, (m.attempts + 1) // 2) else GLOBAL_FAILED


def _latency_component(ms: float | None) -> float:
    """1.0 up to 300 ms, falling linearly to 0 at 2000 ms. Below 300 ms speed earns nothing more."""
    if ms is None:
        return 0.0
    if ms <= 300:
        return 1.0
    return max(0.0, 1.0 - (ms - 300) / 1700)


def _security_component(c: Candidate) -> float:
    if c.security == "reality":
        return 1.0
    if c.security == "tls":
        return 0.85
    if c.protocol == "shadowsocks":
        return 0.6   # AEAD only (see aggregate.problem), but no certificate to check
    if c.protocol in ("hysteria2", "wireguard"):
        return 0.8
    return 0.0


def score(c: Candidate, history: dict | None = None, iran: dict | None = None) -> float:
    """OverallScore 0..100 from measurements only, with the reason for every part."""
    m = c.measurement
    reliability = (m.successes / m.attempts) if m.attempts else 0.0
    latency = m.latency_ms
    jitter = m.jitter_ms
    if latency is None:
        stability = 0.0
    elif jitter is None:
        stability = 0.5  # one sample: no evidence of steadiness either way
    else:
        stability = max(0.0, 1.0 - jitter / max(latency, 50.0))
    http = len(set(m.sites)) / 3.0
    sec = _security_component(c)
    lat = _latency_component(latency)
    # Iran evidence: phones' reports, when any reach the pipeline; neutral (0.5) while unknown so an
    # unknown server is neither rewarded nor punished for it.
    iran_component = 0.5
    iran_reason = "no phone evidence yet (UNKNOWN_IRAN_STATUS)"
    if iran and iran.get("attempts", 0) > 0:
        iran_component = iran.get("successes", 0) / iran["attempts"]
        iran_reason = f"{iran.get('successes', 0)}/{iran['attempts']} phone connections succeeded"
    hist_component = 0.5
    hist_reason = "first time seen"
    if history:
        seen, passed = history.get("seen", 0), history.get("passed", 0)
        if seen:
            hist_component = passed / seen
            hist_reason = f"passed {passed} of {seen} earlier runs"
    parts = {
        "reliability": (reliability, f"{m.successes}/{m.attempts} proxied requests succeeded"),
        "stability": (stability, "single sample" if jitter is None else f"jitter {jitter:.0f} ms over {len(m.samples_ms)} rounds"),
        "http": (http, f"exit reached {len(set(m.sites))}/3 test services (global, not Iran)"),
        "security": (sec, f"{c.security or 'none'} over {c.transport}"),
        "latency": (lat, "no latency" if latency is None else f"median {latency:.0f} ms from the runner"),
        "iran": (iran_component, iran_reason),
        "history": (hist_component, hist_reason),
    }
    total = sum(WEIGHTS[k] * v for k, (v, _) in parts.items())
    c.score = round(total, 2)
    c.score_reasons = {k: {"value": round(v, 3), "weight": WEIGHTS[k], "points": round(WEIGHTS[k] * v, 2), "why": why}
                       for k, (v, why) in parts.items()}
    return c.score


# Diversity: how many published servers may share one failure domain or one kind, and how much of the
# list one side of CDN / non-CDN may take while the other side has candidates.
MAX_PER_DOMAIN = 3
MAX_PER_KIND = 10
MAX_PER_SOURCE = 12
MAX_CDN_SHARE = 0.7


def select_diverse(candidates: list[Candidate], limit: int = MAX_PUBLISHED) -> list[Candidate]:
    """Best score first, but never 30 servers that one filtering change could take down together.

    Greedy over the score order with hard caps per failure domain, kind (protocol/transport/security)
    and source, and a cap on the CDN or non-CDN share while the other side has candidates. When the
    caps leave the list short they are relaxed in steps (kind and source first, then the CDN share,
    then the failure domain), because a short list helps nobody.
    Ties break on the fingerprint, so the result is deterministic.
    """
    ranked = sorted((c for c in candidates if c.global_status == GLOBAL_VERIFIED),
                    key=lambda c: (-c.score, c.fingerprint))
    has_cdn = any(c.cdn for c in ranked)
    has_direct = any(not c.cdn for c in ranked)
    picked: list[Candidate] = []
    domains: dict[str, int] = {}
    kinds: dict[str, int] = {}
    sources: dict[str, int] = {}

    def fits(c: Candidate, level: int = 0) -> bool:
        """[level] relaxes the caps in steps: 0 all, 1 without kind and source caps, 2 domain cap only."""
        if domains.get(c.failure_domain, 0) >= MAX_PER_DOMAIN:
            return False
        if level < 1 and kinds.get(kind(c), 0) >= MAX_PER_KIND:
            return False
        if level < 1 and sources.get(c.source_id, 0) >= MAX_PER_SOURCE:
            return False
        if level < 2:
            same_side = sum(1 for p in picked if p.cdn == c.cdn)
            other_side_exists = has_direct if c.cdn else has_cdn
            if other_side_exists and same_side + 1 > MAX_CDN_SHARE * limit:
                return False
        return True

    def take(c: Candidate) -> None:
        picked.append(c)
        domains[c.failure_domain] = domains.get(c.failure_domain, 0) + 1
        kinds[kind(c)] = kinds.get(kind(c), 0) + 1
        sources[c.source_id] = sources.get(c.source_id, 0) + 1

    # One of each address family first when both exist, so an IPv4-only or IPv6-only failure
    # cannot empty the list.
    for family in ("ipv6", "ipv4"):
        first = next((c for c in ranked if c.family == family and c not in picked), None)
        if first is not None and len(picked) < limit and fits(first):
            take(first)
    for level in (0, 1, 2):
        for c in ranked:
            if len(picked) >= limit:
                break
            if c not in picked and fits(c, level):
                take(c)
    for c in ranked:
        if len(picked) >= limit:
            break
        if c not in picked:
            take(c)
    return sorted(picked, key=lambda c: (-c.score, c.fingerprint))


def diversity_report(picked: list[Candidate]) -> dict:
    def count(key):
        out: dict[str, int] = {}
        for c in picked:
            k = key(c)
            out[k] = out.get(k, 0) + 1
        return dict(sorted(out.items()))
    return {
        "kinds": count(kind),
        "families": count(lambda c: c.family),
        "cdn": count(lambda c: "cdn" if c.cdn else "direct"),
        "failure_domains": len({c.failure_domain for c in picked}),
        "largest_failure_domain": max(count(lambda c: c.failure_domain).values(), default=0),
        "sources": count(lambda c: c.source_id),
    }


def update_history(history: dict, tested: list[Candidate], now: str) -> dict:
    """Per-fingerprint pass record across runs, bounded to the 2000 most recently seen."""
    out = dict(history)
    for c in tested:
        if c.measurement.attempts == 0:
            continue
        h = dict(out.get(c.fingerprint, {"seen": 0, "passed": 0}))
        h["seen"] = h.get("seen", 0) + 1
        if c.global_status == GLOBAL_VERIFIED:
            h["passed"] = h.get("passed", 0) + 1
            h["last_pass"] = now
        h["last_seen"] = now
        out[c.fingerprint] = h
    if len(out) > 2000:
        keep = sorted(out.items(), key=lambda kv: kv[1].get("last_seen", ""), reverse=True)[:2000]
        out = dict(keep)
    return out


def published_record(c: Candidate, name: str, verified_at: str) -> dict:
    """What the app may read about a published config. No credential, path or key is included."""
    m = c.measurement
    return {
        "name": name,
        "fingerprint": c.fingerprint,
        "protocol": c.protocol,
        "transport": c.transport,
        "security": c.security,
        "family": c.family,
        "cdn": c.cdn,
        "failure_domain": hashlib.sha256(c.failure_domain.encode()).hexdigest()[:12],
        "source_id": c.source_id,
        "global_status": c.global_status,
        "iran_status": UNKNOWN_IRAN_STATUS,
        "lifecycle": LIFECYCLE_GLOBAL_VERIFIED,
        "global_reach": sorted(set(m.sites)),
        "latency_ms": None if m.latency_ms is None else round(m.latency_ms),
        "jitter_ms": None if m.jitter_ms is None else round(m.jitter_ms),
        "rounds": {"ok": m.successes, "attempts": m.attempts},
        "score": c.score,
        "score_reasons": c.score_reasons,
        "verified_at": verified_at,
    }


def now_iso(now: datetime | None = None) -> str:
    return (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")


def dumps(data) -> str:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def as_dict(c: Candidate) -> dict:
    d = asdict(c)
    d.pop("link", None)
    return d
