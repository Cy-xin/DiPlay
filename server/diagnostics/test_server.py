import gzip,importlib.util,json,tempfile,threading,unittest,urllib.request,urllib.error,uuid
from pathlib import Path
spec=importlib.util.spec_from_file_location('diag',Path(__file__).with_name('server.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class IntakeTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();m.ROOT=Path(self.tmp.name);m.RATE.clear();m.init()
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
if __name__=='__main__':unittest.main()
