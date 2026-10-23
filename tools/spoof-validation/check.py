#!/usr/bin/env python3
"""Exercise pinned raw carriers between isolated Linux network namespaces; no internet scan."""
import argparse
import hashlib
import os
import pathlib
import subprocess
import tempfile
import time
import urllib.request

VERSION = "v3.1.0-beta.0"
SHA = "4ba5597568607e864fb6d5ba4f13bac91a50456da5696d99463d6375ce2a3697"


def call(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", default="")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("Run in a disposable Linux CI runner with root network namespace privileges")
    with tempfile.TemporaryDirectory() as td:
        binary = pathlib.Path(td) / "spoof"
        if args.binary:
            data = pathlib.Path(args.binary).read_bytes()
        else:
            with urllib.request.urlopen(f"https://github.com/ParsaKSH/spoof-tunnel/releases/download/{VERSION}/spoof-linux-amd64", timeout=60) as r:
                data = r.read(64 * 1024 * 1024)
        assert hashlib.sha256(data).hexdigest() == SHA, "Untrusted carrier binary"
        binary.write_bytes(data)
        binary.chmod(0o755)
        prefix = f"mx{os.getpid()}"
        left, right = prefix + "a", prefix + "b"
        made, processes = [], []
        try:
            for ns in (left, right):
                call("ip", "netns", "add", ns)
                made.append(ns)
            call("ip", "link", "add", "mxa", "type", "veth", "peer", "name", "mxb")
            for ns, iface, ip in ((left, "mxa", "10.203.0.1"), (right, "mxb", "10.203.0.2")):
                call("ip", "link", "set", iface, "netns", ns)
                call("ip", "-n", ns, "addr", "add", ip + "/24", "dev", iface)
                call("ip", "-n", ns, "link", "set", iface, "up")
                call("ip", "-n", ns, "link", "set", "lo", "up")
                call("ip", "netns", "exec", ns, "sysctl", "-qw", "net.ipv4.conf.all.rp_filter=0")
                call("ip", "netns", "exec", ns, "sysctl", "-qw", f"net.ipv4.conf.{iface}.rp_filter=0")
            # Run the exact app-generated scanner against every supported raw carrier in both directions.
            kotlin = (pathlib.Path(__file__).resolve().parents[2] / "app/src/main/java/com/example/panels/servers/tunnel/SpoofCarrier.kt").read_text()
            probe = kotlin.split('val PROBE_PYTHON = """', 1)[1].split('""".trimIndent()', 1)[0]
            probe_file = pathlib.Path(td) / "probe.py"
            probe_file.write_text(probe)
            for protocol in ("tcp", "udp", "icmp", "icmpv6"):
                for src_ns, dst_ns, src_ip, dst_ip in ((left, right, "10.203.0.1", "10.203.0.2"), (right, left, "10.203.0.2", "10.203.0.1")):
                    nonce = os.urandom(32).hex()
                    source = "198.51.100.1"
                    logfile = pathlib.Path(td) / "scan.log"
                    with logfile.open("w+") as log:
                        receiver = subprocess.Popen(["ip", "netns", "exec", dst_ns, "python3", "-u", str(probe_file), str(binary),
                            "receiver", src_ip, "19444", protocol, source, nonce], stdout=log, stderr=log)
                        try:
                            deadline = time.monotonic() + 5
                            while "[carrier-receiver] ready" not in logfile.read_text():
                                assert receiver.poll() is None and time.monotonic() < deadline, logfile.read_text()
                                time.sleep(0.05)
                            call("ip", "netns", "exec", src_ns, "python3", "-u", str(probe_file), str(binary),
                                 "sender", dst_ip, "19444", protocol, source, nonce)
                            assert receiver.wait(timeout=18) == 0, logfile.read_text()
                            assert f"{source} 4/4 0.0%" in logfile.read_text(), logfile.read_text()
                            print(f"PASS: {protocol} source scan {src_ns} -> {dst_ns}, exact nonce receipt", flush=True)
                        finally:
                            if receiver.poll() is None:
                                receiver.terminate(); receiver.wait(timeout=4)
            echo = "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',19520))\nwhile True:\n d,a=s.recvfrom(4096);s.sendto(d[::-1],a)"
            processes.append(subprocess.Popen(["ip", "netns", "exec", right, "python3", "-u", "-c", echo]))
            for up, down in (("tcp", "tcp"), ("udp", "udp"), ("icmp", "icmp"), ("icmpv6", "icmpv6"), ("tcp", "icmp")):
                pair = []
                log = open(pathlib.Path(td) / f"{up}-{down}.log", "w+")
                try:
                    commands = [
                        (right, "remote", "--forward", "127.0.0.1:19520", "--client-ip", "10.203.0.1", "--client-port", "19443", "--listen-port", "19443", "--spoof-ip", "198.51.100.2", "--send-transport", down, "--recv-transport", up),
                        (left, "local", "--listen", "127.0.0.1:19500", "--remote", "10.203.0.2", "--remote-port", "19443", "--recv-port", "19443", "--spoof-ip", "198.51.100.1", "--send-transport", up, "--recv-transport", down),
                    ]
                    for ns, *cmd in commands:
                        p = subprocess.Popen(["ip", "netns", "exec", ns, str(binary), *cmd], stdout=log, stderr=log)
                        pair.append(p)
                    time.sleep(1)
                    assert all(p.poll() is None for p in pair), "Carrier failed to start"
                    probe = """import socket,os
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(3)
for size in (32,256,1024,1280):
 data=os.urandom(size);s.sendto(data,('127.0.0.1',19500));got,_=s.recvfrom(4096);assert got==data[::-1], 'Payload mismatch'
"""
                    call("ip", "netns", "exec", left, "python3", "-c", probe)
                    print(f"PASS: {up}/{down} bidirectional raw carrier, 4 payload sizes", flush=True)
                except Exception:
                    log.flush(); log.seek(0); print(log.read())
                    raise
                finally:
                    for p in pair:
                        p.terminate()
                    for p in pair:
                        try: p.wait(timeout=3)
                        except subprocess.TimeoutExpired: p.kill(); p.wait()
                    log.close()
        finally:
            for p in processes:
                p.terminate(); p.wait(timeout=3)
            for ns in reversed(made):
                subprocess.run(["ip", "netns", "delete", ns], check=False)


if __name__ == "__main__":
    main()
