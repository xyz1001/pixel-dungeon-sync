#!/usr/bin/env python3
"""Isolated loopback test store. Not a production WebDAV server."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import base64, hashlib
from urllib.parse import quote
import xml.etree.ElementTree as ET
ROOT = Path('.local/webdav').resolve()
ROOT.mkdir(parents=True, exist_ok=True)
class Handler(BaseHTTPRequestHandler):
    def handle_request(self):
        if self.headers.get('Authorization') != 'Basic ' + base64.b64encode(b'demo:demo').decode():
            self.send_error(401); return
        parts = self.path.split('/')
        if len(parts) != 4 or parts[1:3] != ['shattered', 'demo'] or not parts[3] or any(x in parts[3] for x in ('..', '\\', '%', ':')):
            self.send_error(400); return
        p = ROOT / parts[3]
        etag = '"' + hashlib.sha256(p.read_bytes()).hexdigest() + '"' if p.exists() else None
        if self.command == 'GET':
            if not p.exists(): self.send_error(404); return
            # Explicit close prevents reuse with automatic client retries disabled.
            data = p.read_bytes(); self.send_response(200); self.send_header('Connection','close'); self.send_header('ETag',etag); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
        else:
            # serialize check+write across handler threads
            import threading
            with lock:
                etag = '"' + hashlib.sha256(p.read_bytes()).hexdigest() + '"' if p.exists() else None
                if self.headers.get('If-None-Match') == '*' and p.exists() or self.headers.get('If-Match') and self.headers.get('If-Match') != etag:
                    self.send_error(412); return
                data=self.rfile.read(int(self.headers.get('Content-Length','0')))
                p.write_bytes(data); self.send_response(201); self.send_header('Connection','close'); self.send_header('Content-Length','0'); self.end_headers()
    def do_PROPFIND(self):
        if self.headers.get('Authorization') != 'Basic ' + base64.b64encode(b'demo:demo').decode():
            self.send_error(401); return
        if self.path != '/shattered/demo/' or self.headers.get('Depth') != '1':
            self.send_error(400); return
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            self.send_error(400); return
        if length < 0 or length > 2 * 1024 * 1024:
            self.send_error(413); return
        self.rfile.read(length)
        # Full Depth:1 listing, no implicit page limit and no ETag requirement.
        multistatus = ET.Element('{DAV:}multistatus')
        with lock:
            entries = [(self.path, True)] + [
                (self.path + quote(p.name, safe='') + ('/' if p.is_dir() else ''), p.is_dir())
                for p in sorted(ROOT.iterdir()) if not p.is_symlink()
            ]
        for href, collection in entries:
            response = ET.SubElement(multistatus, '{DAV:}response')
            ET.SubElement(response, '{DAV:}href').text = href
            propstat = ET.SubElement(response, '{DAV:}propstat')
            prop = ET.SubElement(propstat, '{DAV:}prop')
            resource_type = ET.SubElement(prop, '{DAV:}resourcetype')
            if collection:
                ET.SubElement(resource_type, '{DAV:}collection')
            ET.SubElement(propstat, '{DAV:}status').text = 'HTTP/1.1 200 OK'
        data = ET.tostring(multistatus, encoding='utf-8', xml_declaration=True)
        self.send_response(207)
        self.send_header('Connection', 'close')
        self.send_header('Content-Type', 'application/xml; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = handle_request
    do_PUT = handle_request
import threading
lock = threading.Lock()
if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    ROOT = args.root.resolve()
    ROOT.mkdir(parents=True, exist_ok=True)
    print(f'Demo only: http://127.0.0.1:{args.port} credentials demo/demo', flush=True)
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
