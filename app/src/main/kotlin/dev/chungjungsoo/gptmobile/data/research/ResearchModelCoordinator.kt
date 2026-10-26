package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.tool.parseDelegationObject
import dev.chungjungsoo.gptmobile.research.reliability.ProviderOutcome
import dev.chungjungsoo.gptmobile.research.reliability.ProviderOutcomeClassifier
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
    val requestId: String? = null,
    val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null,
    val metrics: ResearchStageMetrics? = null,
    val providerOutcome: ProviderOutcome? = null,
    val requestDigest: String = ""
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
    MODEL_CAPABILITY_UNAVAILABLE,
    OUTPUT_LIMIT_UNSUPPORTED,
    PROVIDER_COOLDOWN,
    PROVIDER_UNAVAILABLE
}

internal class ResearchAdmissionRejected(val admission: ResearchAdmission, val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null) : IllegalStateException(admission.name)

internal data class ResearchModelRequestBudget(val outputTokens: Int, val inputTokens: Int = Int.MAX_VALUE, val bounded: Boolean = false)

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
    val outputLimitReached: Boolean = false,
    val reasoningTokens: Long = 0,
    val outputTokens: Long = 0
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
    val providerHost: String = "",
    val requestBudget: (Int) -> ResearchModelRequestBudget = { ResearchModelRequestBudget(it) },
    val providerLimit: (suspend () -> dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision?)? = null,
    val generate: suspend (ResearchModelStage, String, Int) -> ResearchModelReply
)

/** Local-first, no tools, no recursive delegation, and no silent cross-account fallback. */
internal class ResearchModelCoordinator(
    private val workers: List<ResearchModelEndpoint>,
    private val reviewer: ResearchModelEndpoint? = null,
    private val stillEnabled: suspend () -> Boolean = { true },
    private val deadlineMillis: Long? = null,
    private val onEvent: suspend (ResearchModelEvent) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val healthScope: String? = null,
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
    private val rejectedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val stageTraces = java.util.concurrent.ConcurrentHashMap<String, ResearchStageTrace>()
    private val successfulStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val incompleteStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun completedStage(stage: String): Boolean = stage.uppercase(java.util.Locale.ROOT).let { it in successfulStages && it !in incompleteStages }
    val events: List<ResearchModelEvent> get() = synchronized(history) { history.toList() }

    suspend fun rejectGrounding(invocationId: String?, stage: ResearchModelStage = ResearchModelStage.EXTRACT, reason: String = "NO_GROUNDED_CLAIMS") {
        val previous = events.lastOrNull { it.invocationId == invocationId && it.stage == stage.name } ?: return
        incompleteStages.add(previous.stage)
        record(previous.copy(outcome = "VALIDATION_FAILED", reason = reason, failurePhase = "GROUNDING", failureCategory = "EVIDENCE_VALIDATION", stageAccepted = false))
    }

    private suspend fun record(event: ResearchModelEvent) {
        val measured = stageTraces[event.id]?.snapshot() ?: event.metrics
        val observed = event.copy(
            metrics = measured,
            invocationId = event.invocationId ?: measured?.invocationIds?.lastOrNull(),
            providerOutcome = event.providerOutcome ?: when {
                event.outcome == "SUCCESS" -> ProviderOutcome.SUCCESS_VISIBLE
                event.outcome == "CANCELLED" -> ProviderOutcome.INTERRUPTED
                event.outcome == "WAITING_PROVIDER_LIMIT" -> if (event.providerLimit?.httpStatus == 402) ProviderOutcome.ALLOWANCE_EXHAUSTED else ProviderOutcome.RATE_LIMITED
                event.failureCategory == "REASONING_ONLY" -> ProviderOutcome.REASONING_ONLY
                event.failureCategory == "REQUEST_INVALID" -> ProviderOutcome.INVALID_REQUEST
                event.failureCategory == "STRUCTURED_OUTPUT" -> ProviderOutcome.INVALID_PAYLOAD
                event.outcome in setOf("FAILED", "TIMED_OUT") -> ProviderOutcome.UPSTREAM_FAILED
                else -> null
            }
        )
        if (observed.outcome == "SUCCESS") successfulStages.add(observed.stage)
        synchronized(history) {
            history.removeAll { it.id == observed.id }
            history += observed
            if (history.size > 200) history.removeAt(0)
        }
        onEvent(observed)
    }

    /** Restore rejected configurations, never invent permission or a completed stage from telemetry. */
    fun restoreProviderFailures(events: List<ResearchModelEvent>) {
        val scope = healthScope ?: return
        val endpoints = (workers + listOfNotNull(reviewer)).associateBy { it.profileUid }
        events.filter { it.metrics?.rootWorkId == scope }.forEach { event ->
            val target = endpoints[event.profileUid] ?: return@forEach
            if (event.model == target.model &&
                event.requestDigest.isNotBlank() &&
                (event.providerOutcome == ProviderOutcome.INVALID_REQUEST || event.failureCategory == "REQUEST_INVALID")
            ) {
                rejectedRequests.add("${target.profileUid}:${target.modelKey}:${event.stage}:${event.requestDigest}")
            }
        }
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

    /** Prepare packets for the strictest pinned candidate without granting dispatch or changing routes. */
    fun requestBudget(stage: ResearchModelStage, requested: Int): ResearchModelRequestBudget {
        val candidates = (if (stage == ResearchModelStage.REVIEW) listOfNotNull(reviewer) + workers else workers).distinctBy { it.profileUid }
        val budgets = candidates.mapNotNull { runCatching { it.requestBudget(requested) }.getOrNull() }
        return if (budgets.isEmpty()) {
            ResearchModelRequestBudget(0, 0, true)
        } else {
            ResearchModelRequestBudget(budgets.minOf { it.outputTokens }, budgets.minOf { it.inputTokens }, budgets.any { it.bounded })
        }
    }

    fun deferStage(stage: ResearchModelStage) {
        incompleteStages.add(stage.name)
    }

    suspend fun generate(stage: ResearchModelStage, prompt: String, cap: Int, extractedBy: String = "", workItemId: String? = null): ResearchModelReply? = try {
        attemptGenerate(stage, prompt, cap, extractedBy, workItemId).also { if (it == null) incompleteStages.add(stage.name) }
    } catch (failure: Throwable) {
        incompleteStages.add(stage.name)
        throw failure
    }

    private suspend fun attemptGenerate(stage: ResearchModelStage, prompt: String, cap: Int, extractedBy: String, workItemId: String?): ResearchModelReply? {
        if (!stillEnabled() || cap <= 0) return null
        val candidates = (if (stage == ResearchModelStage.REVIEW) listOfNotNull(reviewer) + workers else workers).distinctBy { it.profileUid }
        val deferred = mutableListOf<dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision>()
        for (target in candidates) {
            if (!stillEnabled()) return null
            val event = ResearchModelEvent(UUID.randomUUID().toString(), stage.name, target.profileUid, target.model, target.local, "QUEUED", wallClock(), inputTokensEstimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(prompt), requestDigest = researchHash("$cap:$prompt"))
            val requestKey = "${target.profileUid}:${target.modelKey}:${stage.name}:${event.requestDigest}"
            if (requestKey in rejectedRequests) {
                record(event.copy(outcome = "SKIPPED", reason = "REQUEST_CONFIGURATION_REJECTED", failurePhase = "READINESS", failureCategory = "ADMISSION"))
                continue
            }
            val trace = ResearchStageTrace(healthScope.orEmpty(), researchHash("${healthScope.orEmpty()}|${stage.name}|${workItemId ?: researchHash(prompt)}"), event.id)
            val circuit = CircuitKey(target.profileUid, target.modelKey, stage)
            val sharedKey = "${target.profileUid}:${target.modelKey}"
            if (sharedFailures[sharedKey]?.let { it.count >= 2 && monotonicClock() < it.retryAt } == true || !enterCircuit(circuit)) {
                record(event.copy(outcome = "SKIPPED", reason = "MODEL_CIRCUIT_OPEN", metrics = trace.snapshot()))
                continue
            }
            fun failed(shared: Boolean = false) {
                if (shared) sharedFailures.compute(sharedKey) { _, old -> FailureState(((old?.count ?: 0) + 1).coerceAtMost(2), monotonicClock() + 30_000L) }
                failures.compute(circuit) { _, old -> FailureState(((old?.count ?: 0) + 1).coerceAtMost(2), monotonicClock() + 30_000L) }
            }
            val started = monotonicClock()
            var phase = "READINESS"
            var networkLease: Long? = null
            stageTraces[event.id] = trace
            try {
                val admission = withTimeoutOrNull(minOf(5000L, deadlineMillis ?: ResearchTimeouts.MODEL_MS)) {
                    target.admission?.invoke() ?: if (target.available()) ResearchAdmission.ADMITTED else ResearchAdmission.MODEL_NOT_ADMITTED
                } ?: ResearchAdmission.READINESS_TIMEOUT
                if (admission != ResearchAdmission.ADMITTED) {
                    val limit = if (admission == ResearchAdmission.PROVIDER_COOLDOWN) target.providerLimit?.invoke() else null
                    if (limit?.knownNotAccepted == true) {
                        deferred += limit
                        record(event.copy(outcome = "WAITING_PROVIDER_LIMIT", reason = limit.category, failurePhase = phase, failureCategory = "RATE_LIMITED", providerLimit = limit))
                    } else {
                        record(event.copy(outcome = "SKIPPED", reason = admission.name, failurePhase = phase, failureCategory = "ADMISSION"))
                    }
                    continue
                }
                val requestBudget = target.requestBudget(cap)
                if (requestBudget.outputTokens <= 0 || event.inputTokensEstimate > requestBudget.inputTokens) {
                    record(event.copy(outcome = "SKIPPED", reason = "RESEARCH_CONTEXT_OR_OUTPUT_CAPACITY", failurePhase = "READINESS", failureCategory = "ADMISSION"))
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
                val reply = withTimeoutOrNull(target.timeoutMillis ?: deadlineMillis ?: stageDeadline(stage)) {
                    kotlinx.coroutines.withContext(trace) { target.generate(stage, prompt, requestBudget.outputTokens) }
                }
                if (!stillEnabled()) {
                    record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PERMISSION_REVOKED"))
                    return null
                }
                if (reply != null && (!reply.transportComplete || reply.transportFailure != null || reply.outputLimitReached || reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:"))) {
                    val limited = reply.outputLimitReached || reply.text.startsWith("[PARTIAL_DELEGATE_OUTPUT:")
                    val checkpoint = keepPartial(stage, target, reply.text)
                    if (!limited) failed(shared = true)
                    record(event.copy(outcome = "FAILED", reason = if (limited) "OUTPUT_LIMIT_BEFORE_SCHEMA" else "TRANSPORT_INCOMPLETE", failurePhase = "GENERATION", failureCategory = if (limited) "OUTPUT_LIMIT" else "TRANSPORT", durationMs = elapsed(started), invocationId = reply.invocationId, transportComplete = reply.transportComplete && reply.transportFailure == null, partialRetained = checkpoint != null, checkpointId = checkpoint, providerHost = target.providerHost, finishReason = reply.finishReason, requestId = reply.requestId))
                    continue
                }
                if (reply == null) {
                    failed(shared = true)
                    record(event.copy(outcome = "TIMED_OUT", durationMs = elapsed(started), reason = "MODEL_DEADLINE", failurePhase = "GENERATION", failureCategory = "TRANSPORT", providerHost = target.providerHost))
                    continue
                }
                if (reply.transportComplete == true && target.providerHost.isNotBlank()) dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.success(target.providerHost)
                val parsed = parseDelegationObject(reply.text)
                val usage = trace.snapshot().usage
                val providerOutcome = ProviderOutcomeClassifier.classifyPayload(
                    reply.text,
                    maxOf(reply.reasoningTokens, usage.sumOf { it.reasoningTokens ?: 0 }),
                    maxOf(reply.outputTokens, usage.sumOf { it.outputTokens ?: 0 }),
                    valid(stage, parsed)
                )
                if (providerOutcome != ProviderOutcome.SUCCESS_VISIBLE) {
                    val checkpoint = keepPartial(stage, target, reply.text)
                    failed(shared = reply.text.isBlank() || parseDelegationObject(reply.text) == null)
                    record(
                        event.copy(
                            outcome = "INVALID_RESPONSE",
                            partialRetained = checkpoint != null,
                            checkpointId = checkpoint,
                            durationMs = elapsed(started),
                            invocationId = reply.invocationId,
                            providerOutcome = providerOutcome,
                            reason = if (providerOutcome == ProviderOutcome.REASONING_ONLY) {
                                "REASONING_ONLY_RESPONSE"
                            } else if (reply.text.isBlank()) {
                                "NO_VISIBLE_OUTPUT"
                            } else if (parseDelegationObject(reply.text) == null) {
                                "MALFORMED_JSON"
                            } else {
                                "SCHEMA_VALIDATION_FAILED"
                            },
                            failurePhase = "VALIDATION",
                            failureCategory = if (providerOutcome == ProviderOutcome.REASONING_ONLY) "REASONING_ONLY" else "STRUCTURED_OUTPUT",
                            transportComplete = true,
                            providerHost = target.providerHost
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
                val limit = rejected.providerLimit
                if (limit?.knownNotAccepted == true) {
                    deferred += limit
                    record(event.copy(outcome = "WAITING_PROVIDER_LIMIT", durationMs = elapsed(started), reason = limit.category, failurePhase = phase, failureCategory = "RATE_LIMITED", providerLimit = limit))
                } else {
                    record(event.copy(outcome = "SKIPPED", durationMs = elapsed(started), reason = rejected.admission.name, failurePhase = phase, failureCategory = "ADMISSION"))
                }
            } catch (error: Exception) {
                val delegated = error as? dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
                val limit = delegated?.providerLimit
                if (limit?.knownNotAccepted == true && delegated.partialText.isBlank()) {
                    deferred += limit
                    record(event.copy(outcome = "WAITING_PROVIDER_LIMIT", reason = limit.category, failurePhase = phase, failureCategory = "RATE_LIMITED", providerLimit = limit, durationMs = elapsed(started)))
                    continue
                }
                // A completed cap-limited response can spend every token on reasoning.
                // Preserve that distinction even when the delegate reports OUTPUT_LIMIT.
                val reasoningOnly = delegated?.transportComplete == true &&
                    delegated.partialText.isBlank() &&
                    trace.snapshot().usage.any { (it.reasoningTokens ?: 0) > 0 && it.visibleOutputEstimate == 0L }
                val category = if (reasoningOnly) "REASONING_ONLY" else failureCategory(error)
                val dns = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).any { it is java.net.UnknownHostException } || error.message.orEmpty().contains("UnknownHostException") || error.message.orEmpty().contains("DNS", true)
                if (dns && target.providerHost.isNotBlank()) dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.failed(target.providerHost, java.net.UnknownHostException("DNS resolution failed"))
                val checkpoint = delegated?.partialText?.takeIf { it.isNotBlank() }?.let { keepPartial(stage, target, it) }
                if (delegated?.transportComplete == true && target.providerHost.isNotBlank()) dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.success(target.providerHost)
                if (category == "REQUEST_INVALID") {
                    rejectedRequests.add(requestKey)
                } else if (category !in setOf("OUTPUT_LIMIT", "BUDGET_DENIED", "CONTEXT_LIMIT", "AUTHORIZATION", "ADMISSION")) {
                    failed(shared = category in setOf("TRANSPORT", "RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_OVERLOADED", "RUNTIME_QUARANTINED", "REASONING_ONLY", "EMPTY_OUTPUT"))
                }
                record(
                    event.copy(
                        outcome = if (category in setOf("ADMISSION", "BUDGET_DENIED", "CONTEXT_LIMIT")) "SKIPPED" else "FAILED",
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
                stageTraces.remove(event.id)
                networkLease?.let { dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.release(it) }
                // A canceled or inadmissible half-open probe must not strand the stage indefinitely.
                failures.computeIfPresent(circuit) { _, state ->
                    if (state.probe) state.copy(probe = false, retryAt = monotonicClock() + 30_000L) else state
                }
            }
        }
        deferred.minByOrNull { it.retryAt }?.let { limit ->
            throw ResearchProviderDeferred(ResearchDeferredStage(healthScope.orEmpty(), stage.name, researchHash(prompt), limit, workItemId))
        }
        return null
    }

    private fun stageDeadline(stage: ResearchModelStage): Long = when (stage) {
        ResearchModelStage.PLAN -> 30_000L
        ResearchModelStage.FIND_GAPS -> 45_000L
        ResearchModelStage.EXTRACT -> 120_000L
        ResearchModelStage.REVIEW -> 90_000L
    }

    private suspend fun keepPartial(stage: ResearchModelStage, target: ResearchModelEndpoint, text: String): String? {
        if (text.isBlank()) return null
        return try {
            retainPartial?.invoke(stage.name, target.profileUid, dev.chungjungsoo.gptmobile.data.agent.tool.boundedDelegatePartial(text))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun elapsed(started: Long): Long = (monotonicClock() - started).coerceAtLeast(0)

    companion object {
        private val turnHealth = linkedMapOf<String, Pair<Long, Pair<java.util.concurrent.ConcurrentHashMap<CircuitKey, FailureState>, java.util.concurrent.ConcurrentHashMap<String, FailureState>>>>()

        /** Stable categories retain the cause without persisting source text or provider credentials. */
        internal fun failureCategory(error: Exception): String {
            val messages = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).joinToString(" ") { it.message.orEmpty() }
            val delegated = error as? dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
            return when {
                listOf("RESEARCH_STAGE_TERMINAL", "RESEARCH_WORKER_STAGE_TERMINAL", "RESEARCH_WORKER_NOT_ASSIGNED").any { messages.contains(it, true) } -> "ADMISSION"
                messages.contains("REASONING_BUDGET_EXHAUSTED", true) -> "REASONING_ONLY"
                messages.contains("NO_VISIBLE_ANSWER", true) -> "EMPTY_OUTPUT"
                messages.contains("429") || messages.contains("rate limit", true) || messages.contains("saved", true) && messages.contains("cooldown", true) -> "RATE_LIMITED"
                messages.contains("temporarily unavailable after repeated", true) || messages.contains("provider unavailable", true) || messages.contains("PROVIDER_UNAVAILABLE", true) -> "PROVIDER_UNAVAILABLE"
                error is dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetExceeded ||
                    listOf("CombinedBudgetExceeded", "OUTPUT_BUDGET_UNENFORCEABLE", "RESEARCH_OUTPUT_ALLOWANCE", "PHYSICAL_ATTEMPT_ALLOWANCE", "RESEARCH_STAGE_UNFUNDED", "REQUIRED_WORK_UNFUNDED", "BUDGET_DENIED", "work allowance reached", "total token allowance").any { messages.contains(it, true) } -> "BUDGET_DENIED"
                messages.contains("DELEGATE_OVERSIZED", true) || messages.contains("CONTEXT_CAPACITY", true) -> "CONTEXT_LIMIT"
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.REASONING_ONLY -> "REASONING_ONLY"
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.EMPTY_OUTPUT -> "EMPTY_OUTPUT"
                Regex("(?i)\\bHTTP\\s+(400|413|415|422)\\b|REQUEST_INVALID|REQUEST_SCHEMA_INCOMPATIBLE|invalid_request_error").containsMatchIn(messages) -> "REQUEST_INVALID"
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.TRANSPORT_FAILURE -> "TRANSPORT"
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.OUTPUT_LIMIT -> "OUTPUT_LIMIT"
                delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.AUTH_FAILURE -> "AUTHORIZATION"
                listOf("PERMISSION_DENIED", "SOURCE_PERMISSION_REVOKED", "DISPATCH_POLICY_CHANGED").any { messages.contains(it, true) } -> "AUTHORIZATION"
                messages.contains("PROFILE_", true) -> "PROFILE_RESOLUTION"
                messages.contains("interrupted inside native", true) || messages.contains("QUARANTIN", true) -> "RUNTIME_QUARANTINED"
                error is IllegalArgumentException -> "ARGUMENT_VALIDATION"
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
