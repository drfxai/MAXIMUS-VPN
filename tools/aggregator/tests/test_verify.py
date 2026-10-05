import base64
import json
import os
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import aggregate  # noqa: E402
import verify  # noqa: E402

UUID = "11111111-1111-1111-1111-111111111111"


class LinkTests(unittest.TestCase):
    def test_vless_reality_becomes_an_outbound(self):
        ob = verify.outbound(f"vless://{UUID}@203.0.113.7:443?security=reality&sni=www.example.com&pbk=KEY&sid=ab&fp=chrome&flow=xtls-rprx-vision&type=tcp#R")
        self.assertEqual("vless", ob["protocol"])
        user = ob["settings"]["vnext"][0]["users"][0]
        self.assertEqual((UUID, "xtls-rprx-vision", "none"), (user["id"], user["flow"], user["encryption"]))
        self.assertEqual("KEY", ob["streamSettings"]["realitySettings"]["publicKey"])

    def test_vmess_trojan_and_shadowsocks_forms(self):
        body = {"add": "203.0.113.20", "port": "443", "id": UUID, "net": "ws", "tls": "tls", "sni": "v.example", "path": "/p", "host": "h.example"}
        vmess = verify.outbound("vmess://" + base64.b64encode(json.dumps(body).encode()).decode())
        self.assertEqual("/p", vmess["streamSettings"]["wsSettings"]["path"])
        self.assertEqual("v.example", vmess["streamSettings"]["tlsSettings"]["serverName"])

        trojan = verify.outbound("trojan://p%40ss@203.0.113.9:443?security=tls&sni=t.example&type=ws&path=%2Fx#T")
        self.assertEqual("p@ss", trojan["settings"]["servers"][0]["password"])

        sip002 = verify.outbound("ss://" + base64.urlsafe_b64encode(b"aes-256-gcm:secret").decode().rstrip("=") + "@203.0.113.5:8388#S")
        self.assertEqual(("aes-256-gcm", "secret", 8388), tuple(sip002["settings"]["servers"][0][k] for k in ("method", "password", "port")))
        legacy = verify.outbound("ss://" + base64.b64encode(b"aes-256-gcm:secret@203.0.113.5:8388").decode() + "#S")
        self.assertEqual("203.0.113.5", legacy["settings"]["servers"][0]["address"])

    def test_formats_xray_cannot_run_as_a_plain_outbound_are_not_testable(self):
        for link in ("hysteria2://pw@203.0.113.7:443?sni=a.example#H", "wireguard://key@203.0.113.7:51820#W",
                     "ss://YWVzLTI1Ni1nY206cHc@203.0.113.5:8388?plugin=obfs-local#P",
                     f"vless://{UUID}@203.0.113.7:443?security=tls&sni=a.example&type=kcp#K"):
            self.assertIsNone(verify.outbound(link), link)

    def test_every_candidate_gets_its_own_port_and_route(self):
        config = verify._config([{"protocol": "freedom"}, {"protocol": "freedom"}], 30000)
        self.assertEqual([30000, 30001], [i["port"] for i in config["inbounds"]])
        self.assertEqual(["o0", "o1"], [r["outboundTag"] for r in config["routing"]["rules"]])


class KeepWorkingTests(unittest.TestCase):
    def test_each_source_is_ordered_fastest_first_and_untested_servers_are_dropped(self):
        report = {"rejected": {}}
        works = aggregate.keep_working([["a", "b", "c"], ["d", "e"]], lambda links: {"c": 0.2, "a": 0.9, "e": 0.4}, report)
        self.assertEqual([["c", "a"], ["e"]], works)
        self.assertEqual(2, report["rejected"]["no traffic"])

    def test_the_list_never_exceeds_thirty_and_holds_only_servers_that_worked(self):
        link = lambda n: f"vless://{UUID}@203.0.113.{n % 250 + 1}:{1000 + n}?security=tls&sni=a.example&type=tcp#n"
        sources = []
        for s in range(3):
            sources.append({"name": f"s{s}", "url": f"https://example.test/{s}"})
        texts = {f"https://example.test/{s}": "\n".join(link(s * 100 + n) for n in range(80)) for s in range(3)}
        works = set(l for t in texts.values() for l in t.split("\n")[::2])
        links, report = aggregate.collect(sources, texts.__getitem__, None, lambda ls: {l: 1.0 for l in ls if l in works})
        self.assertEqual(30, len(links))
        self.assertTrue(set(links) <= works)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


XRAY = os.environ.get("XRAY_BIN") or shutil.which("xray") or ""


@unittest.skipUnless(XRAY and os.access(XRAY, os.X_OK), "set XRAY_BIN to run the test against a real Xray core")
class RealCoreTests(unittest.TestCase):
    """A real VLESS server and a real page: only the link that carries the request may survive."""

    def setUp(self):
        class Page(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(204)
                self.end_headers()

            def log_message(self, *args):
                pass

        self.web = HTTPServer(("127.0.0.1", 0), Page)
        threading.Thread(target=self.web.serve_forever, daemon=True).start()
        self.server_port = free_port()
        self.dir = tempfile.TemporaryDirectory()
        config = Path(self.dir.name) / "server.json"
        config.write_text(json.dumps({
            "log": {"loglevel": "none"},
            "inbounds": [{"listen": "127.0.0.1", "port": self.server_port, "protocol": "vless",
                          "settings": {"clients": [{"id": UUID}], "decryption": "none"}, "streamSettings": {"network": "tcp"}}],
            "outbounds": [{"protocol": "freedom", "settings": {"finalRules": [{"action": "allow"}]}}]}))
        self.core = subprocess.Popen([XRAY, "run", "-c", str(config)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", self.server_port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)

    def tearDown(self):
        self.core.terminate()
        self.core.wait(timeout=5)
        self.web.shutdown()
        self.dir.cleanup()

    def test_only_a_server_that_carries_the_request_is_kept(self):
        base = f"@127.0.0.1:{self.server_port}?type=tcp&security=none&encryption=none"
        good = f"vless://{UUID}{base}#good"
        wrong_id = f"vless://22222222-2222-2222-2222-222222222222{base}#wrong"
        dead = f"vless://{UUID}@127.0.0.1:{free_port()}?type=tcp&security=none&encryption=none#dead"
        # Xray refuses a whole configuration holding this one; only this entry may be lost.
        refused = f"vless://{UUID}@127.0.0.1:{self.server_port}?type=tcp&security=reality&sni=a.example&pbk=not-a-key#bad"
        url = f"http://127.0.0.1:{self.web.server_address[1]}/generate_204"
        found = verify.latencies([refused, wrong_id, good, dead], XRAY, url=url, timeout=4, first_port=random.randint(24000, 40000))
        self.assertEqual([good], list(found))
        self.assertGreater(found[good], 0)


if __name__ == "__main__":
    unittest.main()
