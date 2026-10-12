#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline tests for explicit Android cross-module Java inputs."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOLS))
spec=importlib.util.spec_from_file_location('android_build',TOOLS/'build-apk.py')
build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)

class JavaSourcesTests(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.root=Path(temp.name);self.repo=self.root/'repo';self.repo.mkdir()
        self.main=self.repo/'android-app';self.helper=self.repo/'android-helper'
        self.generated=self.root/'generated';self.generated.mkdir()
        self.a=self.write('android-app/src/main/java/p/Main.java')
        self.h=self.write('android-helper/src/main/java/p/Shared.java')
        self.runner=self.write('android-root/src/main/java/p/RootRunner.java')
        self.g=self.generated/'R.java';self.g.write_text('class R {}')
    def write(self,name):
        f=self.repo/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text('class Sample {}');return f
    def declare(self,text):
        (self.main/'additional-java-sources.txt').write_text(text)
    def collect(self):return build.collect_java_sources(self.main,self.generated,self.repo)
    def test_unmodified_module_does_not_include_other_modules(self):
        self.assertEqual(set(self.collect()),{self.a,self.g})
    def test_explicit_directory_and_independent_file_are_included(self):
        self.declare('# Reviewed inputs\nandroid-root/src/main/java\nandroid-helper/src/main/java/p/Shared.java\n')
        self.assertEqual(set(self.collect()),{self.a,self.h,self.runner,self.g})
    def test_helper_never_inherits_main_root_declaration(self):
        self.declare('android-root/src/main/java\n')
        self.assertEqual(set(build.collect_java_sources(self.helper,self.generated,self.repo)),{self.h,self.g})
    def test_duplicates_are_deduplicated_and_order_is_stable(self):
        self.declare('android-root/src/main/java\nandroid-root/src/main/java/p/RootRunner.java\nandroid-app/src/main/java\n')
        self.assertEqual(self.collect(),sorted({self.a,self.runner,self.g}))
    def test_missing_empty_nonjava_and_globs_rejected(self):
        (self.repo/'empty').mkdir();self.write('payload.txt')
        for value in ['missing','empty','payload.txt','android-root/**/*.java','../outside','/tmp/source.java','a\\b']:
            with self.subTest(value=value):
                self.declare(value)
                with self.assertRaises(ValueError):self.collect()
    def test_symlink_file_or_parent_rejected(self):
        (self.repo/'link.java').symlink_to(self.h)
        (self.repo/'linkdir').symlink_to(self.runner.parent,target_is_directory=True)
        for value in ['link.java','linkdir','linkdir/RootRunner.java']:
            with self.subTest(value=value):
                self.declare(value)
                with self.assertRaises(ValueError):self.collect()
    def test_symlink_inside_declared_directory_rejected(self):
        (self.runner.parent/'Escaped.java').symlink_to(self.h)
        self.declare('android-root/src/main/java')
        with self.assertRaises(ValueError):self.collect()
    def test_declaration_symlink_rejected(self):
        target=self.root/'inputs';target.write_text('android-root/src/main/java')
        (self.main/'additional-java-sources.txt').symlink_to(target)
        with self.assertRaises(ValueError):self.collect()

if __name__=='__main__':unittest.main()
