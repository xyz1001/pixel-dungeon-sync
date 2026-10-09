"""Disposable loopback onboarding fixture: no production credentials or outbound requests."""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
import base64,json,xml.etree.ElementTree as ET
BASE=Path('/workspace/shared/pds-onboarding-v5'); ROOT=BASE/'remote';ROOT.mkdir(exist_ok=True)
(ROOT/'dav-root').mkdir(exist_ok=True)
ALLOWED={'/dav-root/','/dav-root/shattered/','/dav-root/shattered/default/','/dav-root/shattered/legacy-kept42/'}
class Handler(BaseHTTPRequestHandler):
 def finish_response(self,status,data=b'',typ='text/plain'):
  self.send_response(status);self.send_header('Content-Length',str(len(data)));self.send_header('Content-Type',typ);self.send_header('Connection','close');self.end_headers()
  if data:self.wfile.write(data)
 def auth(self):
  if self.headers.get('Authorization')!='Basic '+base64.b64encode(b'demo:demo').decode():self.finish_response(401);return False
  return True
 def mapped_path(self):
  return ROOT/self.path.lstrip('/')
 def do_PROPFIND(self):
  if not self.auth():return
  self.rfile.read(int(self.headers.get('Content-Length','0')))
  if self.path not in ALLOWED:self.finish_response(404);return
  p=self.mapped_path()
  if not p.is_dir():self.finish_response(404);return
  if self.headers.get('Depth') not in ('0','1'):self.finish_response(400);return
  m=ET.Element('{DAV:}multistatus');r=ET.SubElement(m,'{DAV:}response');ET.SubElement(r,'{DAV:}href').text=self.path
  ps=ET.SubElement(r,'{DAV:}propstat');pr=ET.SubElement(ps,'{DAV:}prop');rt=ET.SubElement(pr,'{DAV:}resourcetype');ET.SubElement(rt,'{DAV:}collection');ET.SubElement(ps,'{DAV:}status').text='HTTP/1.1 200 OK'
  self.finish_response(207,ET.tostring(m,encoding='utf-8',xml_declaration=True),'application/xml')
 def do_MKCOL(self):
  if not self.auth():return
  if self.path not in ALLOWED-{'/dav-root/'}:self.finish_response(403);return
  p=self.mapped_path()
  if p.exists():self.finish_response(405);return
  if not p.parent.is_dir():self.finish_response(409);return
  p.mkdir();self.finish_response(201)
 def do_GET(self):
  if not self.auth():return
  self.finish_response(404)
 def do_PUT(self):self.finish_response(405)
 def log_message(self,fmt,*args):
  with (BASE/'requests.log').open('a') as f:f.write(self.log_date_time_string()+' '+fmt%args+'\n')
if __name__=='__main__':ThreadingHTTPServer(('127.0.0.1',8778),Handler).serve_forever()
