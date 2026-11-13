#!/usr/bin/env python3
"""Private DiPlay diagnostic intake. Upload-only HTTPS via nginx; retrieval is SSH/CLI only."""
import html
from urllib.parse import parse_qs
from http.cookies import SimpleCookie
import argparse, collections, gzip, hashlib, hmac, json, os, re, secrets, sqlite3, threading, time, uuid, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_COMPRESSED=8*1024*1024
MAX_RAW=16*1024*1024
MAX_EVENTS=60000
BASE='/diplay-diagnostics/v1'
ROOT=Path(os.environ.get('DIPLAY_DIAGNOSTICS_DATA','/var/lib/diplay-diagnostics'))
LOCK=threading.RLock()
RATE=collections.defaultdict(collections.deque)
ADMIN=BASE+'/admin/'
ADMIN_SECRET=secrets.token_bytes(32)
ADMIN_PROXY_TOKEN=""

def now(): return int(time.time()*1000)
def sha(value): return hashlib.sha256(value.encode()).hexdigest()
def ident(value):
    if not isinstance(value,str): raise ValueError('identifier')
    return str(uuid.UUID(value))
def db():
    con=sqlite3.connect(ROOT/'index.sqlite',timeout=10)
    con.row_factory=sqlite3.Row
    return con

def init():
    global ADMIN_PROXY_TOKEN
    proxy_key=ROOT/"admin_proxy.key"
    ADMIN_PROXY_TOKEN=proxy_key.read_text().strip() if proxy_key.exists() else ""
    ROOT.mkdir(parents=True,exist_ok=True,mode=0o700)
    (ROOT/'reports').mkdir(exist_ok=True,mode=0o700)
    with db() as c:
        c.executescript('''CREATE TABLE IF NOT EXISTS codes(hash TEXT PRIMARY KEY,expires INTEGER,remaining INTEGER);
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,token_hash TEXT,created INTEGER,revoked INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY,device TEXT,received INTEGER,app TEXT,events INTEGER,errors INTEGER,session TEXT,issue TEXT,digest TEXT,bytes INTEGER);
        CREATE INDEX IF NOT EXISTS report_device_time ON reports(device,received);''')
    os.chmod(ROOT/'index.sqlite',0o600)

def limit(key,count,seconds):
    with LOCK:
        q=RATE[key];t=time.monotonic()
        while q and q[0]<t-seconds:q.popleft()
        if len(q)>=count:raise OverflowError('rate limited')
        q.append(t)
        if len(RATE)>4000:
            for k in list(RATE):
                if not RATE[k] or RATE[k][-1]<t-86400:RATE.pop(k,None)

SENSITIVE=re.compile(r'(?i)(password|passphrase|authorization|privatekey|certificate|pairingkey|access_token|refresh_token)\s*[:=]\s*[^\s,;}]+')
def safe(value,n=4000):
    value=str(value)
    if '-----BEGIN ' in value or re.search(r'[A-Za-z0-9+/]{160,}={0,2}',value):return '[redacted credential payload]'
    return SENSITIVE.sub(lambda m:m.group(1)+'=[redacted]',value)[:n]

def validate(raw,device,request_id):
    dec=zlib.decompressobj(16+zlib.MAX_WBITS)
    plain=dec.decompress(raw,MAX_RAW+1)
    if len(plain)>MAX_RAW or not dec.eof or dec.unused_data:raise ValueError('gzip bounds')
    r=json.loads(plain)
    fields={'schemaVersion','reportId','installationId','createdAt','appVersion','issue','metadata','events'}
    if not isinstance(r,dict) or set(r)!=fields or r['schemaVersion']!=1:raise ValueError('schema')
    if ident(r['reportId'])!=request_id or ident(r['installationId'])!=device:raise ValueError('report identity')
    if not isinstance(r['events'],list) or not 1<=len(r['events'])<=MAX_EVENTS:raise ValueError('events')
    if not isinstance(r['metadata'],dict) or set(r['metadata'])-{'model','manufacturer','androidSdk','appVersion','logPolicy','oldestEvent','newestEvent','fileCount','dropped'}:raise ValueError('metadata')
    if not isinstance(r['createdAt'],int):raise ValueError('timestamp')
    r['issue']=safe(r['issue'],1000);r['appVersion']=safe(r['appVersion'],80)
    r['metadata']={k:safe(v,200) for k,v in r['metadata'].items()}
    errors=0;sessions=set()
    expected={'schema','utc','elapsedMs','seq','session','attempt','level','category','event','detail'}
    for e in r['events']:
        if not isinstance(e,dict) or set(e)!=expected or e['schema']!=1:raise ValueError('event schema')
        if e['level'] not in ('INFO','WARN','ERROR'):raise ValueError('event level')
        ident(e['session']);sessions.add(e['session'])
        if not all(isinstance(e[k],int) for k in ('utc','elapsedMs','seq')):raise ValueError('event times')
        for k in ('attempt','category','event','detail'):e[k]=safe(e[k],4000 if k=='detail' else 100)
        errors+=e['level']=='ERROR'
    return r,errors,','.join(sorted(sessions))

def prune(c):
    # Bounded private storage, with a generous floor for recently submitted reports.
    rows=c.execute('SELECT id,received,bytes FROM reports ORDER BY received DESC').fetchall()
    size=0
    for i,row in enumerate(rows):
        size+=row['bytes']
        if row['received']<now()-30*86400000 or size>2*1024**3 or i>=2000:
            (ROOT/'reports'/(row['id']+'.json.gz')).unlink(missing_ok=True)
            c.execute('DELETE FROM reports WHERE id=?',(row['id'],))
    c.execute('DELETE FROM codes WHERE expires<? OR remaining<=0',(now(),))

def pairing_code(uses):
    if uses not in (1,2,3,4):raise ValueError('invalid uses')
    code=secrets.token_hex(6).upper()
    with LOCK,db() as c:
        c.execute('DELETE FROM codes WHERE expires<? OR remaining<=0',(now(),))
        c.execute('INSERT INTO codes VALUES(?,?,?)',(sha(code),now()+86400000,uses))
    return code

def admin_token():
    payload=str(now())+'.'+secrets.token_hex(24)
    return payload+'.'+hmac.new(ADMIN_SECRET,payload.encode(),'sha256').hexdigest()

def valid_admin_token(token):
    try:
        issued,nonce,signature=token.split('.')
        return 0<=now()-int(issued)<=1200000 and len(nonce)==48 and hmac.compare_digest(signature,hmac.new(ADMIN_SECRET,(issued+'.'+nonce).encode(),'sha256').hexdigest())
    except (ValueError,TypeError):return False

class Server(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,*a,**k):self.slots=threading.BoundedSemaphore(8);super().__init__(*a,**k)
    def process_request(self,request,address):
        if not self.slots.acquire(False):self.shutdown_request(request);return
        super().process_request(request,address)
    def process_request_thread(self,*a):
        try:super().process_request_thread(*a)
        finally:self.slots.release()
class Handler(BaseHTTPRequestHandler):
    server_version='DiPlayDiagnostics'
    def setup(self):super().setup();self.connection.settimeout(20)
    def log_message(self,*a):pass
    def reply(self,status,value):
        data=json.dumps(value,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def admin_reply(self,status,code='',uses=2):
        token=admin_token()
        template=Path(__file__).with_name('pairing_admin.html').read_text()
        content=template.replace('{{csrf}}',html.escape(token,quote=True)).replace('{{code}}',html.escape(code)).replace('{{uses}}',str(uses)).replace('{{result_hidden}}','' if code else 'hidden')
        data=content.encode()
        self.send_response(status)
        self.send_header('Content-Type','text/html; charset=utf-8')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.send_header('X-Content-Type-Options','nosniff')
        # Keep same-origin form POST Origin intact; no referrer is sent cross-origin.
        self.send_header('Referrer-Policy','same-origin')
        self.send_header('Set-Cookie','diplay_admin_csrf='+token+'; Path='+ADMIN+'; Secure; HttpOnly; SameSite=Strict; Max-Age=1200')
        self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def admin_authorized(self):
        # Set only by the isolated nginx Basic Auth location. Backend stays loopback-only.
        return bool(ADMIN_PROXY_TOKEN) and self.headers.get('X-DiPlay-Admin')=='admin' and hmac.compare_digest(self.headers.get('X-DiPlay-Admin-Token',''),ADMIN_PROXY_TOKEN)
    def do_GET(self):
        if self.path.startswith(ADMIN):
            if not self.admin_authorized():self.reply(401,{'error':'admin login required'});return
            if self.path==ADMIN:self.admin_reply(200);return
            if self.path==ADMIN+'copy.js':
                data=b"document.querySelector('#copy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(document.querySelector('#code').value);document.querySelector('#feedback').textContent='\xe5\xb7\xb2\xe5\xa4\x8d\xe5\x88\xb6';}catch(e){document.querySelector('#code').select();document.querySelector('#feedback').textContent='\xe8\xaf\xb7\xe9\x95\xbf\xe6\x8c\x89\xe9\x85\x8d\xe5\xaf\xb9\xe7\xa0\x81\xe5\xa4\x8d\xe5\x88\xb6';}});"
                self.send_response(200);self.send_header('Content-Type','text/javascript; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
            self.reply(404,{'error':'not found'});return
        if self.path==BASE+'/health':self.reply(200,{'service':'diplay-diagnostics','schemaVersion':1})
        else:self.reply(404,{'error':'not found'})
    def do_POST(self):
        try:
            ip=self.headers.get('X-Real-IP',self.client_address[0])
            if self.headers.get('Transfer-Encoding'):raise ValueError('chunked requests are not supported')
            length=int(self.headers.get('Content-Length','0'))
            if self.path==ADMIN+'generate':
                if not self.admin_authorized():self.reply(401,{'error':'admin login required'});return
                if not 0<length<=2048:raise ValueError('size')
                if self.headers.get('Origin')!='https://'+self.headers.get('Host',''):
                    self.reply(403,{'error':'invalid origin'});return
                if self.headers.get('Content-Type','').split(';')[0]!='application/x-www-form-urlencoded':raise ValueError('form')
                payload=parse_qs(self.rfile.read(length).decode(),strict_parsing=True,max_num_fields=4)
                cookie=SimpleCookie();cookie.load(self.headers.get('Cookie',''))
                token=payload.get('csrf',[''])[0]
                saved=cookie.get('diplay_admin_csrf')
                if not saved or not valid_admin_token(token) or not hmac.compare_digest(token,saved.value):
                    self.reply(403,{'error':'form expired; reopen admin page'});return
                limit('admin:'+ip,10,60)
                uses=int(payload.get('uses',['2'])[0])
                self.admin_reply(200,pairing_code(uses),uses);return
            enrollment=self.path==BASE+'/enroll'
            if self.path not in (BASE+'/enroll',BASE+'/reports'):self.reply(404,{'error':'not found'});return
            if not 0<length<=(2048 if enrollment else MAX_COMPRESSED):self.reply(413,{'error':'size limit'});return
            limit('enroll:'+ip if enrollment else 'ip:'+ip,6 if enrollment else 30,60)
            if enrollment:
                payload=json.loads(self.rfile.read(length));code=str(payload.get('code','')).upper();device=ident(payload.get('installationId'))
                with LOCK,db() as c:
                    row=c.execute('SELECT * FROM codes WHERE hash=? AND expires>? AND remaining>0',(sha(code),now())).fetchone()
                    if not row:self.reply(401,{'error':'invalid or expired pairing code'});return
                    token=secrets.token_urlsafe(32)
                    c.execute('INSERT OR REPLACE INTO devices(id,token_hash,created,revoked) VALUES(?,?,?,0)',(device,sha(token),now()))
                    c.execute('UPDATE codes SET remaining=remaining-1 WHERE hash=?',(sha(code),))
                self.reply(200,{'token':token,'installationId':device});return
            auth=self.headers.get('Authorization','')
            if not auth.startswith('Bearer '):self.reply(401,{'error':'upload authorization required'});return
            with db() as c:device=c.execute('SELECT id FROM devices WHERE token_hash=? AND revoked=0',(sha(auth[7:]),)).fetchone()
            if not device:self.reply(401,{'error':'upload authorization required'});return
            device=device['id'];limit('device:'+device,4,60);limit('daily:'+device,60,86400)
            request_id=ident(self.headers.get('Idempotency-Key',''))
            raw=self.rfile.read(length)
            if len(raw)!=length:raise ValueError('truncated request')
            report,errors,sessions=validate(raw,device,request_id);digest=hashlib.sha256(raw).hexdigest()
            with LOCK,db() as c:
                old=c.execute('SELECT digest,received,device FROM reports WHERE id=?',(request_id,)).fetchone()
                if old:
                    if old['device']!=device:self.reply(403,{'error':'report ownership'});return
                    if old['digest']!=digest:self.reply(409,{'error':'idempotency conflict'});return
                    self.reply(200,{'reportId':request_id,'receivedAt':old['received'],'eventCount':len(report['events'])});return
                packed=gzip.compress(json.dumps(report,ensure_ascii=False,separators=(',',':')).encode(),mtime=0)
                dest=ROOT/'reports'/(request_id+'.json.gz');tmp=dest.with_suffix('.tmp')
                with open(tmp,'xb') as f:os.chmod(tmp,0o600);f.write(packed);f.flush();os.fsync(f.fileno())
                os.replace(tmp,dest)
                t=now()
                c.execute('INSERT INTO reports VALUES(?,?,?,?,?,?,?,?,?,?)',(request_id,device,t,report['appVersion'],len(report['events']),errors,sessions,report['issue'],digest,len(packed)))
                prune(c)
            self.reply(201,{'reportId':request_id,'receivedAt':t,'eventCount':len(report['events'])})
        except OverflowError:self.reply(429,{'error':'rate limited; retry later'})
        except (ValueError,TypeError,KeyError,zlib.error,UnicodeDecodeError):self.reply(400,{'error':'invalid diagnostic report'})
        except (BrokenPipeError,ConnectionResetError,TimeoutError):pass
        except Exception:self.reply(500,{'error':'intake unavailable'})

def main():
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='action',required=True)
    sub.add_parser('serve');p=sub.add_parser('pair');p.add_argument('--uses',type=int,default=2)
    p=sub.add_parser('list');p.add_argument('--limit',type=int,default=20);p.add_argument('--device');p.add_argument('--since',type=int,default=0)
    p=sub.add_parser('show');p.add_argument('id');p.add_argument('--errors',action='store_true');p.add_argument('--session');p.add_argument('--last',type=int,default=200)
    p=sub.add_parser('revoke');p.add_argument('device')
    args=parser.parse_args();init()
    if args.action=='serve':Server(('127.0.0.1',8796),Handler).serve_forever()
    elif args.action=='pair':
        code=pairing_code(min(4,max(1,args.uses)))
        print(code) # one-day upload-only enrollment code, intentionally presented to the owner
    elif args.action=='list':
        with db() as c:
            rows=c.execute('SELECT id,device,received,app,events,errors,issue FROM reports WHERE received>=? AND (? IS NULL OR device=?) ORDER BY received DESC LIMIT ?',(args.since,args.device,args.device,min(100,max(1,args.limit)))).fetchall()
            print(json.dumps([dict(r) for r in rows],ensure_ascii=False,indent=2))
    elif args.action=='revoke':
        with db() as c:c.execute('UPDATE devices SET revoked=1 WHERE id=?',(ident(args.device),))
    else:
        report=json.loads(gzip.decompress((ROOT/'reports'/(ident(args.id)+'.json.gz')).read_bytes()))
        events=[e for e in report['events'] if (not args.errors or e['level'] in ('WARN','ERROR')) and (not args.session or e['session']==args.session)]
        print(json.dumps({**report,'events':events[-max(1,min(2000,args.last)):]},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
