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
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, parse_qs, unquote

ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 5 * 1024 * 1024
TIMEOUT_SEC = 30
MAX_PER_SOURCE = 500
SCHEMES = ("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "wireguard://", "wg://")
ENCRYPTED_SECURITY = ("tls", "reality")


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


def problem(link: str) -> str | None:
    """Why this link must not be published, or None."""
    if not link.startswith(SCHEMES):
        return "unknown scheme"
    if len(link) > 2048:
        return "too long"
    if any(c.isspace() for c in link.strip()) or any(ord(c) < 32 for c in link):
        return "control characters"
    parts = urlsplit(link)
    host = (parts.hostname or "").lower()
    if not host or host.endswith(".local") or host in ("localhost", "metadata.google.internal"):
        return "local host"
    if host.replace(".", "").isdigit():
        octets = [int(o) for o in host.split(".") if o.isdigit()]
        if len(octets) == 4 and (
            octets[0] in (0, 10, 127) or octets[0] >= 240
            or (octets[0] == 100 and 64 <= octets[1] <= 127)
            or (octets[0] == 169 and octets[1] == 254)
            or (octets[0] == 172 and 16 <= octets[1] <= 31)
            or (octets[0] == 192 and octets[1] == 168)
            or (octets[0] == 198 and octets[1] in (18, 19))
        ):
            return "private or reserved address"
    query = parse_qs(parts.query)
    if any(v and v[0] in ("1", "true") for k, v in query.items() if k.lower() in ("allowinsecure", "insecure", "skip-cert-verify")):
        return "certificate checks disabled"
    if link.startswith(("vless://", "trojan://")):
        security = (query.get("security", [""])[0] or "").lower()
        encryption = (query.get("encryption", [""])[0] or "").lower()
        if security not in ENCRYPTED_SECURITY and encryption in ("", "none"):
            return "no encryption"
    return None


def fingerprint(link: str) -> str:
    """Canonical identity: scheme, host, port, transport, security, SNI, path, and the credential hashed."""
    parts = urlsplit(link)
    query = parse_qs(parts.query)
    credential = hashlib.sha256(unquote(parts.username or "").encode()).hexdigest()[:16] if parts.username else ""
    fields = [
        parts.scheme.lower(), (parts.hostname or "").lower(), str(parts.port or ""), credential,
        (query.get("type", [""])[0] or "").lower(), (query.get("security", [""])[0] or "").lower(),
        (query.get("sni", [""])[0] or "").lower(), query.get("path", [""])[0],
        query.get("serviceName", [""])[0], query.get("pbk", [""])[0],
    ]
    return hashlib.sha256("|".join(fields).encode()).hexdigest()


def collect(sources: list[dict], fetcher=fetch) -> tuple[list[str], dict]:
    links: list[str] = []
    seen: set[str] = set()
    report = {"sources": {}, "rejected": {}}
    for source in sources:
        name = source.get("name") or source.get("url", "?")
        try:
            text = decode_maybe_base64(fetcher(source["url"]))
        except Exception as error:  # a source that is down must not stop the others
            report["sources"][name] = f"not fetched: {type(error).__name__}"
            continue
        kept = 0
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
            links.append(link)
            kept += 1
            if kept >= MAX_PER_SOURCE:
                break
        report["sources"][name] = f"{kept} kept"
    return links, report


def sign(payload: bytes, pem: str) -> str:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = serialization.load_pem_private_key(pem.encode(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise ValueError("HUB_SIGNING_KEY must be an ECDSA P-256 key")
    return base64.b64encode(key.sign(payload, ec.ECDSA(hashes.SHA256()))).decode()


def build(output_dir: Path, sources_file: Path, fetcher=fetch, now=None, key_pem: str | None = None) -> dict:
    config = json.loads(sources_file.read_text())
    links, report = collect(config.get("sources", []), fetcher)
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
    manifest = build(ROOT / "output", ROOT / "sources" / "sources.json", key_pem=os.environ.get("HUB_SIGNING_KEY") or None)
    print(f"{manifest['count']} configs; manifest {'signed' if (ROOT / 'output' / 'manifest.sig').read_text() else 'UNSIGNED (no key)'}")
    for name, state in manifest["report"]["sources"].items():
        print(f"  {name}: {state}")
    if manifest["report"]["rejected"]:
        print("  rejected: " + ", ".join(f"{k}={v}" for k, v in sorted(manifest["report"]["rejected"].items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
