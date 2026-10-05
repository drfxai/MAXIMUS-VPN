"""Admission policy for measurements from operator-enrolled Iranian network probes.

A GitHub TCP check is not an Iran measurement. Only signed, recent, end-to-end reports from
two independently enrolled ASNs qualify. No default keys, fabricated reports or TCP fallback.
"""
from __future__ import annotations

import base64
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

POLICY = "iran-proxy-v1"
MAX_AGE_SEC = 6 * 60 * 60
MIN_NETWORKS = 2
MIN_ATTEMPTS = 5
MIN_SUCCESS_RATE = 0.9
MAX_P95_MS = 2500
MIN_DOWNLOAD_MBPS = 2.0


def config_digest(link: str) -> str:
    # Bind proof to every URI field, including credentials/transport options. Only the display
    # fragment may change. Do not use the coarser deduplication identity for authentication.
    return hashlib.sha256(link.split("#", 1)[0].encode()).hexdigest()


def canonical(payload: dict) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def timestamp(value: str) -> datetime:
    date = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if date.utcoffset() is None:
        raise ValueError("measurement timestamp must include timezone")
    return date


class IranEvidence:
    def __init__(self, reports: list, probes: dict, now: datetime | None = None):
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

        self.now = now or datetime.now(timezone.utc)
        self.by_config: dict[str, dict[int, dict]] = {}
        self.invalid_reports = 0
        self.verified_reports = 0
        for envelope in reports:
            try:
                payload = envelope["payload"]
                probe = probes[payload["probe_id"]]
                public = Ed25519PublicKey.from_public_bytes(base64.b64decode(probe["public_key"], validate=True))
                public.verify(base64.b64decode(envelope["signature"], validate=True), canonical(payload))
                asn = payload["asn"]
                if type(asn) is not int or asn <= 0 or asn != probe["asn"]:
                    raise ValueError("probe ASN mismatch")
                if probe["country"] != "IR" or payload["country"] != "IR" or payload["policy"] != POLICY:
                    raise ValueError("not an enrolled Iran measurement")
                self.verified_reports += 1
                for result in payload["results"]:
                    if not isinstance(result, dict):
                        continue
                    digest = result.get("config_sha256", "")
                    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                        continue
                    # Retain the newest measurement on an ASN, including failures; an old success
                    # must not hide a newer outage on that same network.
                    measured = timestamp(result["measured_at"])
                    if measured > self.now:
                        continue
                    previous = self.by_config.setdefault(digest, {}).get(asn)
                    if previous is None or measured > timestamp(previous["measured_at"]):
                        self.by_config[digest][asn] = result
            except Exception:
                self.invalid_reports += 1

    @classmethod
    def load(cls, report_path: Path, probes_path: Path, now=None):
        reports = json.loads(report_path.read_text())
        probes = json.loads(probes_path.read_text())
        if not isinstance(reports, list) or not isinstance(probes, dict):
            raise ValueError("invalid Iran evidence files")
        return cls(reports, probes, now)

    def passing(self, result: dict) -> bool:
        try:
            age = (self.now - timestamp(result["measured_at"])).total_seconds()
            attempts, successes = result["attempts"], result["successes"]
            p95, speed = result["p95_ms"], result["download_mbps"]
            return (
                0 <= age < MAX_AGE_SEC
                and type(attempts) is int and type(successes) is int
                and MIN_ATTEMPTS <= attempts and 0 <= successes <= attempts
                and successes / attempts >= MIN_SUCCESS_RATE
                and type(result["https_targets"]) is int and result["https_targets"] >= 2
                and result["proxy_authenticated"] is True
                and result["tls_verified"] is True and result["egress_confirmed"] is True
                and type(p95) in (int, float) and math.isfinite(p95) and 0 < p95 <= MAX_P95_MS
                and type(speed) in (int, float) and math.isfinite(speed) and speed >= MIN_DOWNLOAD_MBPS
            )
        except (KeyError, ValueError, TypeError, OverflowError):
            return False

    def metrics(self, link: str) -> dict[int, dict]:
        return {asn: r for asn, r in self.by_config.get(config_digest(link), {}).items() if self.passing(r)}

    def accepts(self, link: str) -> bool:
        return len(self.metrics(link)) >= MIN_NETWORKS

    def rank(self, link: str) -> tuple:
        results = list(self.metrics(link).values())
        if len(results) < MIN_NETWORKS:
            return (0, 0, float("inf"), 0, config_digest(link))
        return (-len(results), -min(r["successes"] / r["attempts"] for r in results),
                max(r["p95_ms"] for r in results), -min(r["download_mbps"] for r in results), config_digest(link))

    def summary(self, links: list[str]) -> dict:
        measurements = [r for link in links for r in self.metrics(link).values()]
        return {"policy": POLICY, "minimum_networks": MIN_NETWORKS, "max_age_seconds": MAX_AGE_SEC,
                "valid_until": min((timestamp(r["measured_at"]).timestamp() + MAX_AGE_SEC for r in measurements), default=0),
                "verified_reports": self.verified_reports, "invalid_reports": self.invalid_reports,
                "networks": sorted({asn for link in links for asn in self.metrics(link)})}
