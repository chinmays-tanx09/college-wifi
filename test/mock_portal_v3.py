"""Sophos captive-portal mock v3 (dummy credentials only: dummytest/dummytest).
Adds: /live keep-alive (mode=192 -> <ack>ack</ack> or login_again), /logout.xml, session expiry on demand,
idle expiry when keep-alives stop (like Sophos), HTTP-204 internet probe, and fault modes for logout/live/login.
Admin: /admin/state  /admin/expire  /admin/config?key=value  /admin/reset
"""
import json, sys, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
from xml.sax.saxutils import escape
PORT=int(sys.argv[1]) if len(sys.argv)>1 else 8090
LOCK=threading.Lock()
def fresh():
    return {'logged_in':False,'last_activity':0,'logins':0,'login_fail':0,'logouts':0,'lives':0,'expired_by_admin':0,'expired_idle':0,
            'events':[], 'cfg':{'idle_timeout':0,'logout_mode':'normal','live_mode':'normal','login_mode':'normal','logout_delay':0}}
S=fresh()
def ev(k): S['events'].append([round(time.time(),3),k]); del S['events'][:-200]
def idle_check():
    t=S['cfg']['idle_timeout']
    if t and S['logged_in'] and time.time()-S['last_activity']>t:
        S['logged_in']=False; S['expired_idle']+=1; ev('idle_expired')
HTML="""<!doctype html><html><body><h1>Sophos</h1><input id="username" name="username"><input id="password" type="password" name="password">
<div id="loginbutton" onclick="submitRequest()">Sign in</div><div id="msg"></div><script>
function submitRequest(){var x=new XMLHttpRequest();x.open('POST','/login.xml',true);x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
x.onload=function(){var m=x.responseXML&&x.responseXML.getElementsByTagName('message')[0];document.getElementById('msg').textContent=m?m.textContent:'No portal response';};
x.send('mode=191&username='+encodeURIComponent(document.getElementById('username').value)+'&password='+encodeURIComponent(document.getElementById('password').value)+'&a='+Date.now()+'&producttype=0');}
</script></body></html>"""
def rr(status,msg): return f"<?xml version='1.0' ?><requestresponse><status><![CDATA[{status}]]></status><message><![CDATA[{msg}]]></message></requestresponse>"
class H(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.0'
    def out(self,data,typ='text/xml',code=200,extra=None):
        if isinstance(data,str): data=data.encode()
        self.send_response(code); self.send_header('Content-Type',typ); self.send_header('Content-Length',str(len(data)))
        for k,v in (extra or {}).items(): self.send_header(k,v)
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        u=urlsplit(self.path); q=parse_qs(u.query)
        with LOCK:
            idle_check()
            if u.path in ('/','/httpclient.html'): return self.out(HTML,'text/html')
            if u.path=='/live':
                S['lives']+=1; ev('live')
                mode=S['cfg']['live_mode']
                if mode=='garbage': return self.out('<html>weird</html>','text/html')
                if mode=='http500': return self.out('err','text/plain',500)
                ok=S['logged_in'] and q.get('mode')==['192'] and q.get('username')==['dummytest']
                if ok: S['last_activity']=time.time()
                return self.out("<?xml version='1.0' ?><requestresponse><ack><![CDATA[%s]]></ack></requestresponse>"%('ack' if ok else 'login_again'))
            if u.path=='/generate_204':
                if S['logged_in']: return self.out(b'','text/plain',204)
                return self.out('','text/html',302,{'Location':'http://172.16.16.16:8090/httpclient.html'})
            if u.path=='/admin/state':
                d=dict(S); return self.out(json.dumps(d),'application/json')
            if u.path=='/admin/expire':
                S['logged_in']=False; S['expired_by_admin']+=1; ev('admin_expired'); return self.out('expired','text/plain')
            if u.path=='/admin/reset':
                S.clear(); S.update(fresh()); return self.out('reset','text/plain')
            if u.path=='/admin/config':
                for k,v in q.items():
                    S['cfg'][k]=int(v[0]) if v[0].isdigit() else v[0]
                return self.out(json.dumps(S['cfg']),'application/json')
        self.send_error(404)
    def do_POST(self):
        u=urlsplit(self.path); n=min(int(self.headers.get('Content-Length',0)),10000)
        p=parse_qs(self.rfile.read(n).decode('utf-8','replace')); mode=p.get('mode',[''])[0]
        with LOCK:
            idle_check()
            if mode=='193' and u.path in ('/logout.xml','/login.xml'):
                lm=S['cfg']['logout_mode']; S['logouts']+=1; S['logged_in']=False; ev('logout '+u.path)
                delay=S['cfg']['logout_delay']
            elif u.path=='/login.xml' and mode=='191':
                ok=p.get('username')==['dummytest'] and p.get('password')==['dummypass123'] and p.get('producttype')==['0'] and p.get('a',[''])[0].isdigit()
                if S['cfg']['login_mode']=='html':
                    S['login_fail']+=1; ev('login_html'); return self.out(HTML,'text/html')
                if ok: S['logins']+=1; S['logged_in']=True; S['last_activity']=time.time(); ev('login_ok')
                else: S['login_fail']+=1; ev('login_bad')
                return self.out(rr('LIVE','You are signed in as dummytest') if ok else rr('LOGIN','Invalid user name/password'))
            else: return self.out(rr('LOGIN','Invalid request'))
        if delay: time.sleep(delay)
        if lm=='http500': return self.out('<html>Internal error</html>','text/html',500)
        if lm=='garbage': return self.out('<html>ok</html>','text/html')
        return self.out(rr('LOGIN',"You&#39;ve signed out"))
    def log_message(self,*a): pass
ThreadingHTTPServer((sys.argv[2] if len(sys.argv)>2 else '127.0.0.1',PORT),H).serve_forever()
