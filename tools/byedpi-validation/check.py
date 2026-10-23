#!/usr/bin/env python3
"""A TTL-expiring fake TLS ClientHello must be visible at the router but not corrupt server TLS."""
import os
import pathlib
import subprocess
import sys
import tempfile
import time


def call(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=40, **kwargs)


def main():
    binary = str(pathlib.Path(sys.argv[1]).resolve())
    prefix = f"mf{os.getpid()}"
    client, router, server = [prefix + x for x in ('c', 'r', 's')]
    made, processes = [], []
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        try:
            for ns in (client, router, server):
                call('ip', 'netns', 'add', ns); made.append(ns)
                call('ip', '-n', ns, 'link', 'set', 'lo', 'up')
            for a, b, an, bn, aip, bip in (
                ('mfc', 'mfr0', client, router, '10.221.0.2', '10.221.0.1'),
                ('mfr1', 'mfs', router, server, '10.221.1.1', '10.221.1.2')):
                call('ip', 'link', 'add', a, 'type', 'veth', 'peer', 'name', b)
                for iface, ns, ip in ((a, an, aip), (b, bn, bip)):
                    call('ip', 'link', 'set', iface, 'netns', ns)
                    call('ip', '-n', ns, 'addr', 'add', ip + '/24', 'dev', iface)
                    call('ip', '-n', ns, 'link', 'set', iface, 'up')
            call('ip', '-n', client, 'route', 'add', 'default', 'via', '10.221.0.1')
            call('ip', '-n', server, 'route', 'add', 'default', 'via', '10.221.1.1')
            call('ip', 'netns', 'exec', router, 'sysctl', '-qw', 'net.ipv4.ip_forward=1')
            call('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=actual.test',
                 '-addext', 'subjectAltName=DNS:actual.test', '-keyout', str(root/'key.pem'), '-out', str(root/'cert.pem'))
            source = '''import http.server,ssl,sys
s=http.server.HTTPServer(('10.221.1.2',18443),http.server.SimpleHTTPRequestHandler)
c=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);c.load_cert_chain(sys.argv[1],sys.argv[2]);s.socket=c.wrap_socket(s.socket,server_side=True);s.serve_forever()
'''
            processes.append(subprocess.Popen(['ip','netns','exec',server,'python3','-u','-c',source,str(root/'cert.pem'),str(root/'key.pem')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            capture = subprocess.Popen(['ip','netns','exec',router,'tcpdump','-U','-i','mfr0','-w',str(root/'capture.pcap'),'tcp','port','18443'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            processes.append(capture)
            env = {**os.environ, 'MAXIMUS_PEER_IP':'10.221.1.2', 'MAXIMUS_PEER_PORT':'18443'}
            native = subprocess.Popen(['ip','netns','exec',client,binary,'--ip','127.0.0.1','--port','19080','--conn-ip','0.0.0.0',
                '--no-domain','--no-udp','--fake','-1','--ttl','1','--fake-sni','decoy.test','--timeout','8'],env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            processes.append(native)
            time.sleep(1)
            assert native.poll() is None, native.stderr.read().decode()
            command = ['ip','netns','exec',client,'curl','--noproxy','','--socks5','127.0.0.1:19080','--max-time','20',
                '--cacert',str(root/'cert.pem'),'--resolve','actual.test:18443:10.221.1.2','https://actual.test:18443/']
            response = call(*command)
            assert 'Directory listing' in response.stdout
            # A valid TLS exchange and application response must coexist with the decoy on the wire.
            capture.terminate(); capture.wait(timeout=5)
            assert b'decoy.test' in (root/'capture.pcap').read_bytes(), 'No fake SNI observed on wire'
            assert b'actual.test' in (root/'capture.pcap').read_bytes(), 'Original TLS handshake missing'
            print('PASS: native fake SNI on wire; real certificate-verified TLS and HTTP intact', flush=True)
            wrong = subprocess.run(command[:-1] + ['https://wrong.test:18443/','--resolve','wrong.test:18443:10.221.1.2'], capture_output=True, timeout=30)
            assert wrong.returncode != 0, 'Wrong certificate name accepted'
            print('PASS: wrong certificate name rejected', flush=True)
        finally:
            for p in reversed(processes):
                if p.poll() is None:
                    p.terminate()
                    try: p.wait(timeout=3)
                    except subprocess.TimeoutExpired: p.kill(); p.wait()
            for ns in reversed(made):
                subprocess.run(['ip','netns','delete',ns], check=False)


if __name__ == '__main__':
    main()
