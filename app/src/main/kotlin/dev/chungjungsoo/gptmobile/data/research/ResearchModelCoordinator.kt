package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.tool.parseDelegationObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
internal enum class ResearchModelStage { PLAN, FIND_GAPS, EXTRACT, REVIEW }

@Serializable
data class ResearchModelEvent(
    val id: String,
    val stage: String,
    val profileUid: String,
    val model: String,
    val local: Boolean,
    val outcome: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val invocationId: String? = null,
    val inputTokensEstimate: Int = 0,
    val outputTokensEstimate: Int = 0,
    val reason: String = "",
    val independent: Boolean = false,
    val failurePhase: String = "",
    val failureCategory: String = "",
    val exceptionType: String = ""
)

internal enum class ResearchAdmission {
    ADMITTED,
    MODEL_NOT_ADMITTED,
    PROFILE_DISABLED,
    PROFILE_REMOVED,
    PROFILE_IDENTITY_AMBIGUOUS,
    REGISTERED_PROFILE_CHANGED,
    DESTINATION_CHANGED,
    PERMISSION_REVOKED,
    READINESS_TIMEOUT,
    LOCAL_ALLOWANCE_EXHAUSTED,
    MODEL_CAPABILITY_UNAVAILABLE
}

internal class ResearchAdmissionRejected(val admission: ResearchAdmission) : IllegalStateException(admission.name)

internal data class ResearchModelReply(val text: String, val modelKey: String, val invocationId: String? = null, val independent: Boolean = false)

/** Only pinned, consent-checked endpoints supplied by the parent are ever considered. */
internal data class ResearchModelEndpoint(
    val profileUid: String,
    val model: String,
    val modelKey: String,
    val local: Boolean,
    val available: suspend () -> Boolean,
    val timeoutMillis: Long? = null,
    val admission: (suspend () -> ResearchAdmission)? = null,
    val generate: suspend (ResearchModelStage, String, Int) -> ResearchModelReply
)

/** Local-first, no tools, no recursive delegation, and no silent cross-account fallback. */
internal class ResearchModelCoordinator(
    private val workers: List<ResearchModelEndpoint>,
    private val reviewer: ResearchModelEndpoint? = null,
    private val stillEnabled: suspend () -> Boolean = { true },
    private val deadlineMillis: Long = ResearchTimeouts.MODEL_MS,
    private val onEvent: suspend (ResearchModelEvent) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class CircuitKey(val profileUid: String, val modelKey: String, val stage: ResearchModelStage)
    private data class FailureState(val count: Int, val retryAt: Long, val probe: Boolean = false)
    private val failures = java.util.concurrent.ConcurrentHashMap<CircuitKey, FailureState>()
    private val history = mutableListOf<ResearchModelEvent>()
    private val successfulStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val incompleteStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun completedStage(stage: String): Boolean = stage.uppercase(java.util.Locale.ROOT).let { it in successfulStages && it !in incompleteStages }
    val events: List<ResearchModelEvent> get() = synchronized(history) { history.toList() }

    private suspend fun record(event: ResearchModelEvent) {
        if (event.outcome == "SUCCESS") successfulStages.add(event.stage)
        synchronized(history) {
            history.removeAll { it.id == event.id }
            history += event
            if (history.size > 200) history.removeAt(0)
        }
        onEvent(event)
    }

    private fun enterCircuit(key: CircuitKey): Boolean {
        while (true) {
            val previous = failures[key] ?: return true
            if (previous.count < 2) return true
            if (previous.probe || monotonicClock() < previous.retryAt) return false
            if (failures.replace(key, previous, previous.copy(probe = true))) return true
        }
    }

    val hasCandidates: Boolean get() = workers.isNotEmpty() || reviewer != null

    suspend fun generate(stage: ResearchModelStage, prompt: String, cap: Int, extractedBy: String = ""): ResearchModelReply? = try {
        attemptGenerate(stage, prompt, cap, extractedBy).also { if (it == null) incompleteStages.add(stage.name) }
    } catch (failure: Throwable) {
        incompleteStages.add(stage.name)
        throw failure
    }

    private suspend fun attemptGenerate(stage: ResearchModelStage, prompt: String, cap: Int, extractedBy: String): ResearchModelReply? {
        if (!stillEnabled() || cap <= 0) return null
        val candidates = (if (stage == ResearchModelStage.REVIEW) listOfNotNull(reviewer) + workers else workers).distinctBy { it.profileUid }
        for (target in candidates) {
            if (!stillEnabled()) return null
            val event = ResearchModelEvent(UUID.randomUUID().toString(), stage.name, target.profileUid, target.model, target.local, "QUEUED", wallClock(), inputTokensEstimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(prompt))
            val circuit = CircuitKey(target.profileUid, target.modelKey, stage)
            if (!enterCircuit(circuit)) {
                record(event.copy(outcome = "SKIPPED", reason = "MODEL_CIRCUIT_OPEN"))
                continue
            }
            fun failed() {
                failures.compute(circuit) { _, old -> FailureState(((old?.count ?: 0) + 1).coerceAtMost(2), monotonicClock() + 30_000L) }
            }
            val started = monotonicClock()
            var phase = "READINESS"
            try {
                val admission = withTimeoutOrNull(minOf(5000L, deadlineMillis)) {
                    target.admission?.invoke() ?: if (target.available()) ResearchAdmission.ADMITTED else ResearchAdmission.MODEL_NOT_ADMITTED
                } ?: ResearchAdmission.READINESS_TIMEOUT
                if (admission != ResearchAdmission.ADMITTED) {
                    record(event.copy(outcome = "SKIPPED", reason = admission.name, failurePhase = phase, failureCategory = "ADMISSION"))
                    continue
                }
                record(event.copy(outcome = "RUNNING"))
                phase = "GENERATION"
                val reply = withTimeoutOrNull(target.timeoutMillis ?: deadlineMillis) { target.generate(stage, prompt, cap) }
                if (!stillEnabled()) {
                    record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PERMISSION_REVOKED"))
                    return null
                }
                if (reply == null || !valid(stage, parseDelegationObject(reply.text))) {
                    failed()
                    record(event.copy(outcome = if (reply == null) "TIMED_OUT" else "INVALID_RESPONSE", durationMs = elapsed(started), invocationId = reply?.invocationId, reason = if (reply == null) "MODEL_DEADLINE" else "SCHEMA_VALIDATION_FAILED"))
                    continue
                }
                failures.remove(circuit)
                val independent = stage == ResearchModelStage.REVIEW && extractedBy.isNotBlank() && target.modelKey.isNotBlank() && target.modelKey != extractedBy
                record(event.copy(outcome = "SUCCESS", durationMs = elapsed(started), invocationId = reply.invocationId, outputTokensEstimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(reply.text), independent = independent))
                return reply.copy(modelKey = target.modelKey, independent = independent)
            } catch (cancelled: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PARENT_CANCELLED")) }
                throw cancelled
            } catch (rejected: ResearchAdmissionRejected) {
                record(event.copy(outcome = "SKIPPED", durationMs = elapsed(started), reason = rejected.admission.name, failurePhase = phase, failureCategory = "ADMISSION"))
            } catch (error: Exception) {
                failed()
                record(
                    event.copy(
                        outcome = "FAILED",
                        durationMs = elapsed(started),
                        reason = "MODEL_EXECUTION_FAILED",
                        failurePhase = phase,
                        failureCategory = failureCategory(error),
                        exceptionType = error.javaClass.simpleName.take(100)
                    )
                )
            } finally {
                // A canceled or inadmissible half-open probe must not strand the stage indefinitely.
                failures.computeIfPresent(circuit) { _, state ->
                    if (state.probe) state.copy(probe = false, retryAt = monotonicClock() + 30_000L) else state
                }
            }
        }
        return null
    }

    private fun elapsed(started: Long): Long = (monotonicClock() - started).coerceAtLeast(0)

    companion object {
        /** Stable categories retain the cause without persisting source text or provider credentials. */
        internal fun failureCategory(error: Exception): String {
            val messages = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).joinToString(" ") { it.message.orEmpty() }
            return when {
                listOf("PERMISSION_DENIED", "SOURCE_PERMISSION_REVOKED", "DISPATCH_POLICY_CHANGED").any { messages.contains(it, true) } -> "AUTHORIZATION"
                messages.contains("PROFILE_", true) -> "PROFILE_RESOLUTION"
                messages.contains("interrupted inside native", true) || messages.contains("QUARANTIN", true) -> "RUNTIME_QUARANTINED"
                error is IllegalArgumentException -> "ARGUMENT_VALIDATION"
                messages.contains("429") || messages.contains("rate limit", true) -> "RATE_LIMITED"
                messages.contains("503") || messages.contains("overload", true) -> "PROVIDER_OVERLOADED"
                error is java.io.IOException -> "TRANSPORT"
                else -> "PROVIDER_ADAPTER"
            }
        }
        internal fun valid(stage: ResearchModelStage, value: JsonObject?): Boolean = value != null &&
            when (stage) {
                ResearchModelStage.PLAN -> value["questions"] is JsonArray && value["queries"] is JsonArray
                ResearchModelStage.FIND_GAPS -> value["missing"] is JsonArray && value["queries"] is JsonArray
                ResearchModelStage.EXTRACT -> value["claims"] is JsonArray
                ResearchModelStage.REVIEW -> value.text("verdict") in setOf("Supported", "Contradicted", "Insufficient") && value.text("relevance") in setOf("Relevant", "Background", "Irrelevant") && value.text("questionId").isNotBlank()
            }
    }
}
