#!/usr/bin/env python3
"""Isolated UI retry fixture. Only loopback; only public demo credentials."""
import importlib.util
import sys
from pathlib import Path
from http.server import ThreadingHTTPServer

# Explicit checkout path provided by the test launcher, never an outbound destination.
repo, base = map(Path, sys.argv[1:3])
spec = importlib.util.spec_from_file_location('webdav_demo', repo / 'tools/webdav_demo.py')
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)
demo.ROOT = base / 'remote'

class Handler(demo.Handler):
    def do_PROPFIND(self):
        if (base / 'fail-requests').exists():
            self.rfile.read(int(self.headers.get('Content-Length', '0')))
            self.send_error(503, 'Isolated retry test')
            return
        return super().do_PROPFIND()

ThreadingHTTPServer(('127.0.0.1', 8783), Handler).serve_forever()
