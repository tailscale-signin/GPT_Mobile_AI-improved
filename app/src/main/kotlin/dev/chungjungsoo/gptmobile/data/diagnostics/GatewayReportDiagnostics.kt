package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.Locale

/** Bounded observations from admission or already-owned reads; never discovers or resumes a job. */
internal object GatewayReportDiagnostics {
    /** Retain only an actual handshake/readiness/admission observation; this never probes or creates a job. */
    fun admission(
        profileUid: String?,
        modelId: String?,
        serverBuild: String? = null,
        gatewayVersion: String? = null,
        protocolVersion: Int? = null,
        stage: String? = null,
        httpStatus: Int? = null,
        ready: Boolean? = null,
        failureCode: String? = null,
        reasonCode: String? = null,
        capturedAtMillis: Long = System.currentTimeMillis(),
        operationId: String? = null
    ): IssueReportDiagnostics? {
        val fields = buildMap<String, Any?> {
            put("diagnostic_area", "GATEWAY")
            put("diagnostic_capture_mode", "INCIDENT")
            profileUid?.takeIf { it.isNotBlank() && it.length <= 256 }?.let { put("profile_hash", researchHash(it)) }
            modelId?.takeIf { it.isNotBlank() && it.length <= 256 }?.let { put("model_hash", researchHash(it)) }
            // A manifest digest is public package identity, never a credential or private path.
            put("gateway_manifest_hash", serverBuild?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) })
            put("gateway_version", gatewayVersion)
            put("gateway_protocol_version", protocolVersion)
            put("gateway_failure_phase", stage)
            put("gateway_http_status", httpStatus)
            put("gateway_admission_ready", ready)
            put("gateway_reason_code", reasonCode)
            IssueReportContext.identifierHash("gateway-operation", operationId)?.let { put("gateway_operation_id_hash", it) }
            IssueReportContext.identifierHash("error_code", failureCode)?.let { put("original_error_code_hash", it) }
        }
        return IssueReportDiagnostics.fromFields("STRUCTURED_EVENT", fields, capturedAtMillis)?.takeIf { it.values.isNotEmpty() }
    }

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
