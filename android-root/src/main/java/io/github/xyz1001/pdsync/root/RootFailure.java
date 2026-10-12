package io.github.xyz1001.pdsync.root;

import java.io.IOException;

/** Fixed public diagnostics only; never send path contents or exception messages over the wire. */
public final class RootFailure extends IOException {
    public final RootProtocol.Status status;
    public RootFailure(RootProtocol.Status status) { super(status.name()); this.status = status; }
    public RootFailure(RootProtocol.Status status, Throwable cause) { super(status.name(), cause); this.status = status; }
}
