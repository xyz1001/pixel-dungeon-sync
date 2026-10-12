package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/* loaded from: classes.dex */
final class TrustedPrivateDirectory {
    private final Path app;
    private final State appState;
    private final StatReader reader;
    private final Path root;
    private final State rootState;
    private final Path suppliedApp;
    private final Path suppliedRoot;
    private final int uid;

    interface StatReader {
        State lstat(Path path) throws IOException;
    }

    static final class State {
        final long device;
        final boolean directory;
        final long inode;
        final int uid;

        State(long j, long j2, int i, boolean z) {
            this.device = j;
            this.inode = j2;
            this.uid = i;
            this.directory = z;
        }

        boolean same(State state) {
            return state.directory && this.directory && this.device == state.device && this.inode == state.inode && this.uid == state.uid;
        }
    }

    private TrustedPrivateDirectory(Path path, Path path2, Path path3, Path path4, State state, State state2, int i, StatReader statReader) {
        this.suppliedApp = path;
        this.suppliedRoot = path2;
        this.app = path3;
        this.root = path4;
        this.appState = state;
        this.rootState = state2;
        this.uid = i;
        this.reader = statReader;
    }

    static TrustedPrivateDirectory resolve(Path path, Path path2, int i, StatReader statReader) throws IOException {
        requireAbsolute(path);
        requireAbsolute(path2);
        String string = path2.getFileName().toString();
        if ((!"cache".equals(string) && !"files".equals(string)) || !path.equals(path2.getParent())) {
            throw unsafe("Android private directory is not an immediate cache/files child");
        }
        State stateDirectory = directory(statReader.lstat(path), i);
        State stateDirectory2 = directory(statReader.lstat(path2), i);
        Path pathResolve = path.getParent().toRealPath(new LinkOption[0]).resolve(path.getFileName());
        TrustedPrivateDirectory trustedPrivateDirectory = new TrustedPrivateDirectory(path, path2, pathResolve, pathResolve.resolve(string), stateDirectory, stateDirectory2, i, statReader);
        trustedPrivateDirectory.checkedPath();
        return trustedPrivateDirectory;
    }

    Path checkedPath() throws IOException {
        same(this.appState, this.suppliedApp);
        same(this.rootState, this.suppliedRoot);
        if (this.root.equals(this.suppliedRoot.toRealPath(new LinkOption[0]))) {
            Path path = this.root;
            if (path.equals(path.toRealPath(new LinkOption[0]))) {
                same(this.appState, this.app);
                same(this.rootState, this.root);
                same(this.appState, this.suppliedApp);
                same(this.rootState, this.suppliedRoot);
                return this.root;
            }
        }
        throw unsafe("Android private directory changed during resolution");
    }

    TemporaryDirectory createTemporaryDirectory(String str) throws IOException {
        Path pathCreateTempDirectory = Files.createTempDirectory(checkedPath(), str, new FileAttribute[0]);
        TemporaryDirectory temporaryDirectory = new TemporaryDirectory(pathCreateTempDirectory, directory(this.reader.lstat(pathCreateTempDirectory), this.uid));
        temporaryDirectory.path();
        return temporaryDirectory;
    }

    final class TemporaryDirectory implements AutoCloseable {
        private boolean closed;
        private final State state;
        private final Path temporary;

        private TemporaryDirectory(Path path, State state) {
            this.temporary = path;
            this.state = state;
        }

        Path path() throws IOException {
            if (this.closed) {
                throw TrustedPrivateDirectory.unsafe("Preview directory is already closed");
            }
            TrustedPrivateDirectory.this.checkedPath();
            TrustedPrivateDirectory.this.same(this.state, this.temporary);
            Path path = this.temporary;
            if (path.equals(path.toRealPath(new LinkOption[0]))) {
                TrustedPrivateDirectory.this.same(this.state, this.temporary);
                return this.temporary;
            }
            throw TrustedPrivateDirectory.unsafe("Preview directory contains a symbolic link");
        }

        @Override // java.lang.AutoCloseable
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            Stream<Path> streamWalk = Files.walk(path(), new FileVisitOption[0]);
            try {
                List<Path> list = (List) streamWalk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                if (streamWalk != null) {
                    streamWalk.close();
                }
                for (Path path : list) {
                    path();
                    if (!path.equals(path.toRealPath(new LinkOption[0]))) {
                        throw TrustedPrivateDirectory.unsafe("Preview cleanup encountered a symbolic link");
                    }
                    Files.delete(path);
                }
                this.closed = true;
            } catch (Throwable th) {
                if (streamWalk != null) {
                    try {
                        streamWalk.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void same(State state, Path path) throws IOException {
        if (!state.same(directory(this.reader.lstat(path), this.uid))) {
            throw unsafe("Android private directory was replaced");
        }
    }

    private static State directory(State state, int i) throws IOException {
        if (state.directory && state.uid == i) {
            return state;
        }
        throw unsafe("Android private directory is linked, not a directory, or owned by another UID");
    }

    private static void requireAbsolute(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || path.getParent() == null || path.getFileName() == null) {
            throw unsafe("Android did not provide an absolute private directory");
        }
        for (Path path2 : path) {
            if (".".equals(path2.toString()) || "..".equals(path2.toString())) {
                throw unsafe("Android private directory contains a path traversal");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static IOException unsafe(String str) {
        return new IOException(str);
    }
}
