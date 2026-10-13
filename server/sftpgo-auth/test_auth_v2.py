import base64,io,json,threading,unittest,urllib.error,urllib.request
from http.server import BaseHTTPRequestHandler,HTTPServer
from unittest.mock import patch
import auth_v2 as api

class Tests(unittest.TestCase):
 def setUp(self):
  api._attempts.clear();self.created=[];self.calls=[]
  outer=self
  class Handler(BaseHTTPRequestHandler):
   def log_message(self,*args):pass
   def get_client_ip(self):return '127.0.0.1'
   def _send_json(self,*args):pass
   def do_POST(self):
    data=b'{"legacy":true}';self.send_response(200);self.end_headers();self.wfile.write(data)
  def create(user,pwd):
   if user=='existing':raise urllib.error.HTTPError('http://fake',409,'conflict',{},None)
   self.created.append((user,pwd))
  self.env={'AuthHandler':Handler,'SFTPGO_API_URL':'http://fake','PUBLIC_WEBDAV_HOST':'https://example.invalid/dav/',
   'get_admin_token':lambda:'FAKE_ADMIN_SECRET','create_sftpgo_user':create,'check_ip_rate_limit':lambda ip:True}
  api.install(self.env)
  self.server=HTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
  def fetch(req):
   self.calls.append(req.full_url)
   if req.full_url.endswith('/user/token'):
    if req.headers['Authorization']!='Basic '+base64.b64encode(b'existing:Example-test-42').decode():raise urllib.error.HTTPError('http://fake',401,'secret failure body',{},None)
    return {'access_token':'FAKE_USER_SECRET'}
   return {'username':req.full_url.rsplit('/',1)[-1],'status':1,'quota_size':5242880,'upload_bandwidth':100,'download_bandwidth':100,'password':'PRIVATE_HASH'}
  self.mock=patch.object(api,'fetch_json',side_effect=fetch);self.fake=self.mock.start()
 def tearDown(self):
  self.mock.stop();self.server.shutdown();self.server.server_close();self.thread.join()
 def request(self,body):
  req=urllib.request.Request('http://127.0.0.1:%s/api/register'%self.server.server_port,data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
  try:
   with urllib.request.urlopen(req) as r:return r.status,json.loads(r.read())
  except urllib.error.HTTPError as r:return r.code,json.loads(r.read())
 def body(self,action='login',user='existing',password='Example-test-42'):
  return {'version':2,'action':action,'username':user,'password':password}
 def test_legacy_empty_unchanged(self):
  self.assertEqual(self.request({}),(200,{'legacy':True}));self.assertEqual(self.calls,[])
 def test_login_uses_sftpgo_no_create_or_secret_echo(self):
  status,r=self.request(self.body());self.assertEqual(status,200);self.assertEqual(self.created,[])
  self.assertEqual(self.calls,['http://fake/api/v2/user/token','http://fake/api/v2/users/existing'])
  self.assertEqual(r['data']['quota_bytes'],5242880)
  self.assertNotIn('password',r['data']);self.assertNotIn('SECRET',json.dumps(r));self.assertNotIn('PRIVATE_HASH',json.dumps(r))
 def test_registration_forwards_exact_password(self):
  status,r=self.request(self.body('register','newuser'));self.assertEqual(status,200)
  self.assertEqual(self.created,[('newuser','Example-test-42')]);self.assertNotIn('password',r['data'])
 def test_duplicate_never_updates(self):
  self.assertEqual(self.request(self.body('register'))[0],409);self.assertEqual(self.calls,[])
 def test_bad_login_never_queries_account(self):
  status,r=self.request(self.body(password='incorrect'));self.assertEqual(status,401)
  self.assertEqual(len(self.calls),1);self.assertNotIn('secret failure',json.dumps(r))
 def test_invalid_payload_does_not_authenticate(self):
  for b in ([],{'version':2},self.body('register','../escape'),self.body('register',password='$argon2id$pretendhash')):
   self.assertEqual(self.request(b)[0],400)
  self.assertEqual(self.calls,[]);self.assertEqual(self.created,[])
 def test_login_rate_limit(self):
  api._attempts['127.0.0.1']=[api.time.monotonic()]*30
  self.assertEqual(self.request(self.body())[0],429);self.assertEqual(self.calls,[])
 def test_upstream_error_redacted(self):
  self.fake.side_effect=RuntimeError('PRIVATE_SECRET');status,r=self.request(self.body())
  self.assertEqual(status,502);self.assertNotIn('PRIVATE_SECRET',json.dumps(r))

if __name__=='__main__':unittest.main()
