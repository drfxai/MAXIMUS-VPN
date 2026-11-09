#!/usr/bin/env python3
"""Validate production-generated direct configs and native authenticated data transfer, entirely on loopback."""
import argparse, contextlib, http.server, importlib.util, json, os, pathlib, secrets, shutil, socket, subprocess, tempfile, threading, time
parser=argparse.ArgumentParser()
parser.add_argument('--fixtures', type=pathlib.Path, required=True)
args=parser.parse_args()
repo=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('traffic', repo/'tools/singbox-validation/check.py')
traffic=importlib.util.module_from_spec(spec);spec.loader.exec_module(traffic)
rev='f9903959b9ae47538f5c7100f6416fb9fbcd2317'
with tempfile.TemporaryDirectory(prefix='direct-native-') as tmp:
    root=pathlib.Path(tmp); source=root/'source'; source.mkdir()
    subprocess.run(['git','init','-q',str(source)],check=True)
    subprocess.run(['git','-C',str(source),'fetch','--depth=1','https://github.com/patterniha/Xray-core.git',rev],check=True)
    subprocess.run(['git','-C',str(source),'checkout','--detach','FETCH_HEAD'],check=True)
    assert subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()==rev
    binary=root/'xray'
    env=dict(os.environ,GOTOOLCHAIN='go1.27.0',GOOS='linux',GOARCH='amd64',CGO_ENABLED='0')
    validation=source/'maximusvalidation';validation.mkdir()
    for test in (repo/'tools/serverless-validation').glob('*_test.go'):
        shutil.copyfile(test, validation/test.name)
    test_env=dict(env, MAXIMUS_SERVERLESS_FIXTURES=str(args.fixtures.resolve()))
    subprocess.run(['go','test','-count=1','-timeout=90s','./maximusvalidation'],cwd=source,env=test_env,check=True)
    subprocess.run(['go','build','-mod=readonly','-trimpath','-ldflags=-checklinkname=0','-o',str(binary),'./main'],cwd=source,env=env,check=True)
    requests_seen=[]
    class Echo(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            requests_seen.append(self.path)
            import hashlib
            n=int(self.headers.get('Content-Length','0'));assert 0<n<=65536
            data=self.rfile.read(n);body=hashlib.sha256(data).hexdigest().encode()+b':'+data
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def log_message(self,*args): pass
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Echo)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    try:
        fixtures=sorted(args.fixtures.glob('*.json'));assert len(fixtures)==3
        for path in fixtures:
            config=json.loads(path.read_text());inbound=config['inbounds'][0]
            port=traffic.free_port();inbound['port']=port;auth=inbound['settings']['accounts'][0]
            active=root/'config.json';active.write_text(json.dumps(config))
            subprocess.run([str(binary),'run','-test','-c',str(active)],check=True,capture_output=True)
            p=subprocess.Popen([str(binary),'run','-c',str(active)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                deadline=time.monotonic()+10
                while True:
                    assert p.poll() is None
                    try:
                        with socket.create_connection(('127.0.0.1',port),timeout=.2):break
                    except OSError:
                        assert time.monotonic()<deadline;time.sleep(.05)
                traffic.request(port,server.server_port,auth['user'],auth['pass'],secrets.token_bytes(65536))
                with socket.create_connection(('127.0.0.1',port),timeout=2) as client:
                    client.sendall(b'\x05\x01\x00');assert traffic.read_exact(client,2)==b'\x05\xff'
                try:traffic.request(port,server.server_port,auth['user'],'wrong',b'must-not-pass')
                except (AssertionError,OSError):pass
                else:raise AssertionError('Native SOCKS auth bypass')
                print('PASS',path.stem,'native schema, SOCKS auth rejection and 64 KiB upload/download')
            finally:
                p.terminate()
                try:p.wait(timeout=5)
                except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
        # Actual configs exported by Kotlin: a selected exit that cannot be reached
        # must fail instead of bypassing it through a pinned `direct` outbound.
        exit_fixtures=sorted((args.fixtures/'exit').glob('*.json'));assert len(exit_fixtures)==3
        for path in exit_fixtures:
            config=json.loads(path.read_text());inbound=config['inbounds'][0]
            port=traffic.free_port();inbound['port']=port;auth=inbound['settings']['accounts'][0]
            # Hold a non-listening socket so the selected SOCKS exit cannot be reached
            # or accidentally occupied by another process during the assertion.
            with socket.socket() as unavailable:
                unavailable.bind(('127.0.0.1',0))
                config['outbounds'][-1]['settings']['servers'][0]['port']=unavailable.getsockname()[1]
                active=root/'exit-config.json';active.write_text(json.dumps(config))
                subprocess.run([str(binary),'run','-test','-c',str(active)],check=True,capture_output=True)
                p=subprocess.Popen([str(binary),'run','-c',str(active)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                try:
                    deadline=time.monotonic()+10
                    while True:
                        assert p.poll() is None
                        try:
                            with socket.create_connection(('127.0.0.1',port),timeout=.2):break
                        except OSError:
                            assert time.monotonic()<deadline;time.sleep(.05)
                    before=len(requests_seen)
                    try:traffic.request(port,server.server_port,auth['user'],auth['pass'],b'exit-must-not-be-bypassed')
                    except (AssertionError,OSError,IndexError):pass
                    else:raise AssertionError('Selected serverless exit was bypassed')
                    assert len(requests_seen)==before, 'Traffic reached the target despite an unavailable selected exit'
                    print('PASS',path.stem,'selected exit enforced by native direct outbounds')
                finally:
                    p.terminate()
                    try:p.wait(timeout=5)
                    except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
    finally:server.shutdown();server.server_close()
