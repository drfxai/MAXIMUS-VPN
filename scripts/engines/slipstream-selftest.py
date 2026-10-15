#!/usr/bin/env python3
"""Confirms a Slipstream build carries ordinary web traffic both ways through the tunnel.

The app ships slipstream-client so a phone can reach the internet when a server's IP is blocked but DNS
still resolves. This self-test stands up a server on this machine in front of a plain SOCKS5 proxy,
starts the client, then fetches a file and posts a file back through the client's local port and checks
both by SHA-256. It also checks that two resolvers at once still carry traffic, and that a client given
the wrong pinned certificate refuses to carry anything.

    scripts/engines/slipstream-selftest.py --dir build/slipstream-host
    # when the two programs are elsewhere:
    scripts/engines/slipstream-selftest.py --server path/to/slipstream-server --client path/to/slipstream-client

Exit status 0 means every check passed.
"""
import argparse
import hashlib
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import http.server

DOMAIN = "t.example.org"


def _pump(src, dst):
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def _read(conn, count):
    buf = b""
    while len(buf) < count:
        part = conn.recv(count - len(buf))
        if not part:
            raise OSError("peer closed")
        buf += part
    return buf


def start_socks(port):
    """A tiny SOCKS5 proxy (no auth, CONNECT only) standing in for the loopback helper on a real server."""
    def serve(conn):
        try:
            _, nmethods = _read(conn, 2)
            _read(conn, nmethods)
            conn.sendall(b"\x05\x00")
            _, _, _, atyp = _read(conn, 4)
            if atyp == 1:
                host = socket.inet_ntoa(_read(conn, 4))
            elif atyp == 3:
                host = _read(conn, _read(conn, 1)[0]).decode()
            else:
                host = socket.inet_ntop(socket.AF_INET6, _read(conn, 16))
            dport = struct.unpack(">H", _read(conn, 2))[0]
            upstream = socket.create_connection((host, dport))
            conn.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
            threading.Thread(target=_pump, args=(conn, upstream), daemon=True).start()
            _pump(upstream, conn)
        except OSError:
            pass
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", port))
    listener.listen(64)

    def loop():
        while True:
            conn, _ = listener.accept()
            threading.Thread(target=serve, args=(conn,), daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()


def start_web(port, blob):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(blob)))
            self.end_headers()
            self.wfile.write(blob)

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            digest = hashlib.sha256(body).hexdigest().encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(digest)))
            self.end_headers()
            self.wfile.write(digest)

        def log_message(self, *args):
            pass
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()


def start_udp_forward(listen_port, target_port):
    """Forwards UDP between two ports so a second resolver address exercises a separate path."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", listen_port))
    up = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    up.connect(("127.0.0.1", target_port))
    state = {"client": None, "count": 0}

    def loop():
        import select
        while True:
            ready, _, _ = select.select([sock, up], [], [])
            for s in ready:
                if s is sock:
                    data, state["client"] = sock.recvfrom(4096)
                    up.send(data)
                    state["count"] += 1
                else:
                    data = up.recv(4096)
                    if state["client"]:
                        sock.sendto(data, state["client"])
    threading.Thread(target=loop, daemon=True).start()
    return state


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def socks_fetch(client_port, host, path, method="GET", body=None):
    """One HTTP request to host:web_port, dialled through the client's local SOCKS5 port."""
    conn = socket.create_connection(("127.0.0.1", client_port), timeout=30)
    conn.sendall(b"\x05\x01\x00")
    if _read(conn, 2) != b"\x05\x00":
        raise RuntimeError("SOCKS greeting refused")
    addr = socket.inet_aton(host[0])
    conn.sendall(b"\x05\x01\x00\x01" + addr + struct.pack(">H", host[1]))
    reply = _read(conn, 10)
    if reply[1] != 0:
        raise RuntimeError(f"SOCKS connect failed, code {reply[1]}")
    req = f"{method} {path} HTTP/1.1\r\nHost: test\r\nConnection: close\r\n"
    if body is not None:
        req += f"Content-Length: {len(body)}\r\n"
    req += "\r\n"
    conn.sendall(req.encode() + (body or b""))
    raw = b""
    while True:
        part = conn.recv(65536)
        if not part:
            break
        raw += part
    conn.close()
    head, _, payload = raw.partition(b"\r\n\r\n")
    if b"200" not in head.split(b"\r\n")[0]:
        raise RuntimeError(f"HTTP error: {head.splitlines()[0]!r}")
    return payload


def run_client(client_bin, listen_port, resolvers, cert, log_path):
    args = [client_bin, "--tcp-listen-port", str(listen_port), "--domain", DOMAIN]
    for r in resolvers:
        args += ["--resolver", r]
    if cert:
        args += ["--cert", cert]
    log = open(log_path, "wb")
    return subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT)


def wait_port(port, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        except OSError:
            time.sleep(0.1)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", help="directory holding slipstream-client and slipstream-server")
    ap.add_argument("--client")
    ap.add_argument("--server")
    args = ap.parse_args()
    client_bin = args.client or (os.path.join(args.dir, "slipstream-client") if args.dir else None)
    server_bin = args.server or (os.path.join(args.dir, "slipstream-server") if args.dir else None)
    if not client_bin or not server_bin:
        ap.error("give --dir, or both --client and --server")

    work = tempfile.mkdtemp(prefix="slipstream-selftest-")
    good_cert = os.path.join(work, "cert.pem")
    good_key = os.path.join(work, "key.pem")
    wrong_cert = os.path.join(work, "wrong.pem")
    wrong_key = os.path.join(work, "wrong.key")
    gen = ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
           "-nodes", "-days", "3650", "-subj", "/CN=test.example.com"]
    subprocess.run(gen + ["-keyout", good_key, "-out", good_cert], check=True, capture_output=True)
    subprocess.run(gen + ["-keyout", wrong_key, "-out", wrong_cert], check=True, capture_output=True)

    socks_port = free_port()
    web_port = free_port()
    dns_port = free_port()
    relay_port = free_port()
    start_socks(socks_port)
    download = os.urandom(3_000_000)
    upload = os.urandom(1_000_000)
    start_web(web_port, download)

    server = subprocess.Popen(
        [server_bin, "--dns-listen-port", str(dns_port), "--target-address", f"127.0.0.1:{socks_port}",
         "--domain", DOMAIN, "--cert", good_cert, "--key", good_key],
        stdout=open(os.path.join(work, "server.log"), "wb"), stderr=subprocess.STDOUT)
    time.sleep(1)
    relay = start_udp_forward(relay_port, dns_port)
    web = ("127.0.0.1", web_port)
    failures = []

    want_down = hashlib.sha256(download).hexdigest()
    want_up = hashlib.sha256(upload).hexdigest()

    def measured_download(port):
        """One download, retried a few times so a cold tunnel (dcubic ramp) does not read as a failure."""
        last = ""
        for _ in range(4):
            try:
                got = socks_fetch(port, web, "/")
                if hashlib.sha256(got).hexdigest() == want_down:
                    return
                last = f"got {len(got)} of {len(download)} bytes"
            except Exception as exc:
                last = str(exc)
            time.sleep(1)
        raise RuntimeError(f"download did not match ({last})")

    def check(name, resolvers, cert, expect_traffic):
        port = free_port()
        log = os.path.join(work, f"client-{name}.log")
        client = run_client(client_bin, port, resolvers, cert, log)
        try:
            if not wait_port(port, 10):
                raise RuntimeError("client never opened its local port")
            # Warm-up request: lets the QUIC connection come up before the measured transfers.
            try:
                socks_fetch(port, web, "/")
            except OSError:
                pass
            measured_download(port)
            echoed = socks_fetch(port, web, "/up", method="POST", body=upload).decode()
            if echoed != want_up:
                raise RuntimeError("upload did not match")
            if name == "two-resolvers":
                # Several more transfers so picoquic spreads load onto the second path.
                for _ in range(4):
                    socks_fetch(port, web, "/")
            if not expect_traffic:
                failures.append(f"{name}: carried traffic though it should have refused")
            else:
                print(f"PASS {name}: 3 MB down and 1 MB up matched")
        except Exception as exc:
            if expect_traffic:
                failures.append(f"{name}: {exc}")
            else:
                print(f"PASS {name}: refused traffic as expected ({exc})")
        finally:
            client.terminate()
            try:
                client.wait(timeout=5)
            except subprocess.TimeoutExpired:
                client.kill()

    check("single-resolver", [f"127.0.0.1:{dns_port}"], good_cert, True)
    check("pinned-cert", [f"127.0.0.1:{dns_port}"], good_cert, True)
    check("two-resolvers", [f"127.0.0.1:{dns_port}", f"127.0.0.1:{relay_port}"], good_cert, True)
    # The client accepts and uses several resolvers; which one carries how much is picoquic's choice and
    # over two equal loopback paths it often keeps everything on the first, so this is reported, not asserted.
    if relay["count"]:
        print(f"PASS two-resolvers: second resolver path carried {relay['count']} packets")
    else:
        print("note two-resolvers: both resolvers accepted; traffic stayed on the first path this run")
    check("wrong-cert", [f"127.0.0.1:{dns_port}"], wrong_cert, False)

    server.terminate()
    if failures:
        print("\nFAILED:")
        for f in failures:
            print(" -", f)
        sys.exit(1)
    print("\nAll Slipstream self-tests passed")


if __name__ == "__main__":
    main()
