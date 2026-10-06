import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import aggregate  # noqa: E402
import pipeline  # noqa: E402
import verify  # noqa: E402

UUID = "11111111-1111-1111-1111-111111111111"


def vless(host, port=443, security="reality", transport="tcp", sni="www.example.com", extra=""):
    tail = "&pbk=abc&sid=ab" if security == "reality" else ""
    return f"vless://{UUID}@{host}:{port}?security={security}&sni={sni}&type={transport}{tail}{extra}#n"


def candidate(host, score_ms=(200, 210, 205), attempts=3, transport="tcp", security="reality", source="s", sites=("YT",)):
    link = vless(host, transport=transport, security=security)
    c = pipeline.normalize(link, aggregate.endpoint(link), aggregate.fingerprint(link), source)
    c.measurement.samples_ms = list(score_ms)
    c.measurement.successes = len(score_ms)
    c.measurement.attempts = attempts
    c.measurement.sites = list(sites)
    c.global_status = pipeline.global_status(c.measurement)
    pipeline.score(c)
    return c


class SecurityFilterTests(unittest.TestCase):
    def test_new_rejection_reasons(self):
        cases = {
            vless("203.0.113.7", transport="kcp"): "unsupported transport",
            vless("203.0.113.7", extra="&fp=unsafe"): "insecure TLS settings",
            vless("2130706433"): "obfuscated address",
            vless("0x7f.0.0.1"): "obfuscated address",
            vless("[fd00::1]"): "private or reserved address",
            vless("[::ffff:10.0.0.1]"): "private or reserved address",
            "ss://cmM0LW1kNTpwYXNz@203.0.113.5:8388#S": "weak cipher",
        }
        for link, reason in cases.items():
            self.assertEqual(reason, aggregate.problem(link), link)

    def test_an_aead_shadowsocks_and_a_public_ipv6_pass(self):
        self.assertIsNone(aggregate.problem("ss://YWVzLTI1Ni1nY206cGFzcw@203.0.113.5:8388#S"))
        self.assertIsNone(aggregate.problem(vless("[2001:4860::8888]")))

    def test_quarantine_records_keep_the_reason_but_never_the_credential(self):
        state = {}
        bad = f"trojan://secretpass@203.0.113.9:443?security=tls&sni=t.example&allowInsecure=1#T"
        aggregate.collect([{"id": "one", "name": "one", "url": "https://a.example/s"}],
                          lambda url: bad, state=state)
        self.assertEqual(1, len(state["quarantine"]))
        record = state["quarantine"][0]
        self.assertEqual("certificate checks disabled", record["reason"])
        self.assertEqual("QUARANTINED", record["status"])
        self.assertNotIn("secretpass", json.dumps(record))


class ClassificationTests(unittest.TestCase):
    def test_cloudflare_addresses_and_worker_hosts_share_one_failure_domain(self):
        a = candidate("104.16.1.1", transport="ws", security="tls")
        b = candidate("edge.example.workers.dev", transport="ws", security="tls")
        self.assertTrue(a.cdn and b.cdn)
        self.assertEqual("cdn:cloudflare", a.failure_domain)
        self.assertEqual(a.failure_domain, b.failure_domain)

    def test_direct_servers_group_by_network(self):
        a, b, c = candidate("203.0.113.7"), candidate("203.0.200.7"), candidate("198.51.100.7")
        self.assertFalse(a.cdn)
        self.assertEqual(a.failure_domain, b.failure_domain)
        self.assertNotEqual(a.failure_domain, c.failure_domain)
        self.assertEqual("ipv4", a.family)
        self.assertEqual("ipv6", candidate("[2001:db8::1]").family)

    def test_the_display_name_is_not_part_of_the_identity(self):
        link = vless("203.0.113.7")
        self.assertEqual(aggregate.fingerprint(link), aggregate.fingerprint(link.replace("#n", "#other name")))


class ScoringTests(unittest.TestCase):
    def test_a_stable_250ms_server_beats_an_unstable_80ms_one(self):
        stable = candidate("203.0.113.7", (250, 255, 248), attempts=3)
        unstable = candidate("198.51.100.7", (80, 600), attempts=3)
        self.assertGreater(stable.score, unstable.score)

    def test_the_score_is_deterministic_and_explained(self):
        a = candidate("203.0.113.7")
        b = candidate("203.0.113.7")
        self.assertEqual(a.score, b.score)
        self.assertEqual(set(pipeline.WEIGHTS), set(a.score_reasons))
        self.assertAlmostEqual(a.score, sum(r["points"] for r in a.score_reasons.values()), places=1)
        self.assertIn("not Iran", a.score_reasons["http"]["why"])

    def test_latency_never_dominates(self):
        self.assertLessEqual(pipeline.WEIGHTS["latency"], min(pipeline.WEIGHTS["reliability"], pipeline.WEIGHTS["security"]))

    def test_phone_evidence_moves_the_score_and_unknown_is_neutral(self):
        base = candidate("203.0.113.7")
        good = candidate("203.0.113.7")
        pipeline.score(good, iran={"attempts": 4, "successes": 4})
        bad = candidate("203.0.113.7")
        pipeline.score(bad, iran={"attempts": 4, "successes": 0})
        self.assertGreater(good.score, base.score)
        self.assertLess(bad.score, base.score)

    def test_global_statuses(self):
        m = pipeline.Measurement(attempts=3, samples_ms=[1, 2], successes=2)
        self.assertEqual(pipeline.GLOBAL_VERIFIED, pipeline.global_status(m))
        self.assertEqual(pipeline.GLOBAL_FAILED, pipeline.global_status(pipeline.Measurement(attempts=3, successes=1)))
        self.assertEqual(pipeline.GLOBAL_UNTESTED, pipeline.global_status(pipeline.Measurement()))


class SelectionTests(unittest.TestCase):
    def test_never_more_than_thirty(self):
        many = [candidate(f"198.{18 + i // 250 + 30}.{i % 250}.7") for i in range(80)]
        self.assertEqual(30, len(pipeline.select_diverse(many)))

    def test_one_failure_domain_cannot_fill_the_list_while_others_exist(self):
        # 40 fast Cloudflare servers and 10 slower direct ones on different networks.
        cdn = [candidate(f"104.16.{i}.1", (100, 101, 100), transport="ws", security="tls") for i in range(40)]
        direct = [candidate(f"{20 + i}.0.0.1", (400, 410, 405)) for i in range(10)]
        picked = pipeline.select_diverse(cdn + direct)
        self.assertEqual(30, len(picked))
        self.assertEqual(10, sum(1 for c in picked if not c.cdn))
        # When the caps cannot fill 30, the rest is filled from the best remaining.
        self.assertEqual(20, sum(1 for c in picked if c.cdn))

    def test_caps_hold_when_enough_alternatives_exist(self):
        same = [candidate(f"203.0.{i}.7", (90, 90, 90)) for i in range(20)]
        others = [candidate(f"{30 + i}.1.0.1", (300, 300, 300), source=f"s{i % 5}") for i in range(40)]
        picked = pipeline.select_diverse(same + others)
        report = pipeline.diversity_report(picked)
        self.assertLessEqual(report["largest_failure_domain"], pipeline.MAX_PER_DOMAIN)

    def test_both_address_families_are_kept_when_both_exist(self):
        v4 = [candidate(f"{30 + i}.1.0.1", (100, 100, 100)) for i in range(40)]
        v6 = [candidate("[2001:db8::7]", (900, 950, 900))]
        picked = pipeline.select_diverse(v4 + v6)
        self.assertIn("ipv6", {c.family for c in picked})

    def test_only_global_verified_servers_are_published(self):
        failed = candidate("203.0.113.9", (100,), attempts=3)
        self.assertEqual(pipeline.GLOBAL_FAILED, failed.global_status)
        self.assertEqual([], pipeline.select_diverse([failed]))


class BuildOutputTests(unittest.TestCase):
    def test_configs_json_carries_statuses_scores_and_no_secrets(self):
        links = [vless(f"{30 + i}.1.0.1") for i in range(5)]
        source = "\n".join(links)

        def fake_verify(ls):
            return {l: verify.Result(0.2, frozenset({"YT", "TG"}), (0.2, 0.21, 0.2), 3) for l in ls}

        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "sources.json"
            src.write_text(json.dumps({"sources": [{"id": "one", "name": "one", "url": "https://a.example/s"}]}))
            out = Path(tmp) / "out"
            manifest = aggregate.build(out, src, fetcher=lambda u: source, verify=fake_verify)
            configs = json.loads((out / "configs.json").read_text())["configs"]
            history = json.loads((out / "history.json").read_text())
            text = (out / "configs.json").read_text()
        self.assertEqual(5, manifest["count"])
        self.assertEqual(5, len(configs))
        for record in configs:
            self.assertEqual("GLOBAL_VERIFIED", record["global_status"])
            self.assertEqual("UNKNOWN_IRAN_STATUS", record["iran_status"])
            self.assertEqual({"ok": 3, "attempts": 3}, record["rounds"])
            self.assertIn("reliability", record["score_reasons"])
        self.assertNotIn(UUID, text)
        self.assertNotIn("Works in Iran", text)
        self.assertEqual(5, len(history))
        self.assertIn("configs.json", manifest["files"])
        meta = manifest["report"]["source_meta"][0]
        self.assertEqual(("one", "ok", 5, 5), (meta["source_id"], meta["fetch_status"], meta["candidate_count"], meta["valid_count"]))
        self.assertEqual("UNKNOWN_IRAN_STATUS", manifest["evidence"]["iran"])

    def test_a_failed_source_is_recorded_in_its_metadata(self):
        def fetcher(url):
            raise OSError("down")
        _, report = aggregate.collect([{"id": "x", "name": "x", "url": "https://a.example/s"}], fetcher)
        self.assertTrue(report["source_meta"][0]["fetch_status"].startswith("failed"))

    def test_history_counts_passes_across_runs(self):
        c = candidate("203.0.113.7")
        h = pipeline.update_history({}, [c], "t1")
        h = pipeline.update_history(h, [c], "t2")
        self.assertEqual({"seen": 2, "passed": 2}, {k: h[c.fingerprint][k] for k in ("seen", "passed")})


if __name__ == "__main__":
    unittest.main()
