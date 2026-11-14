package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
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

    fun preflight(profileUid: String?, model: String?, ready: Boolean, reasons: List<String>, capturedAtMillis: Long = System.currentTimeMillis(), policyState: String? = null): IssueReportDiagnostics? =
        IssueReportDiagnostics.fromFields(
            "STRUCTURED_EVENT",
            buildMap {
                put("diagnostic_area", "GATEWAY")
                put("gateway_admission_ready", ready)
                put("gateway_failure_phase", "provider_admission")
                put("gateway_preflight_blocker_count", reasons.size)
                put("gateway_validation_policy_state", policyState)
                reasons.firstOrNull()?.let { put("gateway_preflight_reason", it) }
                profileUid?.let { put("profile_hash", researchHash(it)) }
                model?.let { put("model_hash", researchHash(it)) }
            },
            capturedAtMillis
        )

    fun queue(blocker: String, active: Int, deliveries: Int, unsettled: Int, legacy: Int, cached: Int, capturedAtMillis: Long = System.currentTimeMillis()): IssueReportDiagnostics? =
        IssueReportDiagnostics.fromFields(
            "STRUCTURED_EVENT",
            mapOf(
                "diagnostic_area" to "GATEWAY",
                "queue_blocker" to blocker,
                "queue_active_leases" to active,
                "queue_pending_deliveries" to deliveries,
                "queue_unsettled_submissions" to unsettled,
                "queue_legacy_runs" to legacy,
                "queue_cached_active_jobs" to cached,
                "queue_concurrency_limit" to 1
            ),
            capturedAtMillis
        )

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
