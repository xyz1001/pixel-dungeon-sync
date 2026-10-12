package io.github.xyz1001.pdsync.root;

import java.io.IOException;

/** Injectable system boundary. No arbitrary exec or writable target operation is exposed. */
public interface RootRuntime {
    int effectiveUid();
    RootBinding installed(RootBinding requested) throws IOException;
    RootFileSystem fileSystem();
    RootUidState.Observation observe(int boundUid) throws IOException;
    void quietWait() throws IOException;
}
