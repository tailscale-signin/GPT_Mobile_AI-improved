package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A bounded repair has its own durable allowance, independent of output-length continuations. */
internal class GitHubNoOutputProgress(saved: JsonObject?, revised: Boolean, private val maximumRepairs: Int) {
    enum class Kind { REASONING_ONLY, EMPTY_VISIBLE_COMPLETION, DUPLICATE_DRAFT, OUTPUT_LIMIT_WITHOUT_NOVELTY }

    var repairs: Int = if (revised) 0 else (saved?.get("no_output_repairs") as? JsonPrimitive)?.intOrNull ?: 0
        private set
    var visibleOutputRepairPending: Boolean = !revised && saved?.get("visible_output_repair_pending") == JsonPrimitive(true)
        private set
    var visibleOutputRepairRequestClaimed = !revised && saved?.get("visible_output_repair_request_claimed") == JsonPrimitive(true)
        private set
    private var observation = if (revised) null else retainedDetails(saved?.get("no_output_observation") as? JsonObject)

    init {
        require(maximumRepairs in 0..2 && repairs in 0..2) { "Saved empty-output recovery allowance is invalid" }
        require(!visibleOutputRepairPending || repairs == 1 && observation?.get("repair_scheduled") == JsonPrimitive(true)) { "Saved visible-output repair has no durable allowance claim" }
        require(!visibleOutputRepairRequestClaimed || repairs == 1) { "Saved visible-output request has no durable repair claim" }
    }

    fun checkpoint(): JsonObject = buildJsonObject {
        put("no_output_repairs", repairs)
        put("visible_output_repair_pending", visibleOutputRepairPending)
        put("visible_output_repair_request_claimed", visibleOutputRepairRequestClaimed)
        observation?.let { put("no_output_observation", it) }
    }

    fun completeVisibleOutputRepair() {
        visibleOutputRepairPending = false
    }

    fun claimVisibleOutputRequest(): Boolean {
        check(visibleOutputRepairPending)
        if (visibleOutputRepairRequestClaimed) return false
        visibleOutputRepairRequestClaimed = true
        return true
    }

    /** Only typed proof that the provider did not accept this request permits the wait to retry it. */
    fun releaseRejectedVisibleOutputRequest() {
        if (visibleOutputRepairPending) visibleOutputRepairRequestClaimed = false
    }

    fun claimedVisibleOutputFailure(draft: String): ProviderEvent.Failed {
        visibleOutputRepairPending = false
        observation = JsonObject(
            requireNotNull(observation) + mapOf(
                "no_output_stop_reason" to JsonPrimitive("VISIBLE_OUTPUT_REPAIR_REQUEST_ALREADY_CLAIMED"),
                "repair_scheduled" to JsonPrimitive(false)
            )
        )
        return failure(draft)
    }

    fun observe(
        visible: String,
        draft: String,
        reasoningBytes: Long,
        finishReason: String?,
        invocationId: String?,
        evidenceDigest: String,
        limited: Boolean,
        allowRepair: Boolean = true,
        changedOutputRepairAvailable: Boolean = false,
        continuationAttempt: Int = 0,
        continuationLimit: Int = 0,
        outputTokens: Int? = null,
        reasoningTokens: Int? = null,
        effectiveOutputTokens: Int? = null,
        serializedOutputTokens: Int? = null
    ): Boolean {
        val kind = when {
            limited -> Kind.OUTPUT_LIMIT_WITHOUT_NOVELTY
            visible.isNotBlank() -> Kind.DUPLICATE_DRAFT
            reasoningBytes > 0 -> Kind.REASONING_ONLY
            else -> Kind.EMPTY_VISIBLE_COMPLETION
        }
        // An exhausted allowance cannot justify repeating the same request. Only
        // a separately configured visible-output plan may recover an empty limited
        // segment, once, without replenishing the ordinary continuation allowance.
        val eligible = kind in setOf(Kind.REASONING_ONLY, Kind.EMPTY_VISIBLE_COMPLETION) ||
            limited &&
            visible.isBlank() &&
            changedOutputRepairAvailable
        val repairLimit = if (changedOutputRepairAvailable) minOf(maximumRepairs, 1) else maximumRepairs
        val repair = allowRepair && eligible && repairs < repairLimit
        val parentInvocation = (observation?.get("invocation_id") as? JsonPrimitive)?.contentOrNull
        if (repair) repairs++
        visibleOutputRepairPending = repair && changedOutputRepairAvailable
        if (visibleOutputRepairPending) visibleOutputRepairRequestClaimed = false
        observation = buildJsonObject {
            put("no_output_kind", kind.name)
            put("visible_bytes", visible.toByteArray(Charsets.UTF_8).size)
            put("reasoning_bytes", reasoningBytes.coerceAtLeast(0))
            put("tool_call_count", 0)
            put("draft_sha256", GitHubEvidenceBook.digest(draft))
            put("segment_sha256", GitHubEvidenceBook.digest(visible))
            put("evidence_digest", evidenceDigest)
            put("no_output_repairs", repairs)
            put("no_output_repair_limit", repairLimit)
            put("continuation_attempt", continuationAttempt.coerceAtLeast(0))
            put("continuation_limit", continuationLimit.coerceAtLeast(0))
            outputTokens?.takeIf { it >= 0 }?.let { put("output_tokens", it) }
            reasoningTokens?.takeIf { it >= 0 }?.let { put("reasoning_tokens", it) }
            effectiveOutputTokens?.takeIf { it > 0 }?.let { put("effective_output_tokens", it) }
            serializedOutputTokens?.takeIf { it > 0 }?.let { put("serialized_output_tokens", it) }
            put(
                "no_output_stop_reason",
                when {
                    visibleOutputRepairPending -> "BOUNDED_VISIBLE_OUTPUT_REPAIR"
                    repair -> "BOUNDED_EMPTY_OUTPUT_REPAIR"
                    kind == Kind.DUPLICATE_DRAFT -> "DUPLICATE_DRAFT"
                    limited -> "OUTPUT_ALLOWANCE_WITHOUT_NOVELTY"
                    !allowRepair -> "NO_PROGRESS_AFTER_CONTINUATION"
                    else -> "EMPTY_OUTPUT_REPAIR_LIMIT"
                }
            )
            put("repair_scheduled", repair)
            if (visibleOutputRepairPending) put("repair_request_role", dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE)
            parentInvocation?.takeUnless { it == invocationId }?.let { put("parent_invocation_id", it) }
            invocationId?.takeIf { it.length in 1..128 && it.all { char -> char.isLetterOrDigit() || char in "-_.:" } }?.let { put("invocation_id", it) }
            finishReason?.lowercase()?.takeIf { it in FINISH_REASONS }?.let { put("finish_reason", it) }
        }
        return repair
    }

    fun failure(draft: String): ProviderEvent.Failed = failure(requireNotNull(observation), draft.isNotBlank())

    companion object {
        private val FINISH_REASONS = setOf("stop", "length", "max_tokens", "output_limit", "end_turn", "stop_sequence", "tool_calls", "function_call", "completed")
        internal val STOP_REASONS = setOf("BOUNDED_EMPTY_OUTPUT_REPAIR", "BOUNDED_VISIBLE_OUTPUT_REPAIR", "VISIBLE_OUTPUT_REPAIR_REQUEST_ALREADY_CLAIMED", "DUPLICATE_DRAFT", "OUTPUT_ALLOWANCE_WITHOUT_NOVELTY", "NO_PROGRESS_AFTER_CONTINUATION", "EMPTY_OUTPUT_REPAIR_LIMIT")

        fun retainedDetails(details: JsonObject?): JsonObject? {
            if (details == null) return null
            val kind = (details["no_output_kind"] as? JsonPrimitive)?.contentOrNull?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: return null
            return buildJsonObject {
                put("no_output_kind", kind.name)
                for (key in listOf("visible_bytes", "reasoning_bytes", "tool_call_count", "no_output_repairs", "no_output_repair_limit", "continuation_attempt", "continuation_limit", "output_tokens", "reasoning_tokens", "effective_output_tokens", "serialized_output_tokens")) {
                    (details[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let { put(key, it) }
                }
                for (key in listOf("draft_sha256", "segment_sha256", "evidence_digest")) {
                    (details[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { put(key, it) }
                }
                (details["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in FINISH_REASONS }?.let { put("finish_reason", it) }
                for (key in listOf("invocation_id", "parent_invocation_id")) {
                    (details[key] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.length in 1..128 && it.all { char -> char.isLetterOrDigit() || char in "-_.:" } }?.let { put(key, it) }
                }
                (details["no_output_stop_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in STOP_REASONS }?.let { put("no_output_stop_reason", it) }
                if (details["repair_request_role"] == JsonPrimitive(dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE)) put("repair_request_role", requireNotNull(details["repair_request_role"]))
                if (details["repair_scheduled"] in setOf(JsonPrimitive(true), JsonPrimitive(false))) put("repair_scheduled", requireNotNull(details["repair_scheduled"]))
            }
        }

        fun savedFailure(checkpoint: JsonObject): ProviderEvent.Failed {
            val details = retainedDetails(checkpoint["failure_details"] as? JsonObject) ?: retainedDetails(checkpoint["no_output_observation"] as? JsonObject)
            if (details != null) return failure(details, (checkpoint["draft"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
            return ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The saved continuation added no new work. Change the request or supported output configuration before resuming.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED")
        }

        private fun failure(observation: JsonObject, hasDraft: Boolean): ProviderEvent.Failed {
            val kind = Kind.valueOf(requireNotNull((observation["no_output_kind"] as? JsonPrimitive)?.contentOrNull))
            val claimedRequest = observation["no_output_stop_reason"] == JsonPrimitive("VISIBLE_OUTPUT_REPAIR_REQUEST_ALREADY_CLAIMED")
            val explanation = if (claimedRequest) {
                "The saved visible-output repair already claimed its provider request. Inspect that attempt's outcome before another generation; restarting cannot repeat it."
            } else {
                when (kind) {
                    Kind.REASONING_ONLY -> "The provider returned reasoning without a visible answer or tool action after the bounded repair."
                    Kind.EMPTY_VISIBLE_COMPLETION -> "The provider completed without a visible answer or tool action after the bounded repair."
                    Kind.DUPLICATE_DRAFT -> "The continuation repeated the saved draft without new work."
                    Kind.OUTPUT_LIMIT_WITHOUT_NOVELTY -> "The provider exhausted its output allowance without adding useful work."
                }
            }
            return ProviderEvent.Failed(
                "OUTPUT_CONTINUATION_NO_PROGRESS: $explanation Saved evidence remains available.",
                errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS",
                taskOutcome = "BLOCKED",
                invocationId = (observation["invocation_id"] as? JsonPrimitive)?.contentOrNull,
                failureDetails = JsonObject(
                    observation + buildJsonObject {
                        put("failed_stage", "final_synthesis")
                        put("execution_state", "BLOCKED")
                        put("delivery_state", if (hasDraft) "PARTIAL_DRAFT" else "NO_VISIBLE_ANSWER")
                        put("partial_output_preserved", hasDraft)
                        put("retryable", false)
                        put("resume_allowed", false)
                        put("request_replay_allowed", false)
                        put("repair_scheduled", false)
                        put("next_action", if (claimedRequest) "INSPECT_SAVED_PROVIDER_ATTEMPT" else "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST")
                        put("user_input_required", true)
                    }
                )
            )
        }
    }
}
