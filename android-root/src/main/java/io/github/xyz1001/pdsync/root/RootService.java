package io.github.xyz1001.pdsync.root;

import java.io.IOException;

/** Single-job dispatcher, separately host-testable without loading Android classes or invoking su. */
public final class RootService {
    private final RootRuntime runtime;
    public RootService(RootRuntime runtime) { this.runtime = runtime; }
    public RootProtocol.Response execute(RootProtocol.Request request) {
        String binding = "";
        try {
            // This is the first system call, before package manager or target filesystem access.
            if (runtime.effectiveUid() != 0) return RootProtocol.Response.failure(RootProtocol.Status.NOT_ROOT, "");
            binding = request.binding.fingerprint();
            if (request.command == RootProtocol.Command.RESTORE || request.command == RootProtocol.Command.RECOVER)
                return RootProtocol.Response.failure(RootProtocol.Status.UNSUPPORTED, binding);
            requireBinding(request.binding);
            RootSaveTree tree = new RootSaveTree(runtime.fileSystem(), request.binding);
            if (request.command == RootProtocol.Command.PROBE) {
                tree.probe(); requireBinding(request.binding);
                return new RootProtocol.Response(RootProtocol.Status.OK, binding, "", 0, 0, "", "UNKNOWN", false, null);
            }
            if (request.command == RootProtocol.Command.OBSERVE) {
                tree.probe();
                RootUidState.Observation observed = runtime.observe(request.binding.uid);
                requireBinding(request.binding);
                if (!observed.reliable) throw new RootFailure(RootProtocol.Status.UNRELIABLE);
                return new RootProtocol.Response(RootProtocol.Status.OK, binding, "", 0, 0, "", observed.state, true, null);
            }
            boolean export = request.command == RootProtocol.Command.EXPORT;
            RootUidState.Observation observation = runtime.observe(request.binding.uid);
            if (export) requireQuiet(observation);
            RootSaveTree.Snapshot before = tree.scan(false);
            runtime.quietWait();
            observation = runtime.observe(request.binding.uid);
            if (export) requireQuiet(observation);
            RootSaveTree.Snapshot captured = tree.scan(export);
            if (!before.same(captured)) throw new RootFailure(RootProtocol.Status.CHANGED);
            byte[] archive = export ? tree.archive(captured) : null;
            RootSaveTree.Snapshot after = tree.scan(false);
            if (!captured.same(after)) throw new RootFailure(RootProtocol.Status.CHANGED);
            observation = runtime.observe(request.binding.uid);
            if (export) requireQuiet(observation);
            requireBinding(request.binding);
            tree.requireRootIdentity(captured);
            return new RootProtocol.Response(RootProtocol.Status.OK, binding, captured.contentState,
                    captured.inventory.hashes.size(), captured.inventory.bytes, captured.inventory.fingerprint(),
                    observation.state, observation.reliable, archive);
        } catch (RootFailure failure) {
            return RootProtocol.Response.failure(failure.status, binding);
        } catch (IOException | RuntimeException failure) {
            return RootProtocol.Response.failure(RootProtocol.Status.IO_ERROR, binding);
        }
    }
    private void requireBinding(RootBinding expected) throws IOException {
        if (!expected.matchesInstalled(runtime.installed(expected))) throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
    }
    private static void requireQuiet(RootUidState.Observation observation) throws IOException {
        if (!observation.reliable) throw new RootFailure(RootProtocol.Status.UNRELIABLE);
        if (!observation.quiescent()) throw new RootFailure(RootProtocol.Status.BUSY);
    }
}
