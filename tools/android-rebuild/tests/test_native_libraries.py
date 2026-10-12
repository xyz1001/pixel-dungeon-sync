#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Synthetic ELF metadata tests; never load or execute a native library."""
import importlib.util
from pathlib import Path
import struct,sys,tempfile,unittest
TOOLS=Path(__file__).resolve().parents[1];sys.path.insert(0,str(TOOLS))
spec=importlib.util.spec_from_file_location('android_build',TOOLS/'build-apk.py');build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)

class NativeLibraryTests(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name)
    def elf(self,abi,alignment=16384):
        cls,machine={'armeabi-v7a':(1,40),'arm64-v8a':(2,183),'x86':(1,3),'x86_64':(2,62)}[abi]
        data=bytearray(256);data[:7]=b'\x7fELF'+bytes([cls,1,1]);struct.pack_into('<HHI',data,16,3,machine,1)
        struct.pack_into('<I' if cls==1 else '<Q',data,28 if cls==1 else 32,64)
        struct.pack_into('<HH',data,42 if cls==1 else 54,32 if cls==1 else 56,1)
        struct.pack_into('<I',data,64,1);struct.pack_into('<I' if cls==1 else '<Q',data,92 if cls==1 else 112,alignment)
        path=self.root/(abi+'.so');path.write_bytes(data);return path
    def test_four_abis_are_explicit_and_preserve_source_bytes(self):
        for abi in ('armeabi-v7a','arm64-v8a','x86','x86_64'):
            path=self.elf(abi);name=abi+'/libpdsync_root_atomic.so'
            result=build.native_library_inputs([name+'='+str(path)])
            self.assertEqual(result[name],(path,path.read_bytes()))
    def test_abi_or_class_mismatch_rejected(self):
        path=self.elf('x86_64')
        for abi in ('x86','armeabi-v7a','arm64-v8a'):
            with self.assertRaises(ValueError):build.native_library_inputs([abi+'/libroot.so='+str(path)])
    def test_no_globs_traversal_or_duplicate_destinations(self):
        path=self.elf('x86_64')
        for name in ('x86_64/../libroot.so','x86_64/a/libroot.so','mips/libroot.so','x86_64/root.so','x86_64/liba-b.so','/x86_64/libroot.so'):
            with self.assertRaises(ValueError):build.native_library_inputs([name+'='+str(path)])
        mapping='x86_64/libroot.so='+str(path)
        with self.assertRaises(ValueError):build.native_library_inputs([mapping,mapping])
    def test_page_alignment_required(self):
        for align in (0,1,4096,16385):
            with self.assertRaises(ValueError):build.native_library_inputs(['x86_64/libroot.so='+str(self.elf('x86_64',align))])
    def test_malformed_or_executable_elf_rejected(self):
        path=self.elf('arm64-v8a')
        original=path.read_bytes()
        for offset,value in ((0,0),(5,2),(6,0),(16,2),(54,1),(56,0),(64,0)):
            data=bytearray(original);data[offset]=value;path.write_bytes(data)
            with self.assertRaises(ValueError):build.native_library_inputs(['arm64-v8a/libroot.so='+str(path)])
    def test_no_inputs_leave_embedded_build_unchanged(self):
        self.assertEqual({},build.native_library_inputs([]))

if __name__=='__main__':unittest.main()
