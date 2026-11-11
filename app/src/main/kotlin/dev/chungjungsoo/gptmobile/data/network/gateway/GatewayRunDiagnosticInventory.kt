package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.dao.GatewayRunDiagnosticState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Capture-time database state, not inferred from the absence of a rotated terminal log. */
internal fun gatewayRunDiagnosticInventory(rows: List<GatewayRunDiagnosticState>): JsonObject = buildJsonObject {
    put("availability", "observed")
    put("captured_at_utc", System.currentTimeMillis())
    put("complete", rows.size <= 128)
    put("limit", 128)
    put("scope", "Saved non-private runs on the selected gateway route or profile; active runs are listed first.")
    put(
        "runs",
        JsonArray(
            rows.take(128).map { row ->
                val fields = GatewayDiagnosticEvent.fields(
                    buildJsonObject {
                        put("run_id", row.runId)
                        row.jobId?.let { put("job_id", it) }
                    }
                )
                buildJsonObject {
                    fields["run_id"]?.let { put("run_id", it) }
                    fields["job_id"]?.let { put("job_id", it) }
                    val active = row.status in setOf("QUEUED", "RUNNING", "WAITING_PROVIDER_LIMIT", "WAITING_REMOTE_JOB")
                    val known = active || row.status in GatewayDiagnosticSchema.enumAttributes.getValue("terminal_status")
                    put("status", row.status.takeIf { known } ?: "UNKNOWN")
                    put("active", active)
                    put("checkpoint_sequence", row.checkpointSequence.coerceAtLeast(-1))
                    put("ownership_fence", row.ownershipFence.coerceAtLeast(0))
                    put("checkpoint_digest", GatewayDiagnosticEvent.digest("${row.runId}:${row.ownershipFence}:${row.checkpointSequence}"))
                    put("acceptance_evidence", "separate_terminal_receipt_required")
                    if (!active) {
                        val failure = dev.chungjungsoo.gptmobile.data.agent.QueuedRunFailure.evidence(row.terminalError)
                        val reason = failure.reason.takeIf { row.terminalError != null && row.status !in setOf("COMPLETED", "CANCELED", "CANCELLED") && it in GatewayDiagnosticSchema.enumAttributes.getValue("reason_code") }
                        put(
                            "terminal_receipt",
                            buildJsonObject {
                                put("status", row.status.takeIf { known } ?: "UNKNOWN")
                                reason?.let { put("reason_code", it) }
                                failure.phase.takeIf { reason != null && it in GatewayDiagnosticSchema.enumAttributes.getValue("failed_stage") }?.let { put("failed_stage", it) }
                                put("next_action", gatewayDiagnosticRecovery(reason, row.status, "PRECHECK_REJECTED".takeIf { failure.dispatched == false }))
                                put("last_completed_stage", "unknown")
                            }
                        )
                    }
                }
            }
        )
    )
}
