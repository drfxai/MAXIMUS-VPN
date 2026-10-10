#!/usr/bin/env python3
"""Builds and signs the app's free configuration list.

Reads the sources in sources/sources.json, keeps only entries the app can use safely, removes
duplicates, and writes output/free.txt, output/free-base64.txt and a manifest with each file's
SHA-256. With HUB_SIGNING_KEY set (an ECDSA P-256 private key in PEM form, from the repository's
secrets) the manifest is signed; without it the manifest is written unsigned, for a dry run.
"""
from __future__ import annotations

import base64
import hashlib
import socket
from concurrent.futures import ThreadPoolExecutor
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, parse_qs, quote, unquote

ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 5 * 1024 * 1024
TIMEOUT_SEC = 30
MAX_PER_SOURCE = 500
MAX_TOTAL = 30
# Candidates per source that get a real request; the rest of a large source is not tried.
VERIFY_PER_SOURCE = 150
ALIVE_TIMEOUT_SEC = 3
ALIVE_WORKERS = 64
SCHEMES = ("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "wireguard://", "wg://")
ENCRYPTED_SECURITY = ("tls", "reality")
UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def fetch(url: str) -> str:
    if not url.startswith("https://"):
        raise ValueError("sources must be https")
    request = urllib.request.Request(url, headers={"User-Agent": "maximus-aggregator"})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SEC) as response:
        return response.read(MAX_BYTES + 1).decode("utf-8", "replace")


def decode_maybe_base64(text: str) -> str:
    stripped = "".join(text.split())
    if stripped and all(c.isalnum() or c in "+/=-_" for c in stripped):
        try:
            return base64.b64decode(stripped + "=" * (-len(stripped) % 4), altchars=b"-_").decode("utf-8", "replace")
        except Exception:
            return text
    return text


def endpoint(link: str) -> dict:
    """Host, port, credential and transport fields of a link; raises ValueError for a malformed one."""
    if link.startswith("vmess://"):
        body = link[len("vmess://"):].split("#", 1)[0]
        data = json.loads(base64.b64decode(body + "=" * (-len(body) % 4), altchars=b"-_").decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("vmess body is not an object")
        port = int(str(data.get("port", "")).strip())
        tls = str(data.get("tls", "")).lower()
        return {
            "scheme": "vmess", "host": str(data.get("add", "")).strip().lower(), "port": port,
            "credential": str(data.get("id", "")), "type": str(data.get("net", "")).lower(),
            "security": tls, "sni": str(data.get("sni", "") or data.get("host", "")).lower(),
            "path": str(data.get("path", "")), "service": "", "pbk": "",
            "insecure": str(data.get("allowInsecure", data.get("skip-cert-verify", ""))).lower() in ("1", "true"),
            "fp": str(data.get("fp", "")).lower(),
        }
    parts = urlsplit(link)
    port = parts.port  # raises ValueError for a port that is not a number
    query = parse_qs(parts.query)
    first = lambda name: (query.get(name, [""])[0] or "")
    return {
        "scheme": parts.scheme.lower(), "host": (parts.hostname or "").lower(), "port": port or 0,
        "credential": unquote(parts.username or ""), "type": first("type").lower(),
        "security": first("security").lower(), "sni": first("sni").lower(), "path": first("path"),
        "service": first("serviceName"), "pbk": first("pbk"), "encryption": first("encryption").lower(),
        # "host:443/?..." puts a slash after the port, which the app (like Xray's own parser) refuses.
        "path_in_authority": parts.path not in ("",),
        "fp": first("fp").lower(),
        # A pinned-certificate hash with allowInsecure is checked above; "pcs" alone keeps checks on.
        "pcs_bypass": first("verify").lower() in ("0", "false"),
        "method": _ss_method(parts) if parts.scheme.lower() == "ss" else "",
        "insecure": any(v and v[0].lower() in ("1", "true") for k, v in query.items()
                        if k.lower() in ("allowinsecure", "insecure", "skip-cert-verify")),
    }


def _ss_method(parts) -> str:
    userinfo = unquote(parts.username or "")
    if ":" not in userinfo and userinfo:
        try:
            userinfo = base64.b64decode(userinfo + "=" * (-len(userinfo) % 4), altchars=b"-_").decode("utf-8", "replace")
        except Exception:
            return ""
    return userinfo.split(":", 1)[0]


def rename(link: str, name: str) -> str:
    """The link with its display name replaced; source names often carry ads or channel handles."""
    if link.startswith("vmess://"):
        body = link[len("vmess://"):].split("#", 1)[0]
        data = json.loads(base64.b64decode(body + "=" * (-len(body) % 4), altchars=b"-_").decode("utf-8"))
        data["ps"] = name
        return "vmess://" + base64.b64encode(json.dumps(data, separators=(",", ":"), ensure_ascii=False).encode()).decode()
    return link.split("#", 1)[0] + "#" + quote(name)


def problem(link: str) -> str | None:
    """Why this link must not be published, or None. The display name after # is not checked: it is replaced."""
    if not link.startswith(SCHEMES):
        return "unknown scheme"
    if len(link) > 2048 and not link.startswith("vmess://"):
        return "too long"
    if len(link) > 4096:
        return "too long"
    body = link if link.startswith("vmess://") else link.split("#", 1)[0]
    if any(c.isspace() for c in body.strip()) or any(ord(c) < 32 or ord(c) == 127 for c in link):
        return "control characters"
    try:
        e = endpoint(link)
    except Exception:
        return "malformed"
    host = e["host"]
    if not host or host.endswith(".local") or host in ("localhost", "metadata.google.internal"):
        return "local host"
    if not 1 <= e["port"] <= 65535:
        return "malformed"
    if host.replace(".", "").isdigit():
        octets = [int(o) for o in host.split(".") if o.isdigit()]
        if len(octets) == 4 and (
            octets[0] in (0, 10, 127) or octets[0] >= 224
            or (octets[0] == 100 and 64 <= octets[1] <= 127)
            or (octets[0] == 169 and octets[1] == 254)
            or (octets[0] == 172 and 16 <= octets[1] <= 31)
            or (octets[0] == 192 and octets[1] == 168)
            or (octets[0] == 198 and octets[1] in (18, 19))
        ):
            return "private or reserved address"
    if e["insecure"]:
        return "certificate checks disabled"
    if e["scheme"] in ("vless", "trojan"):
        if e["security"] not in ENCRYPTED_SECURITY and (e["scheme"] == "trojan" or e.get("encryption", "") in ("", "none")):
            return "no encryption"
        if e["security"] == "reality" and (not e["pbk"] or not e["sni"]):
            return "incomplete reality settings"
        if e.get("path_in_authority"):
            return "malformed"
    if e["scheme"] == "vless" and not UUID.match(e["credential"]):
        return "malformed"
    if e["scheme"] == "vmess" and not UUID.match(e["credential"]):
        return "invalid credentials"
    if e["scheme"] == "trojan" and not e["credential"]:
        return "malformed"
    if e["scheme"] == "vmess" and e["security"] not in ENCRYPTED_SECURITY:
        return "no encryption"
    if e["type"] and e["type"] not in TRANSPORTS:
        return "unsupported transport"
    if e.get("fp") == "unsafe" or e.get("pcs_bypass"):
        return "insecure TLS settings"
    if e["scheme"] == "ss":
        method = e.get("method", "")
        if method and method.lower() not in SS_AEAD:
            return "weak cipher"
    if _hidden_address(host):
        return "obfuscated address"
    if _private_v6(host):
        return "private or reserved address"
    return None


# Transports the app runs; anything else is refused rather than half-supported.
TRANSPORTS = {"tcp", "ws", "grpc", "httpupgrade", "xhttp", "splithttp", "h2", "http", "raw", "none"}
# Shadowsocks ciphers with authentication (AEAD and 2022); stream ciphers are forgeable.
SS_AEAD = {"aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "chacha20-poly1305", "xchacha20-ietf-poly1305",
           "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"}


def _hidden_address(host: str) -> bool:
    """Integer, hex or octal IPv4 forms ("2130706433", "0x7f.1") hide a private address from a reader."""
    if host.isdigit():
        return True
    parts = host.split(".")
    return any(p.lower().startswith("0x") for p in parts) or (
        len(parts) == 4 and all(p.isdigit() for p in parts) and any(len(p) > 1 and p.startswith("0") for p in parts))


def _private_v6(host: str) -> bool:
    import ipaddress
    try:
        ip = ipaddress.ip_address(host.strip("[]"))
    except ValueError:
        return False
    if ip.version != 6:
        return False
    mapped = ip.ipv4_mapped
    if mapped is not None:
        return not mapped.is_global
    return not ip.is_global


def fingerprint(link: str) -> str:
    """Canonical identity: scheme, host, port, transport, security, SNI, path, and the credential hashed."""
    e = endpoint(link)
    credential = hashlib.sha256(e["credential"].encode()).hexdigest()[:16] if e["credential"] else ""
    fields = [e["scheme"], e["host"], str(e["port"] or ""), credential, e["type"], e["security"],
              e["sni"], e["path"], e["service"], e["pbk"]]
    return hashlib.sha256("|".join(fields).encode()).hexdigest()


def reach(link: str, timeout: float = ALIVE_TIMEOUT_SEC) -> dict:
    """DNS and TCP stages from the runner: {dns_ok, tcp_ok, tcp_ms, ip, stage}. UDP protocols get no TCP
    test (it says nothing about them) and count as reachable."""
    import time
    e = endpoint(link)
    if e["scheme"] in ("hysteria2", "hy2", "wireguard", "wg"):
        return {"dns_ok": None, "tcp_ok": True, "tcp_ms": None, "ip": None, "stage": None}
    try:
        infos = socket.getaddrinfo(e["host"], e["port"], type=socket.SOCK_STREAM)
    except OSError:
        return {"dns_ok": False, "tcp_ok": False, "tcp_ms": None, "ip": None, "stage": "DNS_RESOLUTION_FAILED"}
    family, _, _, _, address = infos[0]
    started = time.monotonic()
    try:
        with socket.socket(family, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            sock.connect(address)
        return {"dns_ok": True, "tcp_ok": True, "tcp_ms": round((time.monotonic() - started) * 1000, 1),
                "ip": address[0], "stage": None}
    except OSError:
        return {"dns_ok": True, "tcp_ok": False, "tcp_ms": None, "ip": address[0], "stage": "TCP_CONNECT_FAILED"}


def alive(link: str, timeout: float = ALIVE_TIMEOUT_SEC) -> bool:
    """True when the server accepts a TCP connection (UDP protocols are kept: TCP says nothing about them)."""
    return bool(reach(link, timeout)["tcp_ok"])


def keep_alive(links: list[str], report: dict, check=alive, stages: dict | None = None) -> list[str]:
    """Drops servers that do not answer from here; a server that answers may still be blocked elsewhere.
    [check] returns a bool or a [reach] dict; dicts are stored in [stages] by link."""
    with ThreadPoolExecutor(max_workers=ALIVE_WORKERS) as pool:
        results = list(pool.map(lambda link: _safe(check, link), links))
    if stages is not None:
        for link, r in zip(links, results):
            if isinstance(r, dict):
                stages[link] = r
    kept = [link for link, ok in zip(links, results) if (ok.get("tcp_ok") if isinstance(ok, dict) else ok)]
    if len(kept) < len(links):
        report["rejected"]["not answering"] = report["rejected"].get("not answering", 0) + len(links) - len(kept)
    return kept


def _safe(check, link: str):
    try:
        return check(link)
    except Exception:
        return False


def interleave(per_source: list[list[str]], limit: int = MAX_TOTAL) -> list[str]:
    """Takes from each source in turn, so one large source cannot fill the whole list."""
    out: list[str] = []
    index = 0
    while len(out) < limit and any(index < len(links) for links in per_source):
        for links in per_source:
            if index < len(links) and len(out) < limit:
                out.append(links[index])
        index += 1
    return out


def keep_working(per_source: list[list[str]], verify, report: dict, reach: dict | None = None) -> list[list[str]]:
    """Keeps the servers that carried a real request and opened at least one of YouTube, Telegram or X,
    each source's best first: most of those sites reached, then fastest. [verify] maps a list of links to
    {link: (seconds, sites reached)} for the ones that worked; [reach] receives the sites of every kept link."""
    candidates = [links[:VERIFY_PER_SOURCE] for links in per_source]
    found = verify([link for links in candidates for link in links])
    tried = sum(len(links) for links in candidates)
    carried = [[l for l in links if l in found] for links in candidates]
    works = [sorted((l for l in links if found[l][1]), key=lambda l: (-len(found[l][1]), found[l][0])) for links in carried]
    report["rejected"]["no traffic"] = tried - sum(len(c) for c in carried)
    blocked = sum(len(c) for c in carried) - sum(len(w) for w in works)
    if blocked:
        report["rejected"]["no YouTube, Telegram or X"] = blocked
    if reach is not None:
        for links in works:
            for link in links:
                reach[link] = found[link]
    return works


def _source_id(source: dict) -> str:
    raw = source.get("id") or source.get("name") or source.get("url", "?")
    return re.sub(r"[^a-z0-9_-]+", "-", str(raw).lower()).strip("-") or "source"


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def collect(sources: list[dict], fetcher=fetch, check=None, verify=None, reach: dict | None = None,
            state: dict | None = None) -> tuple[list[str], dict]:
    """Fetch → validate → normalize → security-check → de-duplicate → (TCP) → real requests → score →
    pick at most 30, diverse. [state] receives the pipeline's records: "candidates" (fingerprint →
    pipeline.Candidate), "quarantine" (rejected entries with the reason, no credentials) and reads
    "history" (earlier runs, by fingerprint) and "iran" (phone evidence, by fingerprint) when present."""
    import pipeline
    state = {} if state is None else state
    history = state.get("history") or {}
    iran = state.get("iran") or {}
    candidates: dict[str, "pipeline.Candidate"] = state.setdefault("candidates", {})
    quarantine: list[dict] = state.setdefault("quarantine", [])
    per_source: list[list[str]] = []
    names: list[str] = []
    seen: set[str] = set()
    report = {"sources": {}, "rejected": {}, "source_meta": []}
    stages: dict[str, dict] = {}
    for source in sources:
        name = source.get("name") or source.get("url", "?")
        sid = _source_id(source)
        meta = {"source_id": sid, "source_name": name, "source_url": source.get("url", ""),
                "last_fetch_time": _now_iso(), "fetch_status": "ok", "candidate_count": 0, "valid_count": 0}
        report["source_meta"].append(meta)
        try:
            text = decode_maybe_base64(fetcher(source["url"]))
        except Exception as error:  # a source that is down must not stop the others
            report["sources"][name] = f"not fetched: {type(error).__name__}"
            meta["fetch_status"] = f"failed: {type(error).__name__}"
            continue
        links: list[str] = []
        for raw in text.splitlines():
            link = raw.strip()
            if not link or link.startswith("#"):
                continue
            meta["candidate_count"] += 1
            reason = problem(link)
            if reason:
                report["rejected"][reason] = report["rejected"].get(reason, 0) + 1
                if len(quarantine) < 5000:
                    quarantine.append(_quarantined(link, reason, sid))
                continue
            key = fingerprint(link)
            if key in seen:
                report["rejected"]["duplicate"] = report["rejected"].get("duplicate", 0) + 1
                continue
            seen.add(key)
            links.append(link)
            candidates[link] = pipeline.normalize(link, endpoint(link), key, sid)
            if len(links) >= MAX_PER_SOURCE:
                break
        if check is not None:
            links = keep_alive(links, report, check, stages)
        meta["valid_count"] = len(links)
        report["sources"][name] = f"{len(links)} kept"
        per_source.append(links)
        names.append(name)
    for link, stage in stages.items():
        c = candidates.get(link)
        if c is None:
            continue
        c.measurement.dns_ok = stage.get("dns_ok")
        c.measurement.tcp_ok = stage.get("tcp_ok")
        c.measurement.tcp_ms = stage.get("tcp_ms")
        c.measurement.resolved_ip = stage.get("ip")
        c.measurement.failure_stage = stage.get("stage")
        pipeline.classify(c)
    if verify is None:
        return interleave(per_source), report

    reach = {} if reach is None else reach
    tried = [links[:VERIFY_PER_SOURCE] for links in per_source]
    found = verify([link for links in tried for link in links])
    verified: list = []
    blocked = no_traffic = 0
    for links in tried:
        for link in links:
            c = candidates[link]
            r = found.get(link)
            if r is None:
                c.measurement.attempts = max(c.measurement.attempts, 1)
                c.measurement.failure_stage = c.measurement.failure_stage or "PROXY_HANDSHAKE_FAILED"
                c.global_status = pipeline.GLOBAL_FAILED
                no_traffic += 1
                continue
            samples = getattr(r, "samples", None) or (r[0], r[0])
            c.measurement.samples_ms = [round(x * 1000, 1) for x in samples]
            c.measurement.successes = len(samples)
            c.measurement.attempts = getattr(r, "attempts", 0) or len(samples)
            c.measurement.sites = sorted(r[1])
            c.global_status = pipeline.global_status(c.measurement)
            if not r[1]:
                # The exit reached none of the test services: no use to anyone, wherever they are.
                blocked += 1
                continue
            if c.global_status != pipeline.GLOBAL_VERIFIED:
                no_traffic += 1
                continue
            pipeline.score(c, history.get(c.fingerprint), iran.get(c.fingerprint))
            verified.append(c)
            reach[link] = r
    report["rejected"]["no traffic"] = no_traffic
    if blocked:
        report["rejected"]["no YouTube, Telegram or X"] = blocked
    picked = pipeline.select_diverse(verified, MAX_TOTAL)
    by_source: dict[str, int] = {}
    for c in verified:
        by_source[c.source_id] = by_source.get(c.source_id, 0) + 1
    for name, meta in zip(names, [m for m in report["source_meta"] if not m["fetch_status"].startswith("failed")]):
        report["sources"][name] = f"{by_source.get(meta['source_id'], 0)} carried traffic"
    report["diversity"] = pipeline.diversity_report(picked)
    state["tested"] = [candidates[l] for links in tried for l in links]
    state["picked"] = picked
    return [c.link for c in picked], report


def _quarantined(link: str, reason: str, source_id: str) -> dict:
    """A rejected entry as it may be stored: where it came from and why, never its credentials."""
    scheme = link.split("://", 1)[0].lower()[:16]
    try:
        e = endpoint(link)
        where = f"{e['host']}:{e['port']}"
        key = fingerprint(link)[:16]
    except Exception:
        where, key = "unparsable", hashlib.sha256(link.encode()).hexdigest()[:16]
    return {"source_id": source_id, "scheme": scheme, "endpoint": where[:80], "fingerprint": key, "reason": reason,
            "status": "QUARANTINED"}


def sign(payload: bytes, pem: str) -> str:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = serialization.load_pem_private_key(pem.encode(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise ValueError("HUB_SIGNING_KEY must be an ECDSA P-256 key")
    return base64.b64encode(key.sign(payload, ec.ECDSA(hashes.SHA256()))).decode()


SITE_ORDER = ("YT", "TG", "X")


def display_name(link: str, index: int, locate=None, sites=()) -> str:
    """"DE · VLESS 12" when the server's country is known, else "Free VLESS 12", followed by the sites it
    opened ("DE · VLESS 12 · YT TG X"); the app reads the code and the tags."""
    scheme = endpoint(link)["scheme"].upper()
    country = None
    if locate is not None:
        try:
            country = locate(endpoint(link)["host"])
        except Exception:
            country = None
    name = f"{country} · {scheme} {index}" if country else f"Free {scheme} {index}"
    tags = [tag for tag in SITE_ORDER if tag in set(sites or ())]
    return f"{name} · {' '.join(tags)}" if tags else name


class Geo:
    """Country of an IPv4 address from a CSV of numeric ranges (start,end,CC), such as the CC0
    geo-whois-asn-country list. Countries are where the address is registered, which is close to where
    the server is for most hosting providers."""

    def __init__(self, path: Path):
        import bisect
        self._bisect = bisect
        self.starts: list[int] = []
        self.rows: list[tuple[int, str]] = []
        with open(path) as handle:
            for line in handle:
                parts = line.strip().split(",")
                if len(parts) != 3 or not parts[0].isdigit():
                    continue
                self.starts.append(int(parts[0]))
                self.rows.append((int(parts[1]), parts[2].upper()))

    def country_of_ip(self, ip: str) -> str | None:
        octets = ip.split(".")
        if len(octets) != 4 or not all(o.isdigit() and int(o) < 256 for o in octets):
            return None
        number = int.from_bytes(bytes(int(o) for o in octets), "big")
        i = self._bisect.bisect_right(self.starts, number) - 1
        if i < 0 or number > self.rows[i][0]:
            return None
        code = self.rows[i][1]
        return code if len(code) == 2 and code.isalpha() and code != "ZZ" else None

    def __call__(self, host: str) -> str | None:
        ip = host
        if not all(part.isdigit() for part in host.split(".")):
            try:
                ip = socket.getaddrinfo(host, None, socket.AF_INET)[0][4][0]
            except OSError:
                return None
        return self.country_of_ip(ip)


def build(output_dir: Path, sources_file: Path, fetcher=fetch, now=None, key_pem: str | None = None, check=None, locate=None,
          verify=None, history: dict | None = None, iran: dict | None = None) -> dict:
    import pipeline
    config = json.loads(sources_file.read_text())
    reach: dict = {}
    state: dict = {"history": history or {}, "iran": iran or {}}
    links, report = collect(config.get("sources", []), fetcher, check, verify, reach, state)
    names = [display_name(link, i + 1, locate, reach.get(link, (0, ()))[1]) for i, link in enumerate(links)]
    created = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    output_dir.mkdir(parents=True, exist_ok=True)
    plain = ("\n".join(rename(link, name) for link, name in zip(links, names)) + "\n") if links else ""
    (output_dir / "free.txt").write_text(plain)
    (output_dir / "free-base64.txt").write_text(base64.b64encode(plain.encode()).decode())
    # Per-config evidence the app and the admin panel can inspect: statuses, score and its reasons.
    by_link = {c.link: c for c in state.get("picked", [])}
    records = [pipeline.published_record(by_link[link], name, created) for link, name in zip(links, names) if link in by_link]
    (output_dir / "configs.json").write_text(pipeline.dumps({"version": 1, "created": created, "configs": records}))
    updated = pipeline.update_history(state["history"], state.get("tested", []), created)
    (output_dir / "history.json").write_text(pipeline.dumps(updated))
    # Rejected entries stay with the run (a workflow artifact), not in the published list.
    (output_dir / "quarantine.json").write_text(pipeline.dumps(state.get("quarantine", [])))
    # Probe targets for the app's multi-target verification, signed with the list (sources/probes.json).
    probes = sources_file.parent / "probes.json"
    names = ["free.txt", "free-base64.txt", "configs.json", "history.json"]
    if probes.exists():
        body = json.loads(probes.read_text())
        body["createdAt"] = int((now or datetime.now(timezone.utc)).timestamp() * 1000)
        (output_dir / "probes.json").write_text(json.dumps(body, sort_keys=True, separators=(",", ":")))
        names.append("probes.json")
    files = {}
    for name in names:
        data = (output_dir / name).read_bytes()
        files[name] = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    manifest = {
        "version": 1,
        "created": created,
        "count": len(links),
        "files": files,
        "report": report,
        # What the runner's checks mean: they ran outside Iran.
        "evidence": {"global": "tested from a GitHub Actions runner", "iran": pipeline.UNKNOWN_IRAN_STATUS},
    }
    payload = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    (output_dir / "manifest.json").write_bytes(payload)
    signature = ""
    if key_pem:
        signature = sign(payload, key_pem)
    (output_dir / "manifest.sig").write_text(signature)
    return manifest


def _load_json(path: str | None) -> dict:
    if not path:
        return {}
    try:
        data = json.loads(Path(path).read_text())
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def main() -> int:
    # AGGREGATOR_CHECK_ALIVE=0 skips the TCP check (for runs without outbound access).
    check = None if os.environ.get("AGGREGATOR_CHECK_ALIVE", "1") == "0" else reach
    # AGGREGATOR_GEO names an IPv4 country CSV (start,end,CC); without it names carry no country.
    geo_path = os.environ.get("AGGREGATOR_GEO")
    locate = Geo(Path(geo_path)) if geo_path else None
    # Every published server must have carried a real request through a local Xray core and opened YouTube,
    # Telegram or X through it. AGGREGATOR_VERIFY=0
    # skips that for a dry run, and then nothing may be published.
    verify = None
    if os.environ.get("AGGREGATOR_VERIFY", "1") != "0":
        xray = os.environ.get("AGGREGATOR_XRAY", "")
        if not xray or not os.access(xray, os.X_OK):
            print("AGGREGATOR_XRAY must name the Xray core, which tests each server with a real request", file=sys.stderr)
            return 2
        import verify as probe
        verify = lambda links: probe.probe(links, xray)
    # The previous run's history.json (from the free-configs branch) scores how often a server passed before.
    history = _load_json(os.environ.get("AGGREGATOR_HISTORY"))
    manifest = build(ROOT / "output", ROOT / "sources" / "sources.json",
                     key_pem=os.environ.get("HUB_SIGNING_KEY") or None, check=check, locate=locate, verify=verify,
                     history=history)
    print(f"{manifest['count']} configs; manifest {'signed' if (ROOT / 'output' / 'manifest.sig').read_text() else 'UNSIGNED (no key)'}")
    for name, state in manifest["report"]["sources"].items():
        print(f"  {name}: {state}")
    if manifest["report"].get("diversity"):
        print("  diversity: " + json.dumps(manifest["report"]["diversity"], sort_keys=True))
    if manifest["report"]["rejected"]:
        print("  rejected: " + ", ".join(f"{k}={v}" for k, v in sorted(manifest["report"]["rejected"].items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
