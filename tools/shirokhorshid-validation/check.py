#!/usr/bin/env python3
"""Runs a host build of the pinned Shir o Khorshid ConsoleClient with configs shaped like ShirokhorshidSidecar's.

    python3 tools/shirokhorshid-validation/check.py --binary PATH [--tunnel]

Each mode (Auto with CDN fronting and user edges, Conduit with rejected CIDRs, Direct with CDN fronting) must be
accepted: the program opens its SOCKS port and reports no configuration error. With --tunnel, a CDN-fronting-only
start must also bring a tunnel up within the time limit (needs the internet; GitHub runners have it).
The field names and protocol lists mirror ShirokhorshidSidecar.applyOptions (its unit tests pin the same shape).
"""
import argparse
import json
import os
import pathlib
import queue
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
PUBLIC = ROOT / "app/src/main/assets/psiphon/public-config.json"

CONDUIT = ["INPROXY-WEBRTC-SSH", "INPROXY-WEBRTC-OSSH", "INPROXY-WEBRTC-TLS-OSSH",
           "INPROXY-WEBRTC-UNFRONTED-MEEK-OSSH", "INPROXY-WEBRTC-UNFRONTED-MEEK-HTTPS-OSSH",
           "INPROXY-WEBRTC-UNFRONTED-MEEK-SESSION-TICKET-OSSH", "INPROXY-WEBRTC-FRONTED-MEEK-OSSH",
           "INPROXY-WEBRTC-FRONTED-MEEK-HTTP-OSSH", "INPROXY-WEBRTC-QUIC-OSSH",
           "INPROXY-WEBRTC-FRONTED-MEEK-QUIC-OSSH", "INPROXY-WEBRTC-SHADOWSOCKS-OSSH"]
CDN = ["FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH", "FRONTED-MEEK-CDN-QUIC-OSSH"]
DIRECT = ["SSH", "OSSH", "TLS-OSSH", "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
          "UNFRONTED-MEEK-SESSION-TICKET-OSSH", "QUIC-OSSH", "SHADOWSOCKS-OSSH", "FRONTED-MEEK-OSSH",
          "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-QUIC-OSSH"]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def base(data_dir):
    config = json.loads(PUBLIC.read_text())
    config.update({
        "LocalSocksProxyPort": free_port(), "DisableLocalHTTPProxy": True, "DataRootDirectory": str(data_dir),
        "EnableUpgradeDownload": False, "SplitTunnelOwnRegion": False, "ClientPlatform": "Android",
        "EmitDiagnosticNetworkParameters": False, "EmitSensitiveNetworkParameters": False,
        "EmitDiagnosticNotices": True, "DNSResolverAlternateServers": ["1.1.1.1", "8.8.8.8", "9.9.9.9"],
    })
    for key in ("FrontedMeekCDNScanUseBuiltInSpec", "FrontedMeekCDNScanSpec", "InproxyRejectProxyCIDRs"):
        config.pop(key, None)
    return config


def modes(data_dir):
    auto = base(data_dir / "auto")
    auto.update({"FrontedMeekCDNScanUseBuiltInSpec": True,
                 "FrontedMeekCDNScanSpec": {"IPCandidates": ["23.215.0.206", "23.215.0.0/24"]},
                 "InproxyRejectProxyCIDRs": ["2.144.0.0/14", "5.22.0.0/17"]})
    conduit = base(data_dir / "conduit")
    conduit.update({"LimitTunnelProtocols": CONDUIT, "InproxyRejectProxyCIDRs": ["2.144.0.0/14", "5.22.0.0/17"]})
    direct = base(data_dir / "direct")
    direct.update({"FrontedMeekCDNScanUseBuiltInSpec": True, "LimitTunnelProtocols": DIRECT + CDN,
                   "DisableTactics": True})
    return {"auto": auto, "conduit": conduit, "direct": direct}


def run(binary, name, config, seconds, until=None):
    data = pathlib.Path(config["DataRootDirectory"])
    data.mkdir(parents=True, exist_ok=True)
    path = data.parent / f"{name}.json"
    path.write_text(json.dumps(config))
    proc = subprocess.Popen([binary, "-config", str(path), "-dataRootDirectory", str(data), "-formatNotices"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l.rstrip("\n")) for l in proc.stdout], daemon=True).start()
    seen, deadline = [], time.time() + seconds
    try:
        while time.time() < deadline:
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                if proc.poll() is not None:
                    break
                continue
            seen.append(line)
            if until and until(line):
                break
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
    while not lines.empty():
        seen.append(lines.get())
    return seen, proc.returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--tunnel", action="store_true")
    args = parser.parse_args()
    failed = False
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        for name, config in modes(root).items():
            lines, _ = run(args.binary, name, config, 25,
                           until=lambda l: "ListeningSocksProxyPort" in l)
            listening = any("ListeningSocksProxyPort" in l for l in lines)
            errors = [l for l in lines if "error loading configuration" in l or "invalid" in l.lower() and "Error" in l]
            print(f"{name}: socks={'yes' if listening else 'no'} config_errors={len(errors)}")
            for l in errors[:3]:
                print("   ", l[:300])
            if not listening or errors:
                failed = True
                for l in lines[-15:]:
                    print("   |", l[:300])
        if args.tunnel and not failed:
            cdn = base(root / "cdn-only")
            cdn.update({"FrontedMeekCDNScanUseBuiltInSpec": True, "LimitTunnelProtocols": CDN[:2], "DisableTactics": True})
            lines, _ = run(args.binary, "cdn-only", cdn, 150,
                           until=lambda l: "Tunnels" in l and '"count":1' in l)
            up = any("Tunnels" in l and '"count":1' in l for l in lines)
            found = [l for l in lines if "cdn fronting scan found" in l]
            active = [l for l in lines if "ActiveTunnel" in l]
            print(f"cdn-only tunnel: {'up' if up else 'not up'}; {active[-1][-120:] if active else ''}")
            for l in found[:2]:
                print("   ", l[-120:])
            if not up:
                failed = True
                for l in lines[-20:]:
                    print("   |", l[:300])
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
