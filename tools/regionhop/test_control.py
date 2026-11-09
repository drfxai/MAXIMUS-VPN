import importlib.util,json,pathlib,tempfile,threading,unittest,urllib.parse
from http.server import BaseHTTPRequestHandler,HTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('control',ROOT/'app/src/main/assets/regionhop/control.py')
control=importlib.util.module_from_spec(spec);spec.loader.exec_module(control)
class RegionHopControlTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();control.ROOT=pathlib.Path(self.tmp.name);control.PANEL=control.ROOT;(control.PANEL/'VERSION').write_text('1.27.0');self.calls=[];owner=self
  class Panel(BaseHTTPRequestHandler):
   def log_message(self,*a):pass
   def do_POST(self):
    data=urllib.parse.parse_qs(self.rfile.read(int(self.headers.get('Content-Length',0))).decode());owner.calls.append((self.path,data))
    if self.path.endswith('/login'):
     self.send_response(303);self.send_header('Set-Cookie','session=test-only; Path=/');self.send_header('Location',owner.base+'/');self.end_headers();return
    if self.headers.get('Cookie')!='session=test-only':self.send_response(401);self.end_headers();return
    self.send_response(200);self.end_headers();self.wfile.write(b'{"ok":true}')
   def do_GET(self):
    self.send_response(200);self.end_headers();self.wfile.write(b'{}')
  self.http=HTTPServer(('127.0.0.1',0),Panel);self.prefix='/'+'a'*32
  self.base=f'http://127.0.0.1:{self.http.server_port}{self.prefix}'
  (control.ROOT/'control.json').write_text(json.dumps(dict(password='test-only',port=self.http.server_port,prefix=self.prefix)))
  self.old=control.locations;control.locations=lambda:[]
  self.thread=threading.Thread(target=self.http.serve_forever,daemon=True);self.thread.start()
 def tearDown(self):
  control.locations=self.old;self.http.shutdown();self.http.server_close();self.tmp.cleanup()
 def test_add_uses_authenticated_panel_and_validated_country(self):
  result=control.invoke('add',{'region':'DE'})
  self.assertTrue(result['installed']);self.assertIn((self.prefix+'/tunnels/add',{'name':['de'],'region':['DE']}),self.calls)
 def test_invalid_country_never_adds_location(self):
  with self.assertRaises(AssertionError):control.invoke('add',{'region':'XX'})
  self.assertFalse(any('/tunnels/add' in p for p,_ in self.calls))
 def test_sync_token_cannot_be_sent_to_a_remote_panel(self):
  with self.assertRaises(AssertionError):control.invoke('sync',{'url':'https://example.com:443/panel','token':'test-only'})
  self.assertFalse(any('/settings/xui' in p for p,_ in self.calls))
 def test_same_vps_https_panel_can_sync(self):
  result=control.invoke('sync',{'url':'https://127.0.0.1:2053/path','token':'test-only'})
  self.assertTrue(result['installed']);self.assertTrue(any(p.endswith('/settings/xui/sync') for p,_ in self.calls))
if __name__=='__main__':unittest.main()
