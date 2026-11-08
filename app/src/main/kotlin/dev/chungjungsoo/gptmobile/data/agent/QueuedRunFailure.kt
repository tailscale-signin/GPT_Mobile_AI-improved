package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionDispatchState
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionRepair
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionRetryability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class QueuedFailureEvidence(
    val reason: String,
    val phase: String,
    val dispatched: Boolean?,
    val remoteAcceptance: String,
    val retryable: Boolean,
    val repair: String
)

/** Local queue persistence and gateway acceptance are independent facts. No unrestricted cause is exported. */
object QueuedRunFailure {
    private const val SCHEMA = "gptmobile.queued-failure.v1"
    private val gatewayCodes = GatewayAdmissionReason.entries.mapTo(hashSetOf()) { "GATEWAY_${it.name}" }
    private val codes = gatewayCodes + setOf("REASONING_OFF_UNSUPPORTED", "REASONING_ON_UNSUPPORTED", "REASONING_CAPABILITY_UNVERIFIED", "REASONING_CAPABILITY_UNAVAILABLE", "REASONING_CAPABILITY_TIMEOUT", "GATEWAY_OUTCOME_UNKNOWN", "PREPARATION_FAILED")
    private val repairs = GatewayAdmissionRepair.entries.mapTo(hashSetOf()) { it.name } + setOf("CHANGE_REASONING_SETTINGS", "RECONCILE_ORIGINAL_COMMAND", "INSPECT_RETAINED_CAUSE")

    fun retained(error: Throwable): String {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (causes.size < 16) {
            val cause = current ?: break
            if (causes.any { it === cause }) break
            causes += cause
            current = cause.cause
        }
        val gateway = causes.filterIsInstance<GatewayAdmissionException>().firstOrNull()
        val reasoning = causes.filterIsInstance<ReasoningAdmissionException>().firstOrNull()
        val capability = causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException>().firstOrNull()
        val uncertain = causes.any { GatewayOutcomeRecovery.requiresReconciliation(it.message.orEmpty()) }
        val evidence = when {
            uncertain -> QueuedFailureEvidence("GATEWAY_OUTCOME_UNKNOWN", "gateway_dispatch", null, "UNCONFIRMED", false, "RECONCILE_ORIGINAL_COMMAND")
            gateway != null -> QueuedFailureEvidence(
                "GATEWAY_${gateway.reason.name}",
                if (gateway.phase.name == "RECONCILIATION") "gateway_recovery" else "connection_preflight",
                false.takeIf { gateway.dispatchState == GatewayAdmissionDispatchState.NOT_DISPATCHED },
                if (gateway.dispatchState == GatewayAdmissionDispatchState.NOT_DISPATCHED) "NOT_ACCEPTED" else "UNCONFIRMED",
                gateway.retryability == GatewayAdmissionRetryability.SAFE_READ,
                if (gateway.retryability == GatewayAdmissionRetryability.RECONCILE_ORIGINAL_COMMAND) "RECONCILE_ORIGINAL_COMMAND" else gateway.repair.name
            )
            reasoning != null -> QueuedFailureEvidence("REASONING_${reasoning.requested}_UNSUPPORTED", "provider_admission", false, "NOT_ACCEPTED", false, "CHANGE_REASONING_SETTINGS")
            capability != null -> QueuedFailureEvidence(capability.code, "connection_preflight", false, "NOT_ACCEPTED", false, "CHECK_CONNECTION")
            else -> QueuedFailureEvidence("PREPARATION_FAILED", "planning", null, "UNCONFIRMED", false, "INSPECT_RETAINED_CAUSE")
        }
        return buildJsonObject {
            put("schema", SCHEMA)
            put("reason", evidence.reason)
            put("phase", evidence.phase)
            put("dispatch_state", if (evidence.dispatched == false) "NOT_DISPATCHED" else "UNKNOWN")
            put("local_state", "SAVED_LOCALLY")
            put("remote_acceptance", evidence.remoteAcceptance)
            put("retryable", evidence.retryable)
            put("repair", evidence.repair)
            gateway?.httpStatus?.let { put("http_status", it) }
            gateway?.wireCode?.let { put("admission_code", it) }
        }.toString()
    }

    internal fun evidence(retained: String?): QueuedFailureEvidence {
        val root = retained?.takeIf { it.length <= 4096 }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        fun text(key: String) = (root?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val reason = text("reason")
        if (text("schema") == SCHEMA && reason in codes) {
            val unsent = text("dispatch_state") == "NOT_DISPATCHED" && text("remote_acceptance") == "NOT_ACCEPTED"
            return QueuedFailureEvidence(
                requireNotNull(reason),
                text("phase")?.takeIf { it in setOf("planning", "connection_preflight", "provider_admission", "gateway_dispatch", "gateway_recovery") } ?: "stage_not_recorded",
                false.takeIf { unsent },
                if (unsent) "NOT_ACCEPTED" else "UNCONFIRMED",
                (root?.get("retryable") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true,
                text("repair")?.takeIf { it in repairs } ?: "INSPECT_RETAINED_CAUSE"
            )
        }
        val legacy = retained?.let { ProfileFailureRecord.classify(it) }
        return QueuedFailureEvidence(legacy?.category ?: "PREPARATION_FAILED", legacy?.phase ?: "stage_not_recorded", null, "UNCONFIRMED", false, "INSPECT_RETAINED_CAUSE")
    }
}
