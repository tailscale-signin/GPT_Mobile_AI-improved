package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.Locale

/** Bounded preparation and already-owned saved-result observations; never authority to replay work. */
internal object GatewayReportDiagnostics {
    fun modelPreparation(profileUid: String?, model: String, receipt: GatewayModelPreparationReceipt, capturedAtMillis: Long = System.currentTimeMillis()): IssueReportDiagnostics? {
        if (receipt.requestedModel != model || receipt.operationId.isBlank()) return null
        val safe = receipt.sanitized()
        val failure = safe.failure ?: safe.reconciliationFailure
        val fields = buildMap<String, Any?> {
            put("diagnostic_area", "GATEWAY")
            put("diagnostic_capture_mode", "INCIDENT")
            profileUid?.takeIf(String::isNotBlank)?.let { put("profile_hash", researchHash(it)) }
            put("model_hash", researchHash(model))
            put("operation_id_hash", IssueReportContext.identifierHash("model-preparation", safe.operationId))
            put("model_preparation_state", safe.state)
            put("model_preparation_action", safe.preparationAction)
            put("model_preparation_dispatch", safe.dispatchState)
            put("model_preparation_load_accepted", safe.loadAccepted)
            put("model_preparation_load_requests", safe.loadRequests)
            put("gateway_model_state", safe.modelState)
            put("gateway_failure_category", failure?.failureCategory)
            put("gateway_dependency", failure?.dependency)
            put("gateway_upstream_status", failure?.upstreamStatus)
            put("gateway_upstream_message_code", failure?.upstreamMessageCode)
            put("gateway_reconciliation_status", safe.reconciliationFailure?.upstreamStatus)
            failure?.failureCode?.let { put("original_error_code_hash", researchHash(it)) }
            (failure?.reasonCode ?: safe.reasonCode)?.let { put("original_reason_code_hash", researchHash(it)) }
        }
        return IssueReportDiagnostics.fromFields("STRUCTURED_EVENT", fields, capturedAtMillis)
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
