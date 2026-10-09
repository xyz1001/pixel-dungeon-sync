"""Regression test for the isolated HTTP/1.0 demo fixture."""
import base64
import http.client
from pathlib import Path
import tempfile
import threading
import unittest
import xml.etree.ElementTree as ET

import webdav_demo


class DemoServerTest(unittest.TestCase):
    def test_explicit_connection_close_and_conditional_upload(self):
        with tempfile.TemporaryDirectory(prefix='pds-demo-http-') as temp:
            original_root = webdav_demo.ROOT
            webdav_demo.ROOT = Path(temp)
            server = webdav_demo.ThreadingHTTPServer(('127.0.0.1', 0), webdav_demo.Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            connection = http.client.HTTPConnection(*server.server_address, timeout=5)
            headers = {'Authorization': 'Basic ' + base64.b64encode(b'demo:demo').decode()}
            try:
                for method, body, extra, expected in [
                    ('GET', None, {}, 404),
                    ('PUT', b'synthetic only', {'If-None-Match': '*'}, 201),
                    ('GET', None, {}, 200),
                    ('PUT', b'different', {'If-None-Match': '*'}, 412),
                    ('GET', None, {}, 200),
                ]:
                    connection.request(method, '/shattered/demo/head.json', body, headers | extra)
                    response = connection.getresponse()
                    self.assertEqual(expected, response.status)
                    self.assertEqual('close', response.getheader('Connection'))
                    content = response.read()
                    if expected == 200:
                        self.assertEqual(b'synthetic only', content)
                        self.assertTrue(response.getheader('ETag').startswith('"'))
            finally:
                connection.close()
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)
                webdav_demo.ROOT = original_root


    def test_full_depth_one_append_only_listing_does_not_modify_legacy_head(self):
        with tempfile.TemporaryDirectory(prefix='pds-demo-listing-') as temp:
            original_root = webdav_demo.ROOT
            webdav_demo.ROOT = Path(temp)
            old_head = b'legacy head must not change'
            (Path(temp) / 'head.json').write_bytes(old_head)
            names = [f'pdsync-v2-commit-{i:08x}-1111-4111-8111-111111111111.json' for i in range(200)]
            for name in names:
                (Path(temp) / name).write_bytes(b'synthetic')
            server = webdav_demo.ThreadingHTTPServer(('127.0.0.1', 0), webdav_demo.Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            connection = http.client.HTTPConnection(*server.server_address, timeout=5)
            auth = 'Basic ' + base64.b64encode(b'demo:demo').decode()
            try:
                connection.request('PROPFIND', '/shattered/demo/', b'', {'Authorization': auth, 'Depth': '1'})
                response = connection.getresponse()
                self.assertEqual(207, response.status)
                self.assertEqual('close', response.getheader('Connection'))
                self.assertIsNone(response.getheader('ETag'))
                root = ET.fromstring(response.read())
                hrefs = [e.text for e in root.findall('{DAV:}response/{DAV:}href')]
                self.assertEqual(202, len(hrefs))
                self.assertTrue(all('/shattered/demo/' + name in hrefs for name in names))
                self.assertEqual(old_head, (Path(temp) / 'head.json').read_bytes())
                for headers, expected in [({'Depth': '1'}, 401), ({'Authorization': auth, 'Depth': 'infinity'}, 400)]:
                    connection.request('PROPFIND', '/shattered/demo/', b'', headers)
                    rejected = connection.getresponse()
                    self.assertEqual(expected, rejected.status)
                    rejected.read()
            finally:
                connection.close()
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)
                webdav_demo.ROOT = original_root


    def test_depth_zero_checks_only_the_three_preexisting_virtual_collections(self):
        with tempfile.TemporaryDirectory(prefix='pds-demo-namespace-') as temp:
            original_root = webdav_demo.ROOT
            webdav_demo.ROOT = Path(temp)
            (Path(temp) / 'untouched.txt').write_bytes(b'synthetic')
            server = webdav_demo.ThreadingHTTPServer(('127.0.0.1', 0), webdav_demo.Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            connection = http.client.HTTPConnection(*server.server_address, timeout=5)
            auth = 'Basic ' + base64.b64encode(b'demo:demo').decode()
            try:
                for path in ('/', '/shattered/', '/shattered/demo/'):
                    connection.request('PROPFIND', path, b'', {'Authorization': auth, 'Depth': '0'})
                    response = connection.getresponse()
                    self.assertEqual(207, response.status)
                    root = ET.fromstring(response.read())
                    self.assertEqual([path], [e.text for e in root.findall('{DAV:}response/{DAV:}href')])
                    self.assertEqual(1, len(root.findall('{DAV:}response/{DAV:}propstat/{DAV:}prop/{DAV:}resourcetype/{DAV:}collection')))
                for path, depth in (('/', '1'), ('/shattered/', '1'), ('/shattered/other/', '0'), ('/outside/', '0')):
                    connection.request('PROPFIND', path, b'', {'Authorization': auth, 'Depth': depth})
                    response = connection.getresponse()
                    self.assertEqual(400, response.status)
                    response.read()
                connection.request('PROPFIND', '/', b'', {'Depth': '0'})
                response = connection.getresponse()
                self.assertEqual(401, response.status)
                response.read()
                self.assertEqual(['untouched.txt'], [p.name for p in Path(temp).iterdir()])
                self.assertEqual(b'synthetic', (Path(temp) / 'untouched.txt').read_bytes())
            finally:
                connection.close()
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)
                webdav_demo.ROOT = original_root


if __name__ == '__main__':
    unittest.main()
