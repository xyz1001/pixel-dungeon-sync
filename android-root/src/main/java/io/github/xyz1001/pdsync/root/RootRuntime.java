package io.github.xyz1001.pdsync.root;

import java.io.IOException;
import io.github.xyz1001.pdsync.root.mutation.MutationOs;
import io.github.xyz1001.pdsync.root.mutation.RootMutationReadGuard;

/** Injectable system boundary. No arbitrary command or unbound target path is exposed. */
public interface RootRuntime {
    interface ReadGuard extends AutoCloseable {
        void requireClear() throws IOException;
        void revalidate() throws IOException;
        @Override void close() throws IOException;
    }
    default ReadGuard openReadGuard(RootBinding binding) throws IOException {
        final RootMutationReadGuard guard=RootMutationReadGuard.open(readMutationOs(binding),binding);
        return new ReadGuard() {
            public void requireClear()throws IOException{guard.requireClear();}
            public void revalidate()throws IOException{guard.revalidate();}
            public void close()throws IOException{guard.close();}
        };
    }
    int effectiveUid();
    RootBinding installed(RootBinding requested) throws IOException;
    /** Default host implementations retain full verification; Android uses a captured one-job metadata fence. */
    default void verifyInstalledMetadata(RootBinding requested) throws IOException {
        if(!requested.matchesInstalled(installed(requested)))throw new IOException("Root target changed");
    }
    /** Read-only journal/lock inspection never requires loading a mutation library. */
    default MutationOs readMutationOs(RootBinding requested) throws IOException {
        throw new IOException("Root journal inspection unavailable");
    }
    /** Writable adapter construction is explicit; no root process is started by this method. */
    default MutationOs mutationOs(RootBinding requested) throws IOException {
        throw new IOException("Root mutation adapter unavailable");
    }
    RootFileSystem fileSystem();
    RootUidState.Observation observe(int boundUid) throws IOException;
    void quietWait() throws IOException;
}
