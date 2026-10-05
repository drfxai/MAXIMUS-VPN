import base64
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import aggregate  # noqa: E402

GOOD = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#R"
GOOD2 = GOOD.replace("203.0.113.7", "203.0.113.8")


class ValidationTests(unittest.TestCase):
    def test_accepts_an_encrypted_public_node(self):
        self.assertIsNone(aggregate.problem(GOOD))

    def test_refuses_unsafe_entries(self):
        cases = {
            "vless://u@203.0.113.7:80?security=none&type=ws&path=/#P": "no encryption",
            "trojan://pw@203.0.113.9:443?security=tls&sni=t.example&allowInsecure=1#T": "certificate checks disabled",
            "vless://u@10.10.34.36:443?security=reality&sni=a.example&pbk=k#B": "private or reserved address",
            "vless://u@127.0.0.1:443?security=tls&sni=a.example#L": "private or reserved address",
            "vless://u@localhost:443?security=tls&sni=a.example#L2": "local host",
            "http://example.com/x": "unknown scheme",
        }
        for link, reason in cases.items():
            self.assertEqual(reason, aggregate.problem(link), link)

    def test_the_same_node_under_two_names_is_one_entry(self):
        self.assertEqual(aggregate.fingerprint(GOOD), aggregate.fingerprint(GOOD.replace("#R", "#Renamed")))
        self.assertNotEqual(aggregate.fingerprint(GOOD), aggregate.fingerprint(GOOD2))

    def test_the_credential_is_never_published_in_the_fingerprint(self):
        self.assertNotIn("11111111", aggregate.fingerprint(GOOD))


def vmess(**fields):
    body = {"v": "2", "ps": "@SomeChannel ad", "add": "203.0.113.20", "port": "443", "id": "22222222-2222-2222-2222-222222222222",
            "net": "ws", "tls": "tls", "sni": "v.example", "path": "/"}
    body.update(fields)
    return "vmess://" + base64.b64encode(json.dumps(body).encode()).decode()


class RealWorldTests(unittest.TestCase):
    def test_a_malformed_port_is_refused_instead_of_stopping_the_run(self):
        self.assertEqual("malformed", aggregate.problem("vless://u@203.0.113.7:abc@x?security=tls#M"))
        self.assertEqual("malformed", aggregate.problem("trojan://pw@203.0.113.7:0?security=tls#Z"))

    def test_links_the_app_would_refuse_are_not_published(self):
        cases = {
            GOOD.replace("&pbk=abc", ""): "incomplete reality settings",
            GOOD.replace("&sni=www.example.com", ""): "incomplete reality settings",
            GOOD.replace(":443?", ":443/?"): "malformed",
            GOOD.replace("11111111-1111-1111-1111-111111111111", "%58pnTeam-51"): "malformed",
            "trojan://pw@203.0.113.9:443?type=tcp#T": "no encryption",
        }
        for link, reason in cases.items():
            self.assertEqual(reason, aggregate.problem(link), link)

    def test_vmess_is_read_from_its_json(self):
        self.assertIsNone(aggregate.problem(vmess()))
        self.assertEqual("private or reserved address", aggregate.problem(vmess(add="192.168.1.1")))
        self.assertEqual("certificate checks disabled", aggregate.problem(vmess(allowInsecure=True)))
        self.assertEqual("malformed", aggregate.problem("vmess://bm90IGpzb24="))
        self.assertNotEqual(aggregate.fingerprint(vmess()), aggregate.fingerprint(vmess(add="203.0.113.21")))

    def test_names_with_spaces_or_ads_are_replaced(self):
        link = GOOD.replace("#R", "#🇩🇪 - DE - @FreeChannel")
        self.assertIsNone(aggregate.problem(link))
        self.assertTrue(aggregate.rename(link, "Free VLESS 1").endswith("#Free%20VLESS%201"))
        renamed = aggregate.rename(vmess(), "Free VMESS 1")
        body = json.loads(base64.b64decode(renamed[len("vmess://"):]))
        self.assertEqual("Free VMESS 1", body["ps"])
        self.assertEqual("203.0.113.20", body["add"])

    def test_sources_take_turns_up_to_the_limit(self):
        self.assertEqual(["a1", "b1", "a2", "b2", "a3"], aggregate.interleave([["a1", "a2", "a3"], ["b1", "b2"]], limit=10))
        self.assertEqual(["a1", "b1", "a2"], aggregate.interleave([["a1", "a2", "a3"], ["b1", "b2"]], limit=3))

    def test_servers_that_do_not_answer_are_dropped(self):
        report = {"rejected": {}}
        kept = aggregate.keep_alive([GOOD, GOOD2], report, check=lambda link: "203.0.113.7" in link)
        self.assertEqual([GOOD], kept)
        self.assertEqual(1, report["rejected"]["not answering"])
        self.assertEqual([], aggregate.keep_alive([GOOD], {"rejected": {}}, check=lambda link: 1 / 0))


class BuildTests(unittest.TestCase):
    def build(self, sources, fetched, key=None):
        """Builds into a temporary directory and returns the manifest plus every file's bytes."""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "output"
            src = Path(tmp) / "sources.json"
            src.write_text(json.dumps({"version": 1, "sources": sources}))
            manifest = aggregate.build(out, src, fetcher=lambda url: fetched[url], key_pem=key)
            files = {f.name: f.read_bytes() for f in out.iterdir()}
            return manifest, files, files["free.txt"].decode(), files["manifest.sig"].decode()

    def test_a_source_that_is_down_does_not_stop_the_others(self):
        sources = [{"name": "up", "url": "https://a.example/s"}, {"name": "down", "url": "https://b.example/s"}]
        fetched = {"https://a.example/s": GOOD + "\n" + GOOD2}
        manifest, files, plain, _ = self.build(sources, DictMissing(fetched))
        self.assertEqual(2, manifest["count"])
        self.assertEqual("2 kept", manifest["report"]["sources"]["up"])
        self.assertTrue(manifest["report"]["sources"]["down"].startswith("not fetched"))
        self.assertEqual(plain, GOOD.replace("#R", "#Free%20VLESS%201") + "\n" + GOOD2.replace("#R", "#Free%20VLESS%202") + "\n")
        self.assertEqual(base64.b64encode(plain.encode()).decode(), files["free-base64.txt"].decode())
        for name, entry in manifest["files"].items():
            self.assertEqual(entry["bytes"], len(files[name]))

    def test_a_base64_source_is_decoded_and_duplicates_dropped(self):
        encoded = base64.b64encode((GOOD + "\n" + GOOD + "\n" + GOOD2).encode()).decode()
        manifest, _, plain, _ = self.build([{"name": "b64", "url": "https://a.example/s"}], {"https://a.example/s": encoded})
        self.assertEqual(2, manifest["count"])
        self.assertEqual(1, manifest["report"]["rejected"]["duplicate"])

    def test_without_a_key_the_manifest_is_written_unsigned(self):
        _, _, _, signature = self.build([], {})
        self.assertEqual("", signature)

    def test_a_signed_manifest_verifies_and_a_changed_one_does_not(self):
        key = subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout"],
                             capture_output=True, text=True, check=True).stdout
        manifest, files, _, signature = self.build([{"name": "s", "url": "https://a.example/s"}], {"https://a.example/s": GOOD}, key=key)
        self.assertTrue(signature)
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        public = serialization.load_pem_private_key(key.encode(), password=None).public_key()
        payload = files["manifest.json"]
        public.verify(base64.b64decode(signature), payload, ec.ECDSA(hashes.SHA256()))
        with self.assertRaises(Exception):
            public.verify(base64.b64decode(signature), payload + b" ", ec.ECDSA(hashes.SHA256()))


class DictMissing(dict):
    def __getitem__(self, key):
        if key not in self:
            raise OSError("unreachable")
        return dict.__getitem__(self, key)


if __name__ == "__main__":
    unittest.main()
