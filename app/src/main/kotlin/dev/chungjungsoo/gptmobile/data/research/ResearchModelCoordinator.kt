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
    val exceptionType: String = "",
    val transportComplete: Boolean = false,
    val schemaValid: Boolean = false,
    val stageAccepted: Boolean = false,
    val partialRetained: Boolean = false,
    val checkpointId: String? = null,
    val providerHost: String = "",
    val finishReason: String? = null,
    val requestId: String? = null
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

internal data class ResearchModelReply(
    val text: String,
    val modelKey: String,
    val invocationId: String? = null,
    val independent: Boolean = false,
    val transportComplete: Boolean = true,
    val finishReason: String? = null,
    val requestId: String? = null,
    val providerIdentity: String = "",
    val transportFailure: String? = null,
    val outputLimitReached: Boolean = false
)

/** Only pinned, consent-checked endpoints supplied by the parent are ever considered. */
internal data class ResearchModelEndpoint(
    val profileUid: String,
    val model: String,
    val modelKey: String,
    val local: Boolean,
    val available: suspend () -> Boolean,
    val timeoutMillis: Long? = null,
    val admission: (suspend () -> ResearchAdmission)? = null,
    val generate: suspend (ResearchModelStage, String, Int) -> ResearchModelReply,
    val providerHost: String = ""
)

/** Local-first, no tools, no recursive delegation, and no silent cross-account fallback. */
internal class ResearchModelCoordinator(
    private val workers: List<ResearchModelEndpoint>,
    private val reviewer: ResearchModelEndpoint? = null,
    private val stillEnabled: suspend () -> Boolean = { true },
    private val deadlineMillis: Long = ResearchTimeouts.MODEL_MS,
    private val onEvent: suspend (ResearchModelEvent) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
    healthScope: String? = null,
    private val retainPartial: (suspend (String, String, String) -> String?)? = null
) {
    private data class CircuitKey(val profileUid: String, val modelKey: String, val stage: ResearchModelStage)
    private data class FailureState(val count: Int, val retryAt: Long, val probe: Boolean = false)
    private val health = healthScope?.let { scope ->
        synchronized(turnHealth) {
            val now = System.currentTimeMillis()
            turnHealth.entries.removeAll { now - it.value.first > 24 * 60 * 60_000L }
            if (turnHealth.size >= 512 && scope !in turnHealth) turnHealth.remove(turnHealth.minBy { it.value.first }.key)
            turnHealth.getOrPut(scope) { now to (java.util.concurrent.ConcurrentHashMap<CircuitKey, FailureState>() to java.util.concurrent.ConcurrentHashMap<String, FailureState>()) }.second
        }
    }
    private val failures = health?.first ?: java.util.concurrent.ConcurrentHashMap<CircuitKey, FailureState>()
    private val sharedFailures = health?.second ?: java.util.concurrent.ConcurrentHashMap<String, FailureState>()
    private val history = mutableListOf<ResearchModelEvent>()
    private val successfulStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val incompleteStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun completedStage(stage: String): Boolean = stage.uppercase(java.util.Locale.ROOT).let { it in successfulStages && it !in incompleteStages }
    val events: List<ResearchModelEvent> get() = synchronized(history) { history.toList() }

    suspend fun rejectGrounding(invocationId: String?) {
        val previous = events.lastOrNull { it.invocationId == invocationId && it.stage == ResearchModelStage.EXTRACT.name } ?: return
        incompleteStages.add(previous.stage)
        record(previous.copy(outcome = "VALIDATION_FAILED", reason = "NO_GROUNDED_CLAIMS", failurePhase = "GROUNDING", failureCategory = "EVIDENCE_VALIDATION"))
    }

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
            val sharedKey = "${target.profileUid}:${target.modelKey}"
            if (sharedFailures[sharedKey]?.let { it.count >= 2 && monotonicClock() < it.retryAt } == true || !enterCircuit(circuit)) {
                record(event.copy(outcome = "SKIPPED", reason = "MODEL_CIRCUIT_OPEN"))
                continue
            }
            fun failed(shared: Boolean = false) {
                if (shared) sharedFailures.compute(sharedKey) { _, old -> FailureState(((old?.count ?: 0) + 1).coerceAtMost(2), monotonicClock() + 30_000L) }
                failures.compute(circuit) { _, old -> FailureState(((old?.count ?: 0) + 1).coerceAtMost(2), monotonicClock() + 30_000L) }
            }
            val started = monotonicClock()
            var phase = "READINESS"
            var networkLease: Long? = null
            try {
                val admission = withTimeoutOrNull(minOf(5000L, deadlineMillis)) {
                    target.admission?.invoke() ?: if (target.available()) ResearchAdmission.ADMITTED else ResearchAdmission.MODEL_NOT_ADMITTED
                } ?: ResearchAdmission.READINESS_TIMEOUT
                if (admission != ResearchAdmission.ADMITTED) {
                    record(event.copy(outcome = "SKIPPED", reason = admission.name, failurePhase = phase, failureCategory = "ADMISSION"))
                    continue
                }
                if (!target.local && target.providerHost.isNotBlank()) {
                    networkLease = dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.acquire(target.providerHost)
                    if (networkLease == null) {
                        record(event.copy(outcome = "SKIPPED", reason = "DNS_RECOVERY", failurePhase = "CONNECTIVITY", failureCategory = "TRANSPORT", providerHost = target.providerHost))
                        continue
                    }
                }
                record(event.copy(outcome = "RUNNING", providerHost = target.providerHost))
                phase = "GENERATION"
                val reply = withTimeoutOrNull(target.timeoutMillis ?: deadlineMillis) { target.generate(stage, prompt, cap) }
                if (!stillEnabled()) {
                    record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PERMISSION_REVOKED"))
                    return null
                }
                if (reply != null && (!reply.transportComplete || reply.transportFailure != null || reply.outputLimitReached || reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:"))) {
                    val limited = reply.outputLimitReached || reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:")
                    val checkpoint = if (reply.text.isNotBlank()) retainPartial?.invoke(stage.name, target.profileUid, dev.chungjungsoo.gptmobile.data.agent.tool.boundedDelegatePartial(reply.text)) else null
                    record(event.copy(outcome = "FAILED", reason = if (limited) "OUTPUT_LIMIT_BEFORE_SCHEMA" else "TRANSPORT_INCOMPLETE", failurePhase = "GENERATION", failureCategory = if (limited) "OUTPUT_LIMIT" else "TRANSPORT", durationMs = elapsed(started), invocationId = reply.invocationId, partialRetained = checkpoint != null, checkpointId = checkpoint, providerHost = target.providerHost, finishReason = reply.finishReason, requestId = reply.requestId))
                    continue
                }
                if (reply?.transportComplete == true && target.providerHost.isNotBlank()) dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.success(target.providerHost)
                if (reply == null || reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:") || !valid(stage, parseDelegationObject(reply.text))) {
                    failed(shared = reply == null || reply.text.isBlank() || reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:") || parseDelegationObject(reply.text) == null)
                    record(
                        event.copy(
                            outcome = if (reply == null) "TIMED_OUT" else "INVALID_RESPONSE",
                            durationMs = elapsed(started),
                            invocationId = reply?.invocationId,
                            reason = if (reply == null) {
                                "MODEL_DEADLINE"
                            } else if (reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:")) {
                                "OUTPUT_LIMIT_BEFORE_SCHEMA"
                            } else if (reply.text.isBlank()) {
                                "NO_VISIBLE_OUTPUT"
                            } else if (parseDelegationObject(reply.text) == null) {
                                "MALFORMED_JSON"
                            } else {
                                "SCHEMA_VALIDATION_FAILED"
                            },
                            failurePhase = "VALIDATION",
                            failureCategory = "STRUCTURED_OUTPUT"
                        )
                    )
                    continue
                }
                failures.remove(circuit)
                sharedFailures.remove(sharedKey)
                val independent = stage == ResearchModelStage.REVIEW && extractedBy.isNotBlank() && target.modelKey.isNotBlank() && target.modelKey != extractedBy
                record(event.copy(outcome = "SUCCESS", durationMs = elapsed(started), invocationId = reply.invocationId, outputTokensEstimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(reply.text), independent = independent, transportComplete = true, schemaValid = true, stageAccepted = true, providerHost = target.providerHost, finishReason = reply.finishReason, requestId = reply.requestId))
                return reply.copy(modelKey = target.modelKey, independent = independent)
            } catch (cancelled: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PARENT_CANCELLED")) }
                throw cancelled
            } catch (rejected: ResearchAdmissionRejected) {
                record(event.copy(outcome = "SKIPPED", durationMs = elapsed(started), reason = rejected.admission.name, failurePhase = phase, failureCategory = "ADMISSION"))
            } catch (error: Exception) {
                val delegated = error as? dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
                val category = failureCategory(error)
                val dns = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).any { it is java.net.UnknownHostException } || error.message.orEmpty().contains("UnknownHostException") || error.message.orEmpty().contains("DNS", true)
                if (dns && target.providerHost.isNotBlank()) dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.failed(target.providerHost, java.net.UnknownHostException("DNS resolution failed"))
                val checkpoint = delegated?.partialText?.takeIf { it.isNotBlank() }?.let { retainPartial?.invoke(stage.name, target.profileUid, it) }
                if (category !in setOf("TRANSPORT", "OUTPUT_LIMIT", "BUDGET_DENIED", "AUTHORIZATION")) failed(shared = failureCategory(error) in setOf("TRANSPORT", "RATE_LIMITED", "PROVIDER_OVERLOADED", "RUNTIME_QUARANTINED") || error.message.orEmpty().contains("provider unavailable", true))
                record(
                    event.copy(
                        outcome = "FAILED",
                        durationMs = elapsed(started),
                        reason = delegated?.kind?.name ?: "MODEL_EXECUTION_FAILED",
                        failurePhase = phase,
                        failureCategory = category,
                        exceptionType = error.javaClass.simpleName.take(100),
                        transportComplete = delegated?.transportComplete ?: false,
                        schemaValid = false,
                        stageAccepted = false,
                        partialRetained = checkpoint != null,
                        checkpointId = checkpoint,
                        providerHost = target.providerHost
                    )
                )
            } finally {
                networkLease?.let { dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.release(it) }
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
        private val turnHealth = linkedMapOf<String, Pair<Long, Pair<java.util.concurrent.ConcurrentHashMap<CircuitKey, FailureState>, java.util.concurrent.ConcurrentHashMap<String, FailureState>>>>()

        /** Stable categories retain the cause without persisting source text or provider credentials. */
        internal fun failureCategory(error: Exception): String {
            val messages = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).joinToString(" ") { it.message.orEmpty() }
            val delegated = error as? dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
            return when {
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.TRANSPORT_FAILURE -> "TRANSPORT"
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.OUTPUT_LIMIT -> "OUTPUT_LIMIT"
                messages.contains("CombinedBudgetExceeded") || messages.contains("BUDGET", true) || messages.contains("ALLOWANCE", true) -> "BUDGET_DENIED"
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
                ResearchModelStage.EXTRACT -> (value["claims"] as? JsonArray)?.let { rows ->
                    rows.isEmpty() ||
                        rows.any { row ->
                            (row as? JsonObject)?.let { claim -> claim.text("text").isNotBlank() && claim.text("quote").length >= 20 } == true
                        }
                } == true
                ResearchModelStage.REVIEW -> value.rows("reviews").any { it.text("claimId").isNotBlank() && it.text("verdict") in setOf("Supported", "Contradicted", "Insufficient") && it.text("relevance") in setOf("Relevant", "Background", "Irrelevant") && it.text("questionId").isNotBlank() } || value.text("verdict") in setOf("Supported", "Contradicted", "Insufficient") && value.text("relevance") in setOf("Relevant", "Background", "Irrelevant") && value.text("questionId").isNotBlank()
            }
    }
}
