import base64
import copy
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import aggregate
from iran_verification import IranEvidence, POLICY, canonical, config_digest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

NOW = datetime(2026, 10, 6, 0, tzinfo=timezone.utc)
GOOD = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#R"


def signed_evidence(links, mutate=None, asns=(44244, 197207)):
    probes, reports, keys = {}, [], []
    for i, asn in enumerate(asns):
        key = Ed25519PrivateKey.generate()
        keys.append(key)
        probe_id = f"probe-{i}"
        probes[probe_id] = {"country": "IR", "asn": asn, "public_key": base64.b64encode(
            key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()}
        payload = {"policy": POLICY, "country": "IR", "asn": asn, "probe_id": probe_id, "results": [
            {"config_sha256": config_digest(link), "measured_at": NOW.isoformat(),
             "attempts": 5, "successes": 5, "https_targets": 2, "proxy_authenticated": True,
             "tls_verified": True, "egress_confirmed": True, "p95_ms": 600, "download_mbps": 8.0}
            for link in links]}
        if mutate:
            mutate(payload, i)
        reports.append({"payload": payload, "signature": base64.b64encode(key.sign(canonical(payload))).decode()})
    return IranEvidence(reports, probes, NOW), reports, probes, keys


class IranVerificationTests(unittest.TestCase):
    def test_two_independent_signed_networks_are_required(self):
        self.assertTrue(signed_evidence([GOOD])[0].accepts(GOOD))
        self.assertFalse(signed_evidence([GOOD], asns=(44244,))[0].accepts(GOOD))
        self.assertFalse(signed_evidence([GOOD], asns=(44244, 44244))[0].accepts(GOOD))

    def test_no_keys_or_reports_admit_nothing(self):
        self.assertFalse(IranEvidence([], {}, NOW).accepts(GOOD))
        _, reports, _, _ = signed_evidence([GOOD])
        self.assertFalse(IranEvidence(reports, {}, NOW).accepts(GOOD))

    def test_unsigned_modified_or_wrong_asn_reports_are_rejected(self):
        _, reports, probes, _ = signed_evidence([GOOD])
        reports[0]["payload"]["results"][0]["p95_ms"] = 1
        self.assertFalse(IranEvidence(reports, probes, NOW).accepts(GOOD))
        evidence = signed_evidence([GOOD], mutate=lambda p, i: p.update(asn=123))[0]
        self.assertFalse(evidence.accepts(GOOD))

    def test_proof_binds_every_option_but_not_display_fragment(self):
        evidence = signed_evidence([GOOD])[0]
        self.assertTrue(evidence.accepts(GOOD.replace("#R", "#Other")))
        for original, changed in [("sid=ab", "sid=cd"), ("pbk=abc", "pbk=xyz"), ("type=tcp", "type=ws")]:
            self.assertFalse(evidence.accepts(GOOD.replace(original, changed)))

    def test_stale_future_weak_or_tcp_only_results_cannot_qualify(self):
        for field, value in [("attempts", 4), ("successes", 4), ("https_targets", 1),
                             ("proxy_authenticated", False), ("tls_verified", False),
                             ("egress_confirmed", False), ("p95_ms", 2501),
                             ("download_mbps", 1.9), ("attempts", True), ("p95_ms", "600"),
                             ("measured_at", (NOW - timedelta(hours=7)).isoformat()),
                             ("measured_at", (NOW + timedelta(hours=1)).isoformat())]:
            evidence = signed_evidence([GOOD], mutate=lambda p, i: p["results"][0].update({field: value}))[0]
            self.assertFalse(evidence.accepts(GOOD), field)
        evidence = signed_evidence([GOOD], mutate=lambda p, i: p.update(country="US"))[0]
        self.assertFalse(evidence.accepts(GOOD))

    def test_newer_failure_overrides_an_old_success_on_the_same_network(self):
        _, reports, probes, keys = signed_evidence([GOOD])
        for envelope in reports:
            envelope["payload"]["results"][0]["measured_at"] = (NOW - timedelta(minutes=10)).isoformat()
        for i, envelope in enumerate(reports):
            envelope["signature"] = base64.b64encode(keys[i].sign(canonical(envelope["payload"]))).decode()
        failed = copy.deepcopy(reports[0]["payload"])
        failed["results"][0].update(measured_at=NOW.isoformat(), successes=0)
        reports.append({"payload": failed, "signature": base64.b64encode(keys[0].sign(canonical(failed))).decode()})
        self.assertFalse(IranEvidence(reports, probes, NOW).accepts(GOOD))

    def test_ranked_verified_list_never_exceeds30(self):
        links = [GOOD.replace("203.0.113.7", f"203.0.113.{i}") for i in range(1, 101)]
        evidence = signed_evidence(links)[0]
        kept, _ = aggregate.collect([{"url": "https://source.example"}],
                                    fetcher=lambda _: "\n".join(links), evidence=evidence)
        self.assertEqual(30, len(kept))
        self.assertTrue(all(evidence.accepts(link) for link in kept))

    def test_unverified_is_filtered_even_if_tcp_answers(self):
        evidence = signed_evidence([GOOD])[0]
        other = GOOD.replace("203.0.113.7", "203.0.113.8")
        kept, report = aggregate.collect([{"url": "https://source.example"}],
            fetcher=lambda _: GOOD + "\n" + other, check=lambda _: True, evidence=evidence)
        self.assertEqual([GOOD], kept)
        self.assertEqual(1, report["rejected"]["no recent Iran proxy proof"])

    def test_ranking_uses_the_worst_network_and_limits_host_concentration(self):
        links = [GOOD.replace("sid=ab", f"sid={i:02x}") for i in range(4)]
        other = GOOD.replace("203.0.113.7", "203.0.113.8")
        links.append(other)
        def metrics(payload, i):
            for index, result in enumerate(payload["results"]):
                result["p95_ms"] = 100 + index * 100
            # A fast result on one network must not hide poor performance on the other.
            if i == 1:
                payload["results"][0]["p95_ms"] = 2200
        evidence = signed_evidence(links, mutate=metrics)[0]
        kept, _ = aggregate.collect([{"url": "https://source.example"}],
            fetcher=lambda _: "\n".join(links), evidence=evidence)
        self.assertEqual([links[1], links[2], other], kept)

    def test_empty_authenticated_reports_create_an_explicit_empty_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            src = root / "sources.json"
            src.write_text(json.dumps({"sources": [{"url": "https://source.example"}]}))
            manifest = aggregate.build(root / "out", src, fetcher=lambda _: GOOD,
                                       evidence=IranEvidence([], {}, NOW), now=NOW)
            self.assertEqual(0, manifest["count"])
            self.assertEqual(POLICY, manifest["verification"]["policy"])
            self.assertEqual(b"", (root / "out" / "free.txt").read_bytes())

    def test_manifest_has_verification_and_unsigned_dry_run_cannot_be_signed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            src = root / "sources.json"
            src.write_text(json.dumps({"sources": [{"url": "https://source.example"}]}))
            with self.assertRaises(ValueError):
                aggregate.build(root / "out", src, key_pem="not-a-key")
            manifest = aggregate.build(root / "out", src, fetcher=lambda _: GOOD,
                                       evidence=signed_evidence([GOOD])[0], now=NOW)
            self.assertEqual(POLICY, manifest["verification"]["policy"])
            self.assertEqual(2, len(manifest["verification"]["networks"]))
            self.assertEqual(NOW.timestamp() + 21600, manifest["verification"]["valid_until"])
