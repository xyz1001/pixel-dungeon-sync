#!/usr/bin/env python3
"""Build/read a small, lossless JSON-only evidence bundle. No fixture/game/config reads.

Build: python3 evidence_bundle.py --root VALIDATION_ROOT > validation-evidence.json
Repeated JSON objects are stored once, in a plainly readable pool. Original raw
file SHA-256 and canonical JSON SHA-256 are both retained. No binary compression.
Screenshots are only hashed. JUnit is reduced to suite/case result attributes;
system-out/system-err and test properties are never copied into the bundle.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import xml.etree.ElementTree as ET

TAG = '$validationRef'


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path, limit=2*1024*1024):
    # Caller uses a fixed allowlist under its explicitly selected evidence root.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise ValueError('Evidence size/type limit')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read(limit + 1)
        after = os.fstat(fd)
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError('Evidence changed while reading')
        return data
    finally:
        os.close(fd)


def pack(documents, sources):
    count = Counter()
    def visit(node):
        if isinstance(node, (dict, list)):
            if isinstance(node, dict) and TAG in node:
                raise ValueError('Reserved key in source')
            encoded = canonical(node)
            if len(encoded) >= 256:
                count[encoded] += 1
            for child in (node.values() if isinstance(node, dict) else node):
                visit(child)
    for doc in documents.values():
        visit(doc)
    pool, indexes = [], {}
    def encode(node):
        if not isinstance(node, (dict, list)):
            return node
        key = canonical(node)
        shared = count[key] > 1
        if shared and key in indexes:
            return {TAG: indexes[key]}
        if shared:
            index = len(pool)
            indexes[key] = index
            pool.append(None)
        value = ({k: encode(v) for k, v in node.items()} if isinstance(node, dict)
                 else [encode(v) for v in node])
        if shared:
            pool[index] = value
            return {TAG: index}
        return value
    encoded = {name: encode(doc) for name, doc in documents.items()}
    return {'schema': 'cloud-native-validation-evidence-v1',
            'sources': sources, 'sourceManifestSha256': sha(canonical(sources)),
            'documents': encoded, 'pool': pool,
            'limits': 'Captured observations only. No save/JAR/ZIP/config bytes or credentials. '
                      'Screenshots remain local; their hashes are recorded. No GUI is replayed.'}


def unpack(bundle):
    if bundle['schema'] != 'cloud-native-validation-evidence-v1':
        raise ValueError('Unknown evidence schema')
    if sha(canonical(bundle['sources'])) != bundle['sourceManifestSha256']:
        raise ValueError('Source manifest hash mismatch')
    pool = bundle['pool']
    def decode(node, active=frozenset(), depth=0):
        if depth > 100:
            raise ValueError('Reference nesting limit')
        if isinstance(node, dict):
            if TAG in node:
                index = node[TAG]
                if len(node) != 1 or type(index) is not int or not 0 <= index < len(pool) or index in active:
                    raise ValueError('Invalid/cyclic reference')
                return decode(pool[index], active | {index}, depth + 1)
            return {k: decode(v, active, depth + 1) for k, v in node.items()}
        if isinstance(node, list):
            return [decode(v, active, depth + 1) for v in node]
        return node
    documents = {k: decode(v) for k, v in bundle['documents'].items()}
    for key, value in documents.items():
        if sha(canonical(value)) != bundle['sources'][key]['canonicalJsonSha256']:
            raise ValueError('Decoded document hash mismatch: ' + key)
    return documents


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = ap.parse_args()
    root, docs, sources = args.root, {}, {}
    def add(name, mode='json'):
        data = read(root / name)
        record = {'bytes': len(data), 'sha256': sha(data)}
        if mode == 'json':
            doc = json.loads(data)
        elif mode == 'xml':
            tree = ET.fromstring(data)
            doc = {'suite': {k: tree.get(k) for k in ['name', 'tests', 'failures', 'errors', 'skipped']},
                   'cases': [{'name': c.get('name'), 'classname': c.get('classname'),
                              'failure': c.find('failure') is not None,
                              'error': c.find('error') is not None,
                              'skipped': c.find('skipped') is not None} for c in tree.findall('testcase')]}
        elif mode == 'text':
            doc = data.decode()
        else:
            sources[name] = record
            return
        docs[name] = doc
        record['canonicalJsonSha256'] = sha(canonical(doc))
        sources[name] = record
    for path in sorted((root / 'evidence').iterdir()):
        if re.fullmatch(r'(?:0[2-9]|[1-3][0-9]|4[013])-[A-Za-z0-9-]+\.json', path.name):
            add('evidence/' + path.name)
        elif re.fullmatch(r'(?:01|12|23|32|34|35|42)-[A-Za-z0-9-]+\.jpg', path.name):
            add('evidence/' + path.name, 'hash')
    for name in ['evidence/capacity-run.json', 'build-evidence/test-summary.json',
                 'build-evidence/runtime-source-equivalence.json',
                 'build-evidence/headful-package-verification.json',
                 'verification/capacity-compile-manifest.json']:
        add(name)
    for name in ['evidence/capacity-run.exit', 'evidence/10-local-settings-before-restore.sha256']:
        add(name, 'text')
    for path in sorted((root/'build-evidence/attempt-4-headful-jdk-results').glob('*/TEST-*.xml')):
        add(str(path.relative_to(root)), 'xml')
    result = pack(docs, sources)
    assert unpack(result) == docs
    print(json.dumps(result, ensure_ascii=False, separators=(',', ':')))


if __name__ == '__main__':
    main()
