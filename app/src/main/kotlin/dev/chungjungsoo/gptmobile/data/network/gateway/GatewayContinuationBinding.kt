package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.SavedViewRecoveryBatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Verified saved handoff identity; it permits returning retained results, never executing saved calls. */
class GatewayContinuationBinding private constructor(
    val jobId: String,
    private val profileUid: String,
    private val baseUrl: String,
    private val model: String,
    private val pending: Map<String, Pair<String, JsonObject>>
) {
    fun requireRoute(profileUid: String, baseUrl: String, model: String) {
        check(this.profileUid == profileUid && sameEndpoint(this.baseUrl, baseUrl) && this.model == model) {
            "GATEWAY_JOB_UNAVAILABLE: The saved handoff belongs to another profile, endpoint or model. No replacement job was dispatched."
        }
    }

    /** Skip only complete, cursor-bound local saved-page batches after the latest real handoff. */
    fun requireResults(exchanges: List<AgentToolExchange>) {
        val latest = exchanges.lastOrNull { !SavedViewRecoveryBatch.isComplete(it, pending.keys) }
        requireResults(latest?.calls.orEmpty().map { it.providerIdentity().let { original -> Triple(original.callId, original.name, original.arguments) } }, latest?.results.orEmpty().map { it.callId })
    }

    /** Opaque call IDs may recur in older completed batches; only this exact batch is authoritative. */
    fun requireResults(calls: List<Triple<String, String, JsonObject>>, terminalResultIds: List<String>) {
        val callIds = calls.map { it.first }
        check(
            callIds.distinct().size == calls.size &&
                terminalResultIds.distinct().size == terminalResultIds.size &&
                callIds.toSet() == pending.keys &&
                terminalResultIds.toSet() == pending.keys
        ) {
            "CLIENT_TOOL_RESULTS_REQUIRED: The assistant batch and terminal results must contain exactly the original pending IDs, once each."
        }
        for ((id, expected) in pending) {
            val matching = calls.filter { it.first == id }
            check(matching.size == 1 && terminalResultIds.count { it == id } == 1) {
                "CLIENT_TOOL_RESULTS_REQUIRED: The saved handoff requires every original pending call and terminal result. Completed operations must not be repeated."
            }
            check(matching.single().second == expected.first && matching.single().third == expected.second) {
                "CLIENT_TOOL_RESULT_IDENTITY_MISMATCH: The retained result no longer matches the original saved call. No continuation was dispatched."
            }
        }
    }

    companion object {
        suspend fun read(
            profileUid: String,
            baseUrl: String,
            model: String,
            expectedJobId: String,
            validateAuthority: suspend () -> Unit,
            load: suspend () -> GatewayJobResult?
        ): GatewayContinuationBinding? {
            validateAuthority()
            val saved = checkNotNull(load()) { "GATEWAY_JOB_UNAVAILABLE: The prior task's saved job could not be read; no replacement request was sent." }
            validateAuthority()
            check(saved.jobId == expectedJobId && (saved.job?.jobId == null || saved.job.jobId == expectedJobId) && saved.matchesRequestedModel(model)) {
                "GATEWAY_JOB_UNAVAILABLE: The saved result does not match the prior task and selected model."
            }
            val status = saved.status.lowercase(java.util.Locale.ROOT)
            if (status in setOf("completed", "failed", "cancelled", "canceled", "interrupted")) return null
            check(status == "waiting_for_client_tool") { "GATEWAY_JOB_IN_PROGRESS: The saved gateway job is still active. Read its result before dispatching another request." }
            return verified(profileUid, baseUrl, model, expectedJobId, saved)
        }

        fun verified(profileUid: String, baseUrl: String, model: String, expectedJobId: String, saved: GatewayJobResult): GatewayContinuationBinding {
            check(expectedJobId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && saved.jobId == expectedJobId && saved.job?.jobId == expectedJobId && saved.matchesRequestedModel(model)) {
                "GATEWAY_JOB_UNAVAILABLE: The saved handoff identity or selected model could not be verified. No replacement job was dispatched."
            }
            check(saved.status.equals("waiting_for_client_tool", true) && saved.error == null && saved.structuredError == null && saved.result?.error == null) {
                "TERMINAL_JOB_NOT_RESUMABLE: Only an exact waiting client-tool handoff can receive retained results."
            }
            val ids = requireNotNull(saved.job).pendingClientToolCallIds
            check(ids.isNotEmpty() && ids.size <= 128 && ids.distinct().size == ids.size && ids.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }) {
                "CLIENT_TOOL_RESULTS_REQUIRED: The saved job does not contain a complete pending-call identity."
            }
            val calls = saved.result?.choices.orEmpty().flatMap { it.effectiveDelta.toolCalls.orEmpty() }
            check(calls.size == ids.size && calls.map { it.id }.toSet() == ids.toSet()) { "CLIENT_TOOL_RESULT_IDENTITY_MISMATCH: The saved handoff call IDs do not match its pending IDs." }
            val pending = calls.associate { call ->
                val name = call.function?.name?.takeIf(String::isNotBlank)
                val arguments = call.function?.arguments?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                check(name != null && arguments != null) { "CLIENT_TOOL_RESULT_IDENTITY_MISMATCH: The saved handoff is missing its original function identity." }
                requireNotNull(call.id) to (name to arguments)
            }
            return GatewayContinuationBinding(expectedJobId, profileUid, baseUrl, model, pending)
        }

        private fun sameEndpoint(a: String, b: String): Boolean = runCatching {
            val first = java.net.URI(a.trim().trimEnd('/')).normalize()
            val second = java.net.URI(b.trim().trimEnd('/')).normalize()
            first.scheme in setOf("http", "https") && first.host != null && first.userInfo == null && first == second
        }.getOrDefault(false)
    }
}
