import gzip,importlib.util,json,tempfile,threading,unittest,urllib.request,urllib.error,uuid
from pathlib import Path
spec=importlib.util.spec_from_file_location('diag',Path(__file__).with_name('server.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class IntakeTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();m.ROOT=Path(self.tmp.name);m.RATE.clear();m.init();m.ADMIN_PROXY_TOKEN="test-admin-proxy"
  self.server=m.Server(('127.0.0.1',0),m.Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
  self.url='http://127.0.0.1:'+str(self.server.server_port)+m.BASE
 def tearDown(self):self.server.shutdown();self.server.server_close();self.tmp.cleanup()
 def request(self,path,data,token=None,rid=None):
  headers={'Content-Type':'application/gzip'}
  if token:headers['Authorization']='Bearer '+token
  if rid:headers['Idempotency-Key']=rid
  req=urllib.request.Request(self.url+path,data=data,headers=headers)
  try:
   with urllib.request.urlopen(req) as r:return r.status,json.loads(r.read())
  except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
 def enroll(self):
  code='ABCDEF123456'
  with m.db() as c:c.execute('INSERT OR REPLACE INTO codes VALUES(?,?,?)',(m.sha(code),m.now()+60000,2))
  device=str(uuid.uuid4());status,r=self.request('/enroll',json.dumps({'code':code,'installationId':device}).encode())
  self.assertEqual(200,status);return device,r['token']
 def report(self,device,rid,detail='normal'):
  return gzip.compress(json.dumps({'schemaVersion':1,'reportId':rid,'installationId':device,'createdAt':m.now(),'appVersion':'test','issue':'audio test','metadata':{'model':'test'},'events':[{'schema':1,'utc':m.now(),'elapsedMs':100,'seq':1,'session':str(uuid.uuid4()),'attempt':'a','level':'ERROR','category':'audio','event':'failed','detail':detail}]}).encode())
 def test_auth_required_and_no_public_read(self):
  rid=str(uuid.uuid4());data=self.report(str(uuid.uuid4()),rid)
  self.assertEqual(401,self.request('/reports',data,rid=rid)[0])
  try:urllib.request.urlopen(self.url+'/reports/'+rid)
  except urllib.error.HTTPError as e:self.assertEqual(404,e.code)
 def test_store_retry_same_receipt_and_conflicting_body_rejected(self):
  d,t=self.enroll();rid=str(uuid.uuid4());raw=self.report(d,rid)
  a=self.request('/reports',raw,t,rid);b=self.request('/reports',raw,t,rid)
  self.assertEqual(201,a[0]);self.assertEqual(200,b[0]);self.assertEqual(a[1]['reportId'],b[1]['reportId'])
  self.assertEqual(409,self.request('/reports',self.report(d,rid,'changed'),t,rid)[0])
  with m.db() as c:self.assertEqual(1,c.execute('SELECT COUNT(*) FROM reports').fetchone()[0])
 def test_other_device_cannot_overwrite_report(self):
  d,t=self.enroll();rid=str(uuid.uuid4());self.request('/reports',self.report(d,rid),t,rid)
  original=(m.ROOT/'reports'/(rid+'.json.gz')).read_bytes()
  d2,t2=self.enroll();self.assertEqual(403,self.request('/reports',self.report(d2,rid),t2,rid)[0])
  self.assertEqual(original,(m.ROOT/'reports'/(rid+'.json.gz')).read_bytes())
 def test_gzip_bomb_and_unknown_assets_rejected(self):
  d,t=self.enroll();rid=str(uuid.uuid4())
  bomb=gzip.compress(b'a'*(m.MAX_RAW+1));self.assertEqual(400,self.request('/reports',bomb,t,rid)[0])
  payload=json.loads(gzip.decompress(self.report(d,rid)));payload['certificate']='not allowed'
  self.assertEqual(400,self.request('/reports',gzip.compress(json.dumps(payload).encode()),t,rid)[0])
 def test_defense_redaction_and_summary(self):
  d,t=self.enroll();rid=str(uuid.uuid4());self.assertEqual(201,self.request('/reports',self.report(d,rid,'password=DO_NOT_SAVE'),t,rid)[0])
  stored=gzip.decompress((m.ROOT/'reports'/(rid+'.json.gz')).read_bytes()).decode();self.assertNotIn('DO_NOT_SAVE',stored)
  with m.db() as c:self.assertEqual(1,c.execute('SELECT errors FROM reports').fetchone()[0])
 def test_expired_pairing_code_cannot_register(self):
  status,_=self.request('/enroll',json.dumps({'code':'EXPIRED00000','installationId':str(uuid.uuid4())}).encode());self.assertEqual(401,status)
 def admin(self,path='',data=None,cookie=None,origin='https://localhost',authorized=True):
  headers={'Host':'localhost','Content-Type':'application/x-www-form-urlencoded'}
  if authorized:headers.update({'X-DiPlay-Admin':'admin','X-DiPlay-Admin-Token':'test-admin-proxy'})
  if cookie:headers['Cookie']=cookie
  if origin:headers['Origin']=origin
  req=urllib.request.Request(self.url+'/admin/'+path,data=data,headers=headers)
  try:
   with urllib.request.urlopen(req) as r:return r.status,r.read().decode(),r.headers
  except urllib.error.HTTPError as e:return e.code,e.read().decode(),e.headers
 def test_admin_requires_login_and_has_private_headers(self):
  self.assertEqual(401,self.admin(authorized=False)[0])
  status,page,headers=self.admin()
  self.assertEqual(200,status);self.assertIn('生成配对码',page)
  self.assertEqual('no-store',headers['Cache-Control'])
  self.assertEqual('same-origin',headers['Referrer-Policy'])
  self.assertIn('HttpOnly',headers['Set-Cookie']);self.assertIn('Secure',headers['Set-Cookie'])
  self.assertIn("frame-ancestors 'none'",headers['Content-Security-Policy'])
 def test_admin_csrf_origin_expiry_and_bounds(self):
  import re,urllib.parse
  _,page,headers=self.admin();token=re.search(r'name="csrf" value="([^"]+)"',page)[1]
  cookie=headers['Set-Cookie'].split(';')[0]
  def post(csrf=token,uses='2',origin='https://localhost',saved=cookie):
   return self.admin('generate',urllib.parse.urlencode({'csrf':csrf,'uses':uses}).encode(),saved,origin)[0]
  self.assertEqual(403,post(csrf='bad'));self.assertEqual(403,post(origin='https://evil.example'));self.assertEqual(403,post(saved=None))
  self.assertEqual(400,post(uses='5'));self.assertFalse(m.valid_admin_token('0.'+'a'*48+'.'+'b'*64))
  with m.db() as c:self.assertEqual(0,c.execute('SELECT COUNT(*) FROM codes').fetchone()[0])
 def test_admin_generated_code_enrolls_and_is_only_hashed(self):
  import re,urllib.parse
  _,page,headers=self.admin();token=re.search(r'name="csrf" value="([^"]+)"',page)[1]
  status,result,_=self.admin('generate',urllib.parse.urlencode({'csrf':token,'uses':'1'}).encode(),headers['Set-Cookie'].split(';')[0])
  self.assertEqual(200,status);code=re.search(r'id="code" value="([A-F0-9]{12})"',result)[1]
  with m.db() as c:
   row=c.execute('SELECT * FROM codes').fetchone();self.assertEqual(m.sha(code),row['hash']);self.assertEqual(1,row['remaining'])
  body=lambda:json.dumps({'code':code,'installationId':str(uuid.uuid4())}).encode()
  self.assertEqual(200,self.request('/enroll',body())[0]);self.assertEqual(401,self.request('/enroll',body())[0])
if __name__=='__main__':unittest.main()
