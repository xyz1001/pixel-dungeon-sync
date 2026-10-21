#!/usr/bin/env python3
"""Production SafeSaveTree scan with real temporary files and host-only Android OS adapters.

This proves scanner/filter/hash behavior, not Android Binder, SELinux or descriptor races.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_android_api_compat import write_java, PRODUCTION, JAVA


class SaveLayoutSnapshotTests(unittest.TestCase):
    def test_known_non_save_files_do_not_enter_snapshot_or_hash(self):
        with tempfile.TemporaryDirectory(prefix='pdsync-save-layout-snapshot-') as temporary:
            root = Path(temporary)
            fixtures = {
                'android.content.pm.PackageManager': 'package android.content.pm; public class PackageManager { }',
                'android.os.Process': '''package android.os; public class Process {
                    public static int myUid() { return 42; }
                }''',
                'android.system.ErrnoException': '''package android.system; public class ErrnoException extends Exception {
                    public ErrnoException(Throwable cause) { super(cause); }
                }''',
                'android.system.OsConstants': '''package android.system; public class OsConstants {
                    public static final int O_RDONLY=0, O_NOFOLLOW=131072;
                    public static boolean S_ISDIR(int mode) { return (mode & 0170000)==0040000; }
                    public static boolean S_ISREG(int mode) { return (mode & 0170000)==0100000; }
                }''',
                'android.system.StructStat': '''package android.system; public class StructStat {
                    public long st_dev,st_ino,st_nlink,st_size,mtime;
                    public int st_mode,st_uid=42;
                }''',
                'android.system.Os': '''package android.system;
                    import java.io.*; import java.nio.file.*; import java.nio.file.attribute.*; import java.util.*;
                    public class Os {
                        private static final Map<FileDescriptor,FileInputStream> streams=new IdentityHashMap<>();
                        private static final Map<FileDescriptor,String> paths=new IdentityHashMap<>();
                        public static final List<String> opened=new ArrayList<>();
                        public static StructStat lstat(String name) throws ErrnoException {
                            try {
                                Map<String,Object> values=Files.readAttributes(Path.of(name),"unix:dev,ino,mode,nlink,size,lastModifiedTime",LinkOption.NOFOLLOW_LINKS);
                                StructStat stat=new StructStat();
                                stat.st_dev=((Number)values.get("dev")).longValue(); stat.st_ino=((Number)values.get("ino")).longValue();
                                stat.st_mode=((Number)values.get("mode")).intValue(); stat.st_nlink=((Number)values.get("nlink")).longValue();
                                stat.st_size=((Number)values.get("size")).longValue(); stat.mtime=((FileTime)values.get("lastModifiedTime")).toMillis();
                                return stat;
                            } catch(IOException error) { throw new ErrnoException(error); }
                        }
                        public static FileDescriptor open(String name,int flags,int mode) throws ErrnoException {
                            try {
                                if((flags & OsConstants.O_NOFOLLOW)==0 || Files.isSymbolicLink(Path.of(name))) throw new IOException("unsafe fixture open");
                                FileInputStream stream=new FileInputStream(name); FileDescriptor fd=stream.getFD();
                                streams.put(fd,stream); paths.put(fd,name); opened.add(Path.of(name).getFileName().toString()); return fd;
                            } catch(IOException error) { throw new ErrnoException(error); }
                        }
                        public static StructStat fstat(FileDescriptor fd) throws ErrnoException { return lstat(paths.get(fd)); }
                        public static void close(FileDescriptor fd) throws ErrnoException {
                            try { streams.remove(fd).close(); paths.remove(fd); } catch(IOException error) { throw new ErrnoException(error); }
                        }
                    }''',
                'io.github.xyz1001.pdsync.bridge.GameGuard': '''package io.github.xyz1001.pdsync.bridge;
                    final class GameGuard { void requireQuiescent() { } }
                ''',
                'io.github.xyz1001.pdsync.bridge.AndroidApiCompat': '''package io.github.xyz1001.pdsync.bridge;
                    final class AndroidApiCompat {
                        static int closeOnExecFlag() { return 0; }
                        static long modifiedMillis(android.system.StructStat s) { return s.mtime; }
                        static String statIdentity(android.system.StructStat s) {
                            return s.st_dev+":"+s.st_ino+":"+s.st_mode+":"+s.st_size+":"+s.mtime;
                        }
                    }''',
                'io.github.xyz1001.pdsync.bridge.SaveLayoutSnapshotChecks': r'''
                    package io.github.xyz1001.pdsync.bridge;
                    import java.nio.file.*; import java.util.*;
                    public final class SaveLayoutSnapshotChecks {
                        static int assertions;
                        static void check(boolean condition,String message) { assertions++; if(!condition)throw new AssertionError(message); }
                        public static void main(String[] args) throws Exception {
                            Path base=Path.of(args[0]), root=Files.createDirectory(base.resolve("files"));
                            Path slot=Files.createDirectory(root.resolve("game1"));
                            Files.writeString(slot.resolve("game.dat"),"unchanged-game-progress");
                            Files.writeString(root.resolve("rankings.dat"),"unchanged-rankings");
                            SafeSaveTree tree=new SafeSaveTree(root.toFile(),new GameGuard());
                            SafeSaveTree.State before=tree.scan(null,true);
                            String fingerprint=before.inventory.fingerprint();
                            String[] nonSaves={"profileInstalled","profileinstaller_profileWrittenFor_lastUpdateTime.dat","keybinds.dat"};
                            for(String name:nonSaves) Files.writeString(root.resolve(name),"private-non-save-value");
                            SafeSaveTree.State added=tree.scan(null,true);
                            check(fingerprint.equals(added.inventory.fingerprint()),"adding known non-save files leaves save fingerprint unchanged");
                            check(before.inventory.hashes.equals(added.inventory.hashes),"same save file hashes");
                            check(added.content.excludedEntries==3 && added.content.transferable(),"all known regular non-save files excluded");
                            for(String name:nonSaves) Files.writeString(root.resolve(name),"changed-private-preference-or-runtime-marker");
                            Path copy=Files.createDirectory(base.resolve("copy"));
                            SafeSaveTree.State changed=tree.scan(copy.toFile(),true);
                            check(fingerprint.equals(changed.inventory.fingerprint()),"non-save content changes do not alter fingerprint");
                            check(added.same(changed),"non-save content changes do not alter save stability metadata");
                            check(Files.readString(copy.resolve("game1/game.dat")).equals("unchanged-game-progress"),"actual save is copied unchanged");
                            for(String name:nonSaves) {
                                check(!android.system.Os.opened.contains(name),"scanner never opens non-save content: "+name);
                                check(!Files.exists(copy.resolve(name)),"non-save file not copied: "+name);
                                Files.delete(root.resolve(name)); Files.createDirectory(root.resolve(name));
                                try { tree.scan(null,true); throw new AssertionError("directory accepted: "+name); }
                                catch(BridgeFailure denied) { check("UNRECOGNIZED_SAVE_LAYOUT".equals(denied.code),"directory still blocks transfer"); }
                                Files.delete(root.resolve(name)); Files.createSymbolicLink(root.resolve(name),slot.resolve("game.dat"));
                                try { tree.scan(null,true); throw new AssertionError("symlink accepted: "+name); }
                                catch(BridgeFailure denied) { check("UNRECOGNIZED_SAVE_LAYOUT".equals(denied.code),"symlink still blocks transfer"); }
                                Files.delete(root.resolve(name)); Files.writeString(root.resolve(name),"restored non-save file");
                            }
                            for(String name:List.of(".spdtmp","unrecognized.dat","keybinds.dat.extra","Profileinstaller_profileWrittenFor_lastUpdateTime.dat")) {
                                Files.writeString(root.resolve(name),"unknown");
                                try { tree.scan(null,true); throw new AssertionError("unknown file accepted: "+name); }
                                catch(BridgeFailure denied) { check("UNRECOGNIZED_SAVE_LAYOUT".equals(denied.code),"unknown file still blocks transfer"); }
                                Files.delete(root.resolve(name));
                            }
                            Files.writeString(slot.resolve("depth1.dat"),"level bytes retained only as hash");
                            Files.setLastModifiedTime(slot.resolve("game.dat"),java.nio.file.attribute.FileTime.fromMillis(1234567890123L));
                            android.system.Os.opened.clear();
                            SaveArchive.Observation observation=tree.observe();
                            check(observation.inventory.hashes.size()==3 && android.system.Os.opened.size()==3,"single observation opens each allowed source exactly once");
                            check(observation.payloads.size()==2 && !observation.payloads.containsKey("game1/depth1.dat"),"level bytes excluded from observation transport");
                            check(observation.modifiedMillis.get("game1/game.dat")==1234567890123L,"actual source millisecond timestamp retained");
                            check(new String(observation.payloads.get("game1/game.dat"),java.nio.charset.StandardCharsets.UTF_8).equals("unchanged-game-progress"),"summary receives real captured bytes");
                            Path legacyBackup=Files.writeString(base.resolve("legacy.zip"),"existing backup");
                            SafeSaveTree.deleteEntry(slot.toFile(),root.toFile(),()->{});
                            check(!Files.exists(slot),"old slot removed directly without rollback copy");
                            check(Files.readString(legacyBackup).equals("existing backup"),"existing backup untouched");
                            check(Files.exists(root.resolve("keybinds.dat")),"non-save data untouched by replacement");
                            Path outside=Files.writeString(base.resolve("outside"),"do not touch");
                            Path linked=Files.createSymbolicLink(root.resolve("game2"),outside);
                            try { SafeSaveTree.deleteEntry(linked.toFile(),root.toFile(),()->{}); throw new AssertionError("linked target accepted"); }
                            catch(BridgeFailure denied) { check("UNSAFE_SAVE_PATH".equals(denied.code),"linked target rejected"); }
                            check(Files.readString(outside).equals("do not touch"),"symlink target preserved");
                            Files.delete(linked);
                            Path interrupted=Files.createDirectory(root.resolve("game3"));
                            Files.writeString(interrupted.resolve("game.dat"),"fixture");
                            final int[] checks={0};
                            try { SafeSaveTree.deleteEntry(interrupted.toFile(),root.toFile(),()->{
                                if(++checks[0]==3) throw new java.io.IOException("injected deletion failure");
                            }); throw new AssertionError("injected failure ignored"); }
                            catch(java.io.IOException expected) { check(Files.exists(interrupted),"failed deletion remains observable without rollback"); }
                            check(!Files.exists(base.resolve("backups")) && !Files.exists(base.resolve("removed")),"delete path creates no backup directories");
                            System.out.println("PASS: "+assertions+" production save scanner assertions with host-only Android adapters.");
                        }
                    }
                ''',
            }
            sources = [write_java(root, name, source) for name, source in fixtures.items()]
            sources += [PRODUCTION / (name + '.java') for name in ('SaveArchive', 'SaveDirectoryPolicy', 'BridgeFailure', 'SafeSaveTree')]
            classes = root / 'classes'
            classes.mkdir()
            subprocess.run([JAVA, 'com.sun.tools.javac.Main', '-source', '17', '-target', '17', '-d', str(classes), *map(str, sources)], check=True)
            subprocess.run([JAVA, '-cp', str(classes), 'io.github.xyz1001.pdsync.bridge.SaveLayoutSnapshotChecks', str(root)], check=True)


if __name__ == '__main__':
    unittest.main()
