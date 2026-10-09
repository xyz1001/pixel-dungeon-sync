"""Regression test for the isolated HTTP/1.0 demo fixture."""
import base64
import http.client
from pathlib import Path
import tempfile
import threading
import unittest

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


if __name__ == '__main__':
    unittest.main()
