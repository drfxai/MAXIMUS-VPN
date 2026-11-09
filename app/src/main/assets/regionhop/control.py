#!/usr/bin/env python3
"""Private SSH-to-loopback panel adapter. Never output passwords/configs/API tokens."""
import http.cookiejar,json,pathlib,re,subprocess,sys,urllib.parse,urllib.request
ROOT=pathlib.Path('/opt/maximus-regionhop')
PANEL=pathlib.Path('/opt/psi-panel')
REGIONS=set('AT BE BG CZ DK EE FI FR DE IE IT LT NL NO PL RO RS ES SE CH GB IN ID JP SG CA US BR AU'.split())
def invoke(operation, data):
    if not (ROOT/'control.json').is_file():
        if operation=='status': return {'installed':False,'locations':[]}
        raise ValueError('Install managed RegionHop first')
    cfg=json.loads((ROOT/'control.json').read_text())
    assert re.fullmatch(r'/[a-f0-9]{32}',cfg['prefix'])
    base=f"http://127.0.0.1:{int(cfg['port'])}{cfg['prefix']}"
    class NoExternalRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            assert newurl.startswith(base+'/'), 'External redirect refused'
            return super().redirect_request(req,fp,code,msg,headers,newurl)
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),NoExternalRedirect())
    def call(path, form=None):
        body=urllib.parse.urlencode(form).encode() if form is not None else None
        request=urllib.request.Request(base+path,body,headers={'Content-Type':'application/x-www-form-urlencoded','Origin':base,'Referer':base+'/'})
        with client.open(request,timeout=50) as response:
            assert '/login' not in response.url, 'RegionHop authentication failed'
            answer=response.read(524289);assert len(answer)<=524288
            return answer
    call('/login',{'password':cfg['password']})
    if operation=='prepare-remove':
        for location in locations():
            name=location['name'];assert re.fullmatch(r'[a-z0-9][a-z0-9-]{1,30}',name)
            call('/tunnels/'+name+'/remove',{})
        link=PANEL/'data/xui.json'
        if link.is_file() and json.loads(link.read_text()).get('enabled'):
            result=json.loads(call('/settings/xui/sync',{}));assert result.get('ok'), 'Outbound removal was not confirmed'
        return {'installed':True,'readyToRemove':True,'locations':[]}
    if operation=='add':
        code=data['region'];assert code in REGIONS
        name=data.get('name',code.lower());assert re.fullmatch(r'[a-z0-9][a-z0-9-]{1,30}',name)
        call('/tunnels/add',{'name':name,'region':code})
    elif operation in ['start','stop','restart','remove','test']:
        name=data['name'];assert re.fullmatch(r'[a-z0-9][a-z0-9-]{1,30}',name)
        assert name in {location['name'] for location in locations()}, 'Unknown location'
        if operation=='start':
            # v1.27 has no /start HTTP endpoint; use its pinned CLI to enable+start.
            subprocess.run(['/usr/local/bin/psictl','start',name],check=True,capture_output=True,timeout=45)
            answer=b'{}'
        else:
            answer=call('/tunnels/'+name+'/'+operation+('?mode=real' if operation=='test' else ''),None if operation=='test' else {})
        if operation=='test':
            result=json.loads(answer)
            return {'installed':True,'locations':locations(),'test':result}
    elif operation=='sync':
        url=data['url'];parts=urllib.parse.urlsplit(url)
        assert parts.scheme in ['http','https'] and parts.hostname=='127.0.0.1' and parts.username is None and parts.password is None and parts.port, 'Use the same VPS loopback 3X-UI URL'
        assert len(data['token'])<=8192 and '\n' not in data['token']
        call('/settings/xui',{'xui_enabled':'on','xui_url':url,'xui_token':data['token']})
        result=json.loads(call('/settings/xui/sync',{}));assert result.get('ok'), '3X-UI sync was not confirmed'
    elif operation!='status': raise ValueError('Unsupported operation')
    statuses=json.loads(call('/tunnels-status'))
    return {'installed':True,'version':(PANEL/'VERSION').read_text().strip(),'locations':locations(),'statuses':statuses}
def locations():
    p=(PANEL/'data/tunnels.json')
    raw=json.loads(p.read_text()) if p.exists() else []
    assert len(raw)<=128
    for x in raw:
        assert re.fullmatch(r'[a-z0-9][a-z0-9-]{1,30}',x['name']) and isinstance(x['socks_port'],int) and 1024<=x['socks_port']<=65535
    return [{'name':x['name'],'region':x['region'],'port':x['socks_port'],'outbound':'regionhop-'+x['name'],'state':subprocess.run(['systemctl','is-active','psi-tunnel@'+x['name']],capture_output=True,text=True,timeout=10).stdout.strip()} for x in raw]
if __name__=='__main__':
    try:
        request=json.loads(pathlib.Path(sys.argv[1]).read_text())
        print(json.dumps(invoke(request['operation'],request.get('data',{}))))
    except urllib.error.HTTPError as error:
        print(json.dumps({'error':f'RegionHop panel returned HTTP {error.code}; check panel credentials and API compatibility.'}));sys.exit(1)
    except Exception:
        print(json.dumps({'error':'RegionHop operation failed; inspect server services, panel authentication and 3X-UI API compatibility.'}));sys.exit(1)
