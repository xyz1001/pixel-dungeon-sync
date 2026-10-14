package io.github.xyz1001.pdsync.android;

import io.github.xyz1001.pdsync.root.RootProtocol;
import pdsync.DiagnosticDetails;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;

/** Records existing verified responses only. Never probes Root and never records package/UID IDs. */
final class RootDiagnosticLogging {
    private String lastObservation;

    void response(RootProtocol.Response reply) {
        String state = reply.observationState + ":" + reply.reliable;
        if (reply.command == RootProtocol.Command.OBSERVE && !changed(state)) return;
        DiagnosticDetails.Builder details = DiagnosticDetails.builder().mode("ROOT_DIRECT").readinessCode("OK").rootCommand(reply.command.name())
                .failureSource("ROOT").rootStage(reply.diagnosticStage.name()).rootCategory(reply.diagnosticCategory.name())
                .errno(reply.errno).observationState(reply.observationState).observationReliable(reply.reliable);
        // A PROBE/OBSERVE with no inventory does not prove an empty save directory.
        if (reply.contentState != null && !reply.contentState.isEmpty())
            details.saveLayoutState(reply.contentState).fileCount(reply.fileCount).contentBytes(reply.contentBytes);
        DiagnosticLog.record(DiagnosticStage.BRIDGE, DiagnosticResult.SUCCEEDED, DiagnosticFailure.NONE, details.build());
    }

    void failure(RootProtocol.Command command, Throwable error) {
        String code = BridgeDiagnostics.code(error);
        if (command == RootProtocol.Command.OBSERVE && !changed("failure:" + code)) return;
        DiagnosticFailure failure = AndroidDiagnosticLogging.classify(error);
        DiagnosticLog.record(DiagnosticStage.BRIDGE, failure == DiagnosticFailure.CANCELED
                ? DiagnosticResult.CANCELED : DiagnosticResult.FAILED, failure,
                AndroidDiagnosticLogging.failureDetailsBuilder(error).rootCommand(command.name()).build());
    }

    private synchronized boolean changed(String observation) {
        if (observation.equals(lastObservation)) return false;
        lastObservation = observation;
        return true;
    }
}
