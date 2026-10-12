#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Produce a checksum-pinned API-26 runtime apksig jar without modifying the SDK.

Only the Code attribute of Asn1BerParser.getElementType(Field) is replaced.
Its complete replacement body is: return Android26ElementType.getElementType(field).
The new helper source is adjacent in apksig-compat/. No signatures or verifiers
are removed, and all other original jar entries retain identical bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

JAR_SHA256 = '3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec'
CLASS_SHA256 = '4ca7f8f668bfc2739233655e34632f71aa2f20d51b8ff29c6d0402697c12c575'
PARSER = 'com/android/apksig/internal/asn1/Asn1BerParser.class'
HELPER = 'com/android/apksig/internal/asn1/Android26ElementType'
NOTICE = 'META-INF/pdsync-apksig-api26.NOTICE'
DESCRIPTOR = '(Ljava/lang/reflect/Field;)Ljava/lang/Class;'
HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent

def sha(data):
    return hashlib.sha256(data).hexdigest()

def u2(data, offset):
    return struct.unpack_from('>H', data, offset)[0]

def u4(data, offset):
    return struct.unpack_from('>I', data, offset)[0]

def parse_pool(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Not a Java class')
    count, position, index = u2(data, 8), 10, 1
    strings = {}
    while index < count:
        tag = data[position]
        position += 1
        if tag == 1:
            size = u2(data, position)
            position += 2
            strings[index] = data[position:position + size].decode('utf-8', errors='replace')
            position += size
        elif tag in (3, 4):
            position += 4
        elif tag in (5, 6):
            position += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            position += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            position += 4
        elif tag == 15:
            position += 3
        else:
            raise ValueError('Unexpected constant-pool tag: ' + str(tag))
        index += 1
    return count, position, strings

def skip_attributes(data, position, count):
    for _ in range(count):
        position += 6 + u4(data, position + 2)
    return position

def patch_parser(data):
    if sha(data) != CLASS_SHA256:
        raise ValueError('Unrecognized Asn1BerParser.class; review the new dependency before patching')
    count, pool_end, strings = parse_pool(data)
    appended = []
    def add(payload):
        index = count + len(appended)
        appended.append(payload)
        return index
    def utf(value):
        raw = value.encode('utf-8')
        return add(b'\x01' + struct.pack('>H', len(raw)) + raw)
    helper_name = utf(HELPER)
    helper_class = add(b'\x07' + struct.pack('>H', helper_name))
    method_name = utf('getElementType')
    descriptor = utf(DESCRIPTOR)
    name_type = add(b'\x0c' + struct.pack('>HH', method_name, descriptor))
    method_ref = add(b'\x0a' + struct.pack('>HH', helper_class, name_type))
    # aload_0; invokestatic helper.getElementType(Field); areturn.
    code = b'\x2a\xb8' + struct.pack('>H', method_ref) + b'\xb0'
    replacement = struct.pack('>HHI', 1, 1, len(code)) + code + b'\0\0\0\0'
    position = pool_end + 6
    position += 2 + 2 * u2(data, position)  # interfaces
    field_count = u2(data, position)
    position += 2
    for _ in range(field_count):
        attributes = u2(data, position + 6)
        position = skip_attributes(data, position + 8, attributes)
    method_count = u2(data, position)
    position += 2
    patches = []
    for _ in range(method_count):
        access, name, desc, attributes = struct.unpack_from('>HHHH', data, position)
        position += 8
        target = strings[name] == 'getElementType' and strings[desc] == DESCRIPTOR
        if target and access != 0x000a:  # private static
            raise ValueError('Unexpected method access flags')
        for _ in range(attributes):
            start = position
            attribute_name = u2(data, position)
            size = u4(data, position + 2)
            position += 6 + size
            if target and strings[attribute_name] == 'Code':
                patches.append((start, position, struct.pack('>HI', attribute_name, len(replacement)) + replacement))
    if len(patches) != 1:
        raise ValueError('Expected exactly one method Code attribute')
    start, end, new_attribute = patches[0]
    return (data[:8] + struct.pack('>H', count + len(appended)) + data[10:pool_end]
            + b''.join(appended) + data[pool_end:start] + new_attribute + data[end:])

def build_compat_jar(source, output, android_jar, java='java'):
    source, output, android_jar = (Path(x).resolve() for x in (source, output, android_jar))
    if output == source or output.is_relative_to(REPO) or output.is_relative_to(source.parent):
        raise ValueError('Compatibility output must be outside the repository and SDK jar directory')
    original = source.read_bytes()
    if sha(original) != JAR_SHA256:
        raise ValueError('Unrecognized SDK apksigner.jar SHA-256; refusing an unaudited patch')
    source_java = HERE / 'apksig-compat/Android26ElementType.java'
    with tempfile.TemporaryDirectory(prefix='pdsync-apksig-api26-') as temporary:
        classes = Path(temporary) / 'classes'
        classes.mkdir()
        subprocess.run([java, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
                        '-bootclasspath', str(android_jar), '-classpath', str(source),
                        '-encoding', 'UTF-8', '-d', str(classes), str(source_java)], check=True)
        helper = (classes / (HELPER + '.class')).read_bytes()
        with zipfile.ZipFile(source) as archive:
            if HELPER + '.class' in archive.namelist():
                raise ValueError('Unexpected pre-existing compatibility helper')
            entries = {item.filename: archive.read(item) for item in archive.infolist() if not item.is_dir()}
        entries[PARSER] = patch_parser(entries[PARSER])
        entries[HELPER + '.class'] = helper
        entries[NOTICE] = (
            'PD Sync API-26 compatibility modification to Android Open Source Project apksig.\n'
            'License: Apache License 2.0. Original copyright: Android Open Source Project.\n'
            'Original source: https://dl.google.com/android/repository/build-tools_r36_linux.zip\n'
            'Original jar SHA-256: ' + JAR_SHA256 + '\n'
            'Changed Asn1BerParser.getElementType(Field) to call Android26ElementType.\n'
            'This helper uses ParameterizedType and a single concrete element Class;\n'
            'no certificate validation or signing scheme has been removed.\n'
            'Locally maintained compatibility patch; not an upstream apksig release.\n'
        ).encode('utf-8')
        output.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary_output = tempfile.mkstemp(prefix=output.name + '.', suffix='.tmp', dir=output.parent)
        os.close(fd)
        try:
            with zipfile.ZipFile(temporary_output, 'w') as archive:
                for name, data in sorted(entries.items()):
                    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o644 << 16
                    archive.writestr(info, data)
            os.replace(temporary_output, output)
        finally:
            if os.path.exists(temporary_output):
                os.unlink(temporary_output)
    report = {'source_jar_sha256': JAR_SHA256, 'source_parser_sha256': CLASS_SHA256,
              'output_jar_sha256': sha(output.read_bytes()), 'helper_source_sha256': sha(source_java.read_bytes()),
              'patched_entry': PARSER, 'new_entry': HELPER + '.class',
              'modification_notice_entry': NOTICE,
              'replacement_method': 'getElementType' + DESCRIPTOR,
              'official_source': 'https://dl.google.com/android/repository/build-tools_r36_linux.zip',
              'license': 'Apache-2.0', 'device_tested': False}
    output.with_suffix('.compat.json').write_text(json.dumps(report, indent=2) + '\n')
    return output

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--android-jar', required=True, type=Path)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    print(build_compat_jar(args.input, args.output, args.android_jar, args.java))
