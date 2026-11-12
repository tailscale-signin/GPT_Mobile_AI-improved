package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.Locale

/** Observation of an already-owned saved-result read, never a request to discover or resume a job. */
internal object GatewayReportDiagnostics {
    fun recovery(run: AgentRun, observedJobId: String, remoteStatus: String?, capturedAtMillis: Long = System.currentTimeMillis()): IssueReportDiagnostics? {
        if (run.gatewayJobId != observedJobId || observedJobId.isBlank()) return null
        val fields = buildMap<String, Any?> {
            put("diagnostic_area", "GATEWAY")
            put("diagnostic_capture_mode", "INCIDENT")
            put("run_id_hash", IssueReportContext.identifierHash("run", run.runId))
            put("job_id_hash", IssueReportContext.identifierHash("job", observedJobId))
            put("profile_hash", researchHash(run.profileUid))
            put("model_hash", researchHash(run.modelSnapshot))
            // The server state is distinct from the local terminal status. No state
            // establishes completed transport, replay permission or write approval.
            remoteStatus?.uppercase(Locale.ROOT)?.takeIf { it in states }?.let { put("gateway_state", it) }
            if (run.gatewayLastSequence >= 0 && run.ownershipFence >= 0) {
                put("checkpoint_hash", IssueReportContext.identifierHash("gateway-checkpoint", "${run.runId}:${run.ownershipFence}:${run.gatewayLastSequence}"))
            }
        }
        return IssueReportDiagnostics.fromFields("STRUCTURED_EVENT", fields, capturedAtMillis)
    }

    private val states = setOf("QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED")
}
