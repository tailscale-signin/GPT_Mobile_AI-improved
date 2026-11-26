package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Independent durable allowances; a provider cooldown or tool handoff is not a lost connection. */
internal class GitHubRecoveryProgress(checkpoint: JsonObject?, recoveryRevision: String) {
    var transportRecoveries: Int = when {
        checkpoint?.get("transport_recoveries") != null -> checkpoint.count("transport_recoveries")
        resumeCause(checkpoint) == Cause.TRANSPORT_INTERRUPTION -> checkpoint.count("interruption_attempts")
        else -> 0
    }
        private set
    var consecutiveTransportRecoveries: Int = when {
        checkpoint?.get("recovery_revision") != JsonPrimitive(recoveryRevision) -> 0
        checkpoint["consecutive_transport_recoveries"] != null -> checkpoint.count("consecutive_transport_recoveries")
        resumeCause(checkpoint) == Cause.TRANSPORT_INTERRUPTION -> checkpoint.count("interruption_attempts")
        else -> 0
    }.coerceAtMost(GitHubTaskRecovery.MAX_CONNECTION_RECOVERIES)
        private set
    private var providerWaits = checkpoint.count("provider_waits")
    private var toolHandoffs = checkpoint.count("tool_handoffs")

    fun claimTransportRecovery() {
        consecutiveTransportRecoveries++
        transportRecoveries++
    }

    /** A known rejected POST did not exercise the claimed transport continuation. */
    fun providerWait(releaseTransportClaim: Boolean) {
        providerWaits++
        if (releaseTransportClaim) {
            consecutiveTransportRecoveries = (consecutiveTransportRecoveries - 1).coerceAtLeast(0)
            transportRecoveries = (transportRecoveries - 1).coerceAtLeast(0)
        }
    }

    fun completedBoundary(toolHandoff: Boolean) {
        consecutiveTransportRecoveries = 0
        if (toolHandoff) toolHandoffs++
    }

    fun checkpoint(state: String, failure: ProviderEvent.Failed?, outputContinuation: Boolean, noOutputRepair: Boolean): JsonObject = buildJsonObject {
        put("recovery_cause", causeForState(state, failure, outputContinuation, noOutputRepair).name)
        put("transport_recoveries", transportRecoveries)
        put("consecutive_transport_recoveries", consecutiveTransportRecoveries)
        // Retain the old field for readers of v1 checkpoints, with its corrected meaning.
        put("interruption_attempts", consecutiveTransportRecoveries)
        put("provider_waits", providerWaits)
        put("tool_handoffs", toolHandoffs)
    }

    enum class Cause { PROVIDER_COOLDOWN, TRANSPORT_INTERRUPTION, TOOL_HANDOFF, OUTPUT_LENGTH, NO_OUTPUT, PROVIDER_REQUEST }

    companion object {
        fun resumeCause(checkpoint: JsonObject?): Cause {
            val state = (checkpoint?.get("state") as? JsonPrimitive)?.contentOrNull
            if (state == "WAITING_PROVIDER_LIMIT") return Cause.PROVIDER_COOLDOWN
            if (checkpoint?.get("state") in GitHubToolHandoff.states) return Cause.TOOL_HANDOFF
            if (state == "NO_OUTPUT_REPAIR" || checkpoint?.get("visible_output_repair_pending") == JsonPrimitive(true)) return Cause.NO_OUTPUT
            if (checkpoint?.get("resume_kind") == JsonPrimitive("OUTPUT_CONTINUATION") || state == "OUTPUT_LIMIT") return Cause.OUTPUT_LENGTH
            val code = (checkpoint?.get("failure_code") as? JsonPrimitive)?.contentOrNull
            if (code != null && GitHubTaskRecovery.isConnectionUnavailable(ProviderEvent.Failed("", errorCode = code))) return Cause.TRANSPORT_INTERRUPTION
            if (state == "WAITING_FOR_CONNECTION") {
                // Older checkpoints charged AFTER_TOOL_RESULTS against the connection
                // limit and then replaced the current failure with a generic wrapper.
                val originalCode = (ProviderRecoveryFailure.interruption(checkpoint)?.get("code") as? JsonPrimitive)?.contentOrNull
                if (checkpoint["resume_kind"] == JsonPrimitive("AFTER_TOOL_RESULTS") && originalCode == "429" && code == null) return Cause.TOOL_HANDOFF
                return Cause.TRANSPORT_INTERRUPTION
            }
            // A process stopped during a request, without a complete typed boundary.
            if (code == null && state in setOf("GENERATING", "WRITING", "CANCELLED", "INTERRUPTED")) return Cause.TRANSPORT_INTERRUPTION
            return Cause.PROVIDER_REQUEST
        }

        private fun causeForState(state: String, failure: ProviderEvent.Failed?, outputContinuation: Boolean, noOutputRepair: Boolean): Cause = when {
            state == "WAITING_PROVIDER_LIMIT" -> Cause.PROVIDER_COOLDOWN
            state in setOf("TOOL_HANDOFF", "TOOL_RESULTS_READY") -> Cause.TOOL_HANDOFF
            state in setOf("NO_OUTPUT_REPAIR", "BLOCKED_NO_PROGRESS") || noOutputRepair -> Cause.NO_OUTPUT
            outputContinuation || state == "OUTPUT_LIMIT" -> Cause.OUTPUT_LENGTH
            state == "WAITING_FOR_CONNECTION" || failure?.let(GitHubTaskRecovery::isConnectionUnavailable) == true -> Cause.TRANSPORT_INTERRUPTION
            else -> Cause.PROVIDER_REQUEST
        }

        private fun JsonObject?.count(key: String): Int = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.coerceIn(0, 1_000_000) ?: 0
    }
}
