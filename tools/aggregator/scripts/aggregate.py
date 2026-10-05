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
MAX_TOTAL = 300
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
        "insecure": any(v and v[0].lower() in ("1", "true") for k, v in query.items()
                        if k.lower() in ("allowinsecure", "insecure", "skip-cert-verify")),
    }


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
    if e["scheme"] == "trojan" and not e["credential"]:
        return "malformed"
    return None


def fingerprint(link: str) -> str:
    """Canonical identity: scheme, host, port, transport, security, SNI, path, and the credential hashed."""
    e = endpoint(link)
    credential = hashlib.sha256(e["credential"].encode()).hexdigest()[:16] if e["credential"] else ""
    fields = [e["scheme"], e["host"], str(e["port"] or ""), credential, e["type"], e["security"],
              e["sni"], e["path"], e["service"], e["pbk"]]
    return hashlib.sha256("|".join(fields).encode()).hexdigest()


def alive(link: str, timeout: float = ALIVE_TIMEOUT_SEC) -> bool:
    """True when the server accepts a TCP connection (UDP protocols are kept: TCP says nothing about them)."""
    e = endpoint(link)
    if e["scheme"] in ("hysteria2", "hy2", "wireguard", "wg"):
        return True
    try:
        with socket.create_connection((e["host"], e["port"]), timeout=timeout):
            return True
    except OSError:
        return False


def keep_alive(links: list[str], report: dict, check=alive) -> list[str]:
    """Drops servers that do not answer from here; a server that answers may still be blocked elsewhere."""
    with ThreadPoolExecutor(max_workers=ALIVE_WORKERS) as pool:
        results = list(pool.map(lambda link: _safe(check, link), links))
    kept = [link for link, ok in zip(links, results) if ok]
    if len(kept) < len(links):
        report["rejected"]["not answering"] = report["rejected"].get("not answering", 0) + len(links) - len(kept)
    return kept


def _safe(check, link: str) -> bool:
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


def collect(sources: list[dict], fetcher=fetch, check=None) -> tuple[list[str], dict]:
    per_source: list[list[str]] = []
    seen: set[str] = set()
    report = {"sources": {}, "rejected": {}}
    for source in sources:
        name = source.get("name") or source.get("url", "?")
        try:
            text = decode_maybe_base64(fetcher(source["url"]))
        except Exception as error:  # a source that is down must not stop the others
            report["sources"][name] = f"not fetched: {type(error).__name__}"
            continue
        links: list[str] = []
        for raw in text.splitlines():
            link = raw.strip()
            if not link or link.startswith("#"):
                continue
            reason = problem(link)
            if reason:
                report["rejected"][reason] = report["rejected"].get(reason, 0) + 1
                continue
            key = fingerprint(link)
            if key in seen:
                report["rejected"]["duplicate"] = report["rejected"].get("duplicate", 0) + 1
                continue
            seen.add(key)
            try:
                links.append(rename(link, f"Free {endpoint(link)['scheme'].upper()} {len(seen)}"))
            except Exception:
                report["rejected"]["malformed"] = report["rejected"].get("malformed", 0) + 1
            if len(links) >= MAX_PER_SOURCE:
                break
        if check is not None:
            links = keep_alive(links, report, check)
        report["sources"][name] = f"{len(links)} kept"
        per_source.append(links)
    return interleave(per_source), report


def sign(payload: bytes, pem: str) -> str:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = serialization.load_pem_private_key(pem.encode(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise ValueError("HUB_SIGNING_KEY must be an ECDSA P-256 key")
    return base64.b64encode(key.sign(payload, ec.ECDSA(hashes.SHA256()))).decode()


def build(output_dir: Path, sources_file: Path, fetcher=fetch, now=None, key_pem: str | None = None, check=None) -> dict:
    config = json.loads(sources_file.read_text())
    links, report = collect(config.get("sources", []), fetcher, check)
    output_dir.mkdir(parents=True, exist_ok=True)
    plain = ("\n".join(links) + "\n") if links else ""
    (output_dir / "free.txt").write_text(plain)
    (output_dir / "free-base64.txt").write_text(base64.b64encode(plain.encode()).decode())
    files = {}
    for name in ("free.txt", "free-base64.txt"):
        data = (output_dir / name).read_bytes()
        files[name] = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    manifest = {
        "version": 1,
        "created": (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "count": len(links),
        "files": files,
        "report": report,
    }
    payload = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    (output_dir / "manifest.json").write_bytes(payload)
    signature = ""
    if key_pem:
        signature = sign(payload, key_pem)
    (output_dir / "manifest.sig").write_text(signature)
    return manifest


def main() -> int:
    # AGGREGATOR_CHECK_ALIVE=0 skips the TCP check (for runs without outbound access).
    check = None if os.environ.get("AGGREGATOR_CHECK_ALIVE", "1") == "0" else alive
    manifest = build(ROOT / "output", ROOT / "sources" / "sources.json",
                     key_pem=os.environ.get("HUB_SIGNING_KEY") or None, check=check)
    print(f"{manifest['count']} configs; manifest {'signed' if (ROOT / 'output' / 'manifest.sig').read_text() else 'UNSIGNED (no key)'}")
    for name, state in manifest["report"]["sources"].items():
        print(f"  {name}: {state}")
    if manifest["report"]["rejected"]:
        print("  rejected: " + ", ".join(f"{k}={v}" for k, v in sorted(manifest["report"]["rejected"].items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
