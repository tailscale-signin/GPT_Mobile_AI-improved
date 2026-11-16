package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Diagnostic references only; the planner remains the sole owner of admission counts. */
internal class ToolRepeatAdmissionHistory(private val runId: String?) {
    internal enum class Outcome { UNOBSERVED, DISPATCHED_SUCCESS, DISPATCHED_FAILURE, NOT_DISPATCHED, SHARED_RESULT, UNKNOWN_OUTCOME }
    internal enum class Coverage { COMPLETE, PARTIAL, UNAVAILABLE }

    private class Reference(val callIdHash: String, val toolName: String, val argumentHash: String) {
        var outcome = Outcome.UNOBSERVED
    }

    internal class References(val callIdHashes: List<String>, val outcomes: List<Outcome>, val coverage: Coverage) {
        override fun toString(): String = "RepeatAdmissionReferences"
    }

    internal class Snapshot(private val runId: String, private val argumentHash: String, private val prior: References, private val batch: References) {
        fun fields(persistedRunId: String?, arguments: JsonObject): Map<String, Any> =
            if (persistedRunId == runId && ScopedToolGrant.canonicalHash(arguments) == argumentHash) receiptFields() else emptyMap()

        fun receiptFields(): Map<String, Any> = mapOf(
            "dependency_repeat_prior_call_id_hashes" to prior.callIdHashes,
            "dependency_repeat_prior_outcomes" to prior.outcomes.map { it.name },
            "dependency_repeat_prior_coverage" to prior.coverage.name,
            "dependency_repeat_batch_call_id_hashes" to batch.callIdHashes,
            "dependency_repeat_batch_coverage" to batch.coverage.name
        )

        override fun toString(): String = "RepeatAdmissionHistorySnapshot"
    }

    private val references = linkedMapOf<String, MutableList<Reference>>()

    // Keep only bounded hashes, including refused IDs. Reuse or overflow makes
    // subsequent history unavailable instead of inventing unique ownership.
    private val seenCallHashes = hashSetOf<String>()
    private var pendingCallHashes = emptySet<String>()
    private var ownershipUnavailable = false
    override fun toString(): String = "ToolRepeatAdmissionHistory"

    /** Run immediately after the pure plan, before closure, counter commits, or execution. */
    fun capture(plan: ToolBatchPlan, calls: List<ProviderEvent.ToolCall>, retainedIds: Set<String>, settledIds: Set<String>): ToolBatchPlan {
        val origin = runId?.takeIf { it.isNotBlank() } ?: return plan
        if (ownershipUnavailable) return plan
        val hashes = calls.map { ToolDependencyDiagnostics.callHash(it.callId) }
        if (hashes.distinct().size != hashes.size || hashes.any { it in seenCallHashes } || seenCallHashes.size + hashes.size > MAX_SEEN_CALLS) {
            ownershipUnavailable = true
            references.clear()
            pendingCallHashes = emptySet()
            return plan
        }
        pendingCallHashes = hashes.toSet()
        if (plan.repeatLimitSnapshots.isEmpty()) {
            seenCallHashes += hashes
            return plan
        }
        val admittedIds = plan.executable.filterNot { it.callId in retainedIds || it.callId in settledIds }.mapTo(hashSetOf()) { it.callId }
        val snapshots = plan.repeatLimitSnapshots.toMutableMap()
        calls.forEachIndexed { index, call ->
            val evidence = snapshots[call.callId] ?: return@forEachIndexed
            val currentHash = ToolDependencyDiagnostics.callHash(call.callId)
            val arguments = argumentHash(call.arguments) ?: return@forEachIndexed
            val key = toolRepeatKey(call)
            val priorReferences = references[key].orEmpty()
            // These are the original earlier provisional admissions, including any
            // that closure will later refund. Their outcomes are unobserved here.
            val batch = calls.subList(0, index).asSequence()
                .filter { it.callId in admittedIds && toolRepeatKey(it) == key }
                .take(MAX_REFERENCES)
                .map { Reference(ToolDependencyDiagnostics.callHash(it.callId), it.name, "") }.toList()
            val batchHashes = batch.map { it.callIdHash }
            if (priorReferences.size > evidence.snapshot.priorAdmissions ||
                batch.size > evidence.snapshot.batchAdmissions ||
                priorReferences.any { it.callIdHash == currentHash } ||
                batchHashes.any { it in seenCallHashes || it == currentHash }
            ) {
                return@forEachIndexed
            }
            val prior = snapshot(priorReferences, evidence.snapshot.priorAdmissions)
            snapshots[call.callId] = evidence.withHistory(call, Snapshot(origin, arguments, prior, snapshot(batch, evidence.snapshot.batchAdmissions)))
        }
        seenCallHashes += hashes
        return plan.copy(repeatLimitSnapshots = snapshots.toMap())
    }

    /** Observe only calls actually charged by the final dependency-closed plan. */
    fun commit(plan: ToolBatchPlan, retainedIds: Set<String>, settledIds: Set<String>) {
        if (runId.isNullOrBlank() || ownershipUnavailable) return
        val remaining = plan.acceptedByName.toMutableMap()
        plan.executable.forEach { call ->
            if (call.callId in retainedIds || call.callId in settledIds) return@forEach
            val key = toolRepeatKey(call)
            val count = remaining[key] ?: 0
            if (count <= 0) return@forEach
            remaining[key] = count - 1
            val callHash = ToolDependencyDiagnostics.callHash(call.callId)
            if (callHash !in pendingCallHashes) return@forEach
            val hash = argumentHash(call.arguments) ?: return@forEach
            val bucket = references[key] ?: run {
                if (references.size >= MAX_KEYS) references.remove(references.keys.first())
                mutableListOf<Reference>().also { references[key] = it }
            }
            if (bucket.size < MAX_REFERENCES) bucket += Reference(callHash, call.name, hash)
        }
        pendingCallHashes = emptySet()
    }

    /** Completion cannot attribute another call, connection, or argument set to an admission. */
    fun observe(call: ProviderEvent.ToolCall, result: AgentToolResult) {
        if (runId.isNullOrBlank() || ownershipUnavailable || result.callId != call.callId) return
        val callHash = ToolDependencyDiagnostics.callHash(call.callId)
        val hash = argumentHash(call.arguments) ?: return
        val reference = references[toolRepeatKey(call)]?.singleOrNull { it.callIdHash == callHash } ?: return
        if (reference.toolName != call.name || reference.argumentHash != hash || reference.outcome != Outcome.UNOBSERVED) return
        reference.outcome = when {
            result.errorCode in ToolResultEnvelope.unknownOutcomeCodes ||
                listOf("requires_reconciliation", "requiresReconciliation").any { result.executionReceipt?.get(it) == JsonPrimitive(true) } -> Outcome.UNKNOWN_OUTCOME
            result.sharedResult -> Outcome.SHARED_RESULT
            !result.dispatched -> Outcome.NOT_DISPATCHED
            result.isError -> Outcome.DISPATCHED_FAILURE
            else -> Outcome.DISPATCHED_SUCCESS
        }
    }

    private fun snapshot(values: List<Reference>, expected: Int): References {
        if (expected == 0) return References(emptyList(), emptyList(), Coverage.COMPLETE)
        if (expected !in 1..16_000_000 || values.isEmpty() || values.size > expected) return References(emptyList(), emptyList(), Coverage.UNAVAILABLE)
        val retained = values.take(MAX_REFERENCES)
        return References(
            retained.map { it.callIdHash },
            retained.map { it.outcome },
            if (retained.size == expected) Coverage.COMPLETE else Coverage.PARTIAL
        )
    }

    private fun argumentHash(arguments: JsonObject): String? {
        var nodes = 0
        var characters = 0L
        fun bounded(value: JsonElement, depth: Int): Boolean {
            if (++nodes > 256 || depth > 12) return false
            when (value) {
                is JsonObject -> {
                    if (value.size > 256) return false
                    characters += value.keys.sumOf { it.length.toLong() }
                    if (characters > 65_536 || !value.values.all { bounded(it, depth + 1) }) return false
                }
                is JsonArray -> if (value.size > 256 || !value.all { bounded(it, depth + 1) }) return false
                is JsonPrimitive -> characters += value.content.length
            }
            return characters <= 65_536
        }
        return if (bounded(arguments, 0)) ScopedToolGrant.canonicalHash(arguments) else null
    }

    companion object {
        /** Freeze optional ownership before this loop can persist any tool events. */
        suspend fun runIdWithoutPriorEvents(runId: String, previousSequence: suspend (String) -> Int?): String? {
            if (runId.isBlank()) return null
            return try {
                if (previousSequence(runId) == null) runId else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

        private const val MAX_KEYS = 128
        private const val MAX_REFERENCES = 8
        private const val MAX_SEEN_CALLS = MAX_KEYS * MAX_REFERENCES

        fun forLoop(runId: String?, recovery: ToolBatchRecoverySnapshot?, initialToolCallCount: Int? = null): ToolRepeatAdmissionHistory {
            // An empty checkpoint exists on fresh normal runs too. Any restored
            // observation or spent allowance lacks fresh admission ownership,
            // even when an authority revision selected an empty new journal.
            val fresh = initialToolCallCount == 0 &&
                (
                    recovery == null ||
                        listOf(recovery.dispatchedCount, recovery.completedCount, recovery.queuedCount, recovery.rejectedCount, recovery.failedCount, recovery.unknownCount).all { it == 0 } &&
                        recovery.pending.isEmpty() &&
                        recovery.pendingDispatchedIds.isEmpty() &&
                        recovery.pendingRetainedIds.isEmpty() &&
                        recovery.unsettledCalls.isEmpty() &&
                        recovery.completedExchanges.isEmpty() &&
                        recovery.consumedCallIds.isEmpty() &&
                        recovery.pendingCompletedExchange == null
                    )
            return ToolRepeatAdmissionHistory(runId.takeIf { fresh })
        }

        internal val FIELD_NAMES = setOf(
            "dependency_repeat_prior_call_id_hashes",
            "dependency_repeat_prior_outcomes",
            "dependency_repeat_prior_coverage",
            "dependency_repeat_batch_call_id_hashes",
            "dependency_repeat_batch_coverage"
        )
    }
}
