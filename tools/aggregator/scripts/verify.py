"""Sends a real request through every candidate server, so the published list holds only servers that carry traffic.

A TCP connection proves almost nothing: the large CDNs accept it for any config, and dead panels keep
their port open. Here each candidate becomes an outbound of a local Xray core and fetches a small page
through it, in three rounds. Only servers that answer at least twice are kept; every round's latency
is returned so the pipeline can score steadiness and failure rate.

Each server that passes then opens YouTube, Telegram and X through the same core ([SITES]). The result
says which of the three it reached, so the list can hold only servers that open at least one of them.

Formats Xray cannot run as a plain outbound (Hysteria2, WireGuard, Shadowsocks plugins, mKCP...) are
never published: nothing here could show that they work.
"""
from __future__ import annotations

import base64
import json
import os
import socket
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

PROBE_URL = "https://www.gstatic.com/generate_204"
BATCH = 100
BASE_PORT = 21000
REQUEST_TIMEOUT_SEC = 7
START_WAIT_SEC = 8
# The sites people most need a VPN for, by the short tag the published name carries. Any HTTP answer over
# a TLS connection curl verified counts: a server that cannot reach the site gives no answer at all.
SITES = {
    "YT": "https://www.youtube.com/generate_204",
    "TG": "https://api.telegram.org/",
    "X": "https://x.com/",
}
NETWORKS = {"tcp", "ws", "grpc", "httpupgrade", "xhttp", "splithttp"}


def _first(query: dict, *names: str) -> str:
    for name in names:
        value = (query.get(name) or [""])[0]
        if value:
            return value
    return ""


def _b64(text: str) -> bytes:
    return base64.b64decode(text + "=" * (-len(text) % 4), altchars=b"-_")


def _stream(network: str, security: str, p: dict) -> dict | None:
    """Xray streamSettings for the transport fields in [p], or None when Xray cannot run them as given."""
    if network == "splithttp":
        network = "xhttp"
    if network not in NETWORKS or security not in ("", "none", "tls", "reality"):
        return None
    stream: dict = {"network": network, "security": security or "none"}
    sni = p.get("sni") or p.get("host") or ""
    if security == "tls":
        tls = {"serverName": sni, "fingerprint": p.get("fp") or "chrome"}
        if p.get("alpn"):
            tls["alpn"] = [a for a in p["alpn"].split(",") if a]
        stream["tlsSettings"] = tls
    elif security == "reality":
        stream["realitySettings"] = {
            "serverName": p.get("sni", ""), "fingerprint": p.get("fp") or "chrome",
            "publicKey": p.get("pbk", ""), "shortId": p.get("sid", ""), "spiderX": p.get("spx", ""),
        }
    path = p.get("path") or "/"
    if network == "ws":
        stream["wsSettings"] = {"path": path, "headers": {"Host": p["host"]} if p.get("host") else {}}
    elif network == "grpc":
        stream["grpcSettings"] = {"serviceName": p.get("serviceName", ""), "multiMode": p.get("mode") == "multi"}
    elif network == "httpupgrade":
        stream["httpupgradeSettings"] = {"path": path, "host": p.get("host", "")}
    elif network == "xhttp":
        stream["xhttpSettings"] = {"path": path, "host": p.get("host", ""), "mode": p.get("mode") or "auto"}
    elif network == "tcp" and p.get("headerType") == "http":
        request = {"path": [path]}
        if p.get("host"):
            request["headers"] = {"Host": [p["host"]]}
        stream["tcpSettings"] = {"header": {"type": "http", "request": request}}
    return stream


def outbound(link: str) -> dict | None:
    """The Xray outbound (tag "proxy") for a share link, or None when it cannot be run this way."""
    try:
        if link.startswith("vmess://"):
            data = json.loads(_b64(link[len("vmess://"):].split("#", 1)[0]).decode("utf-8"))
            tls = str(data.get("tls", "")).lower()
            stream = _stream(str(data.get("net", "tcp")).lower() or "tcp", "tls" if tls == "tls" else "none", {
                "sni": str(data.get("sni", "")), "host": str(data.get("host", "")), "path": str(data.get("path", "")),
                "fp": str(data.get("fp", "")), "alpn": str(data.get("alpn", "")),
                "headerType": str(data.get("type", "")), "serviceName": str(data.get("path", "")),
            })
            if stream is None:
                return None
            return {"tag": "proxy", "protocol": "vmess", "streamSettings": stream, "settings": {"vnext": [{
                "address": str(data["add"]).strip(), "port": int(str(data["port"]).strip()),
                "users": [{"id": str(data["id"]), "alterId": int(data.get("aid") or 0), "security": data.get("scy") or "auto"}],
            }]}}
        if link.startswith("ss://"):
            return _shadowsocks(link)
        parts = urlsplit(link)
        query = parse_qs(parts.query)
        p = {k: _first(query, k) for k in ("sni", "host", "path", "fp", "alpn", "flow", "pbk", "sid", "spx",
                                           "serviceName", "mode", "headerType", "encryption", "type", "security")}
        stream = _stream((p["type"] or "tcp").lower(), p["security"].lower(), p)
        host, port = parts.hostname, parts.port
        if stream is None or not host or not port:
            return None
        if parts.scheme == "vless":
            user = {"id": unquote(parts.username or ""), "encryption": p["encryption"] or "none"}
            if p["flow"]:
                user["flow"] = p["flow"]
            settings = {"vnext": [{"address": host, "port": port, "users": [user]}]}
        elif parts.scheme == "trojan":
            settings = {"servers": [{"address": host, "port": port, "password": unquote(parts.username or "")}]}
        else:
            return None
        return {"tag": "proxy", "protocol": parts.scheme, "streamSettings": stream, "settings": settings}
    except Exception:
        return None


def _shadowsocks(link: str) -> dict | None:
    body = link[len("ss://"):].split("#", 1)[0]
    if "plugin=" in body:
        return None
    main, _, query = body.partition("?")
    if "@" in main:
        userinfo, _, hostport = main.rpartition("@")
        if ":" not in unquote(userinfo):
            userinfo = _b64(userinfo).decode("utf-8")
        userinfo = unquote(userinfo)
    else:  # legacy form: the whole "method:password@host:port" is Base64
        decoded = _b64(main).decode("utf-8")
        userinfo, _, hostport = decoded.rpartition("@")
    method, _, password = userinfo.partition(":")
    host, _, port = hostport.rpartition(":")
    if not (method and password and host and port.isdigit()):
        return None
    return {"tag": "proxy", "protocol": "shadowsocks", "settings": {"servers": [{
        "address": host.strip("[]"), "port": int(port), "method": method, "password": password}]}}


def _config(outbounds: list[dict], first_port: int) -> dict:
    """One socks inbound per candidate, each routed to its own outbound."""
    inbounds, rules, tagged = [], [], []
    for i, ob in enumerate(outbounds):
        ob = dict(ob, tag=f"o{i}")
        tagged.append(ob)
        inbounds.append({"tag": f"i{i}", "listen": "127.0.0.1", "port": first_port + i, "protocol": "socks",
                         "settings": {"udp": False}})
        rules.append({"type": "field", "inboundTag": [f"i{i}"], "outboundTag": f"o{i}"})
    return {"log": {"loglevel": "none"}, "inbounds": inbounds, "outbounds": tagged,
            "routing": {"domainStrategy": "AsIs", "rules": rules}}


def _listening(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def _clean_env() -> dict[str, str]:
    """The environment without proxy settings: curl applies NO_PROXY even to an explicit --socks5, so a
    target on that list would be fetched directly and every server would look like it works."""
    return {k: v for k, v in os.environ.items() if "proxy" not in k.lower()}


def _fetch(port: int, url: str, timeout: int) -> float | None:
    """Seconds for a 204 through the socks port, or None."""
    try:
        done = subprocess.run(
            ["curl", "-s", "-o", os.devnull, "-w", "%{http_code} %{time_total}", "--socks5-hostname", f"127.0.0.1:{port}",
             "--max-time", str(timeout), url],
            capture_output=True, text=True, timeout=timeout + 5, env=_clean_env())
        code, _, seconds = done.stdout.strip().partition(" ")
        return float(seconds) if code == "204" else None
    except Exception:
        return None


def _answers(port: int, url: str, timeout: int) -> bool:
    """True when [url] gave any HTTP answer through the socks port (curl checks its certificate)."""
    try:
        done = subprocess.run(
            ["curl", "-s", "-o", os.devnull, "-w", "%{http_code}", "--socks5-hostname", f"127.0.0.1:{port}",
             "--max-time", str(timeout), url],
            capture_output=True, text=True, timeout=timeout + 5, env=_clean_env())
        code = done.stdout.strip()
        return code.isdigit() and 100 <= int(code) < 500
    except Exception:
        return False


ROUNDS = 3


class Result(tuple):
    """(seconds, sites reached) like before, plus every round's latency: [samples] has one entry per
    successful round and [attempts] counts every round tried, so the failure rate is measured."""

    def __new__(cls, seconds: float, sites: frozenset, samples: tuple = (), attempts: int = 0):
        self = super().__new__(cls, (seconds, sites))
        self.samples = tuple(samples)
        self.attempts = attempts or len(samples)
        return self

    @property
    def successes(self) -> int:
        return len(self.samples)


def _run_batch(xray: str, outbounds: list[dict], url: str, timeout: int, first_port: int,
               sites: dict[str, str] | None = None) -> list[Result | None]:
    """(latency in seconds, sites reached) per outbound, None = no traffic carried; raises RuntimeError
    if Xray rejects the batch."""
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "verify.json"
        path.write_text(json.dumps(_config(outbounds, first_port)))
        core = subprocess.Popen([xray, "run", "-c", str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.time() + START_WAIT_SEC
            while not all(_listening(first_port + i) for i in (0, len(outbounds) - 1)):
                if core.poll() is not None:
                    raise RuntimeError("Xray refused the configuration")
                if time.time() > deadline:
                    raise RuntimeError("Xray did not start")
                time.sleep(0.1)
            with ThreadPoolExecutor(max_workers=len(outbounds)) as pool:
                rounds = [list(pool.map(lambda i: _fetch(first_port + i, url, timeout), range(len(outbounds))))]
                # More rounds on the servers that answered once: one lucky answer is not "working",
                # and the spread of the answers shows how steady a server is.
                for _ in range(ROUNDS - 1):
                    rounds.append(list(pool.map(
                        lambda i: _fetch(first_port + i, url, timeout) if rounds[0][i] is not None else None,
                        range(len(outbounds)))))
                attempts = [ROUNDS if rounds[0][i] is not None else 1 for i in range(len(outbounds))]
                samples = [[r[i] for r in rounds if r[i] is not None] for i in range(len(outbounds))]
                passing = [len(s) >= 2 for s in samples]
                jobs = [(i, tag, site) for i, ok in enumerate(passing) if ok for tag, site in (sites or {}).items()]
                reached = list(pool.map(lambda job: _answers(first_port + job[0], job[2], timeout), jobs))
            found: dict[int, set[str]] = {}
            for (i, tag, _), ok in zip(jobs, reached):
                if ok:
                    found.setdefault(i, set()).add(tag)
            return [Result(sum(samples[i]) / len(samples[i]), frozenset(found.get(i, ())), tuple(samples[i]), attempts[i])
                    if passing[i] else None for i in range(len(outbounds))]
        finally:
            core.terminate()
            try:
                core.wait(timeout=5)
            except subprocess.TimeoutExpired:
                core.kill()


def _measure(xray: str, outbounds: list[dict], url: str, timeout: int, first_port: int,
             sites: dict[str, str] | None = None) -> list[Result | None]:
    try:
        return _run_batch(xray, outbounds, url, timeout, first_port, sites)
    except RuntimeError:
        if len(outbounds) == 1:
            return [None]
        # One bad entry makes Xray refuse the whole batch: split it so only that entry is lost.
        half = len(outbounds) // 2
        return _measure(xray, outbounds[:half], url, timeout, first_port, sites) + \
            _measure(xray, outbounds[half:], url, timeout, first_port + half, sites)


def probe(links: list[str], xray: str, url: str = PROBE_URL, timeout: int = REQUEST_TIMEOUT_SEC,
          budget_sec: float = 900, first_port: int = BASE_PORT, sites: dict[str, str] | None = None) -> dict[str, Result]:
    """For each link that carried traffic in at least 2 of [ROUNDS] rounds: a [Result] with the mean seconds
    to fetch [url], the [sites] tags it reached and every round's latency. Others are absent."""
    sites = SITES if sites is None else sites
    runnable = [(link, ob) for link in links if (ob := outbound(link)) is not None]
    result: dict[str, Result] = {}
    started = time.time()
    for start in range(0, len(runnable), BATCH):
        if time.time() - started > budget_sec:
            break  # unverified candidates are dropped, never published
        chunk = runnable[start:start + BATCH]
        for (link, _), found in zip(chunk, _measure(xray, [ob for _, ob in chunk], url, timeout, first_port, sites)):
            if found is not None:
                result[link] = found
    return result


def latencies(links: list[str], xray: str, url: str = PROBE_URL, timeout: int = REQUEST_TIMEOUT_SEC,
              budget_sec: float = 600, first_port: int = BASE_PORT) -> dict[str, float]:
    """Seconds to fetch [url] through each link that carried traffic in at least 2 rounds. Others are absent."""
    return {link: seconds for link, (seconds, _) in
            probe(links, xray, url, timeout, budget_sec, first_port, sites={}).items()}
