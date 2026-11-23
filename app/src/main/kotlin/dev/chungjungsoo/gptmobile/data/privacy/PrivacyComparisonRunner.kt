package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded, sequential paired inference over the closed fictional corpus. No automatic retry. */
class PrivacyComparisonRunner(
    private val openSession: suspend (caseId: String, protection: Boolean, requestId: String, deadlineMs: Long) -> PrivacyComparisonSession,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val requestTimeoutMs: Long = 60_000,
    private val overallTimeoutMs: Long = 600_000
) {
    init {
        require(requestTimeoutMs in 1..60_000 && overallTimeoutMs in 1..600_000)
    }

    suspend fun run(
        runId: String = UUID.randomUUID().toString(),
        onUpdate: (PrivacyComparisonResult) -> Unit = {}
    ): PrivacyComparisonResult {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        var result = PrivacyComparisonResult(runId)
        var policy: PrivacyModeState? = null
        var authorityRevision: String? = null
        var authorityCaptured = false
        fun update(next: PrivacyComparisonResult) {
            result = next
            onUpdate(next)
        }
        update(result)
        try {
            val finished = withTimeoutOrNull(overallTimeoutMs) {
                // Both preparations are visible but excluded from the scored population.
                for (arm in PrivacyComparisonArm.entries) {
                    update(result.copy(activeCaseId = PrivacyComparisonCase.WARMUP.id, activeArm = arm))
                    val leg = execute(PrivacyComparisonCase.WARMUP, arm, runId, policy, authorityRevision, authorityCaptured)
                    if (!authorityCaptured) {
                        authorityRevision = leg.authorityRevision
                        authorityCaptured = true
                    }
                    if (arm == PrivacyComparisonArm.ON && leg.policy != null) policy = leg.policy
                    if (!leg.taskCriteriaPassed) {
                        update(result.copy(status = PrivacyComparisonStatus.INCOMPLETE, failure = leg.failure ?: PrivacyComparisonFailure.WARMUP_FAILED))
                        return@withTimeoutOrNull result
                    }
                    update(result.copy(warmupCompleted = result.warmupCompleted + 1))
                }
                for ((index, case) in PrivacyComparisonCase.scored.withIndex()) {
                    // Alternate the first arm, so protection is not always the warm second request.
                    val order = if (index % 2 == 0) PrivacyComparisonArm.entries.toList() else PrivacyComparisonArm.entries.reversed()
                    for (arm in order) {
                        currentCoroutineContext().ensureActive()
                        update(result.copy(activeCaseId = case.id, activeArm = arm))
                        val leg = execute(case, arm, runId, policy, authorityRevision, authorityCaptured) { interrupted ->
                            update(result.copy(legs = result.legs + interrupted))
                        }
                        if (arm == PrivacyComparisonArm.ON && leg.policy != null && policy == null) policy = leg.policy
                        update(result.copy(legs = result.legs + leg))
                        if (leg.status != PrivacyComparisonLegStatus.COMPLETE) {
                            update(result.copy(status = PrivacyComparisonStatus.INCOMPLETE, failure = leg.failure))
                            return@withTimeoutOrNull result
                        }
                    }
                }
                result.copy(status = PrivacyComparisonStatus.COMPLETE, activeCaseId = null, activeArm = null)
            }
            update(finished ?: result.copy(status = PrivacyComparisonStatus.INCOMPLETE, failure = PrivacyComparisonFailure.OVERALL_TIMEOUT))
            return result
        } catch (cancelled: CancellationException) {
            update(result.copy(status = PrivacyComparisonStatus.CANCELLED, failure = PrivacyComparisonFailure.CANCELLED))
            throw cancelled
        }
    }

    private suspend fun execute(
        case: PrivacyComparisonCase,
        arm: PrivacyComparisonArm,
        runId: String,
        expectedPolicy: PrivacyModeState?,
        expectedAuthorityRevision: String?,
        authorityCaptured: Boolean,
        onInterrupted: (PrivacyComparisonLeg) -> Unit = {}
    ): PrivacyComparisonLeg {
        val started = nowMillis()
        val answer = StringBuilder()
        var firstText: Long? = null
        var complete = false
        var providerFailed = false
        var failure: PrivacyComparisonFailure? = null
        var prepared: PrivacyComparisonSession? = null
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var actualRuntime: String? = null
        var effectiveOutputTokens: Int? = null
        var configurationChanged = false
        var blocked = false
        fun snapshot(statusOverride: PrivacyComparisonLegStatus? = null): PrivacyComparisonLeg {
            val observation = prepared?.measurements?.invoke() ?: PrivacyComparisonMeasurements()
            val state = prepared?.policy
            val status = statusOverride ?: when {
                complete && failure == null -> PrivacyComparisonLegStatus.COMPLETE
                blocked -> PrivacyComparisonLegStatus.BLOCKED
                failure == PrivacyComparisonFailure.TIMEOUT -> PrivacyComparisonLegStatus.TIMED_OUT
                answer.isNotEmpty() -> PrivacyComparisonLegStatus.PARTIAL
                else -> PrivacyComparisonLegStatus.FAILED
            }
            val elapsed = (nowMillis() - started).coerceAtLeast(0)
            return PrivacyComparisonLeg(
                caseId = case.id,
                arm = arm,
                status = status,
                answer = answer.toString(),
                criteria = if (status == PrivacyComparisonLegStatus.COMPLETE) case.assess(answer.toString()) else emptyList(),
                firstTextMs = firstText,
                completionMs = elapsed.takeIf { status == PrivacyComparisonLegStatus.COMPLETE },
                elapsedMs = elapsed,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                measurements = observation,
                detection = if (arm == PrivacyComparisonArm.ON && state != null) detect(case, state, observation.inputReceipt) else null,
                policy = state,
                actualRuntime = actualRuntime,
                effectiveOutputTokens = effectiveOutputTokens,
                configurationChanged = configurationChanged,
                authorityRevision = prepared?.authorityRevision,
                failure = failure
            )
        }
        try {
            val finished = withTimeoutOrNull(requestTimeoutMs) {
                val session = openSession(case.id, arm == PrivacyComparisonArm.ON, "$runId-${case.id}-${arm.name.lowercase()}", requestTimeoutMs)
                prepared = session
                if (authorityCaptured && session.authorityRevision != expectedAuthorityRevision) {
                    throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.POLICY_CHANGED)
                }
                if (arm == PrivacyComparisonArm.ON && (session.policy?.enabled != true || expectedPolicy != null && session.policy != expectedPolicy)) {
                    throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.POLICY_CHANGED)
                }
                currentCoroutineContext().ensureActive()
                session.session.streamRound(emptyList(), emptyList()).takeWhile { event ->
                    currentCoroutineContext().ensureActive()
                    // Let the provider and accounting finish normally after a real
                    // terminal event. Aborting its emit would falsely mark it cancelled.
                    if (complete || providerFailed) return@takeWhile true
                    var stopImmediately = false
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (failure != null) return@takeWhile true
                            if (event.text.isNotEmpty() && firstText == null) firstText = (nowMillis() - started).coerceAtLeast(0)
                            if (event.text.length > MAX_ANSWER_CHARACTERS - answer.length) {
                                answer.append(event.text.take(MAX_ANSWER_CHARACTERS - answer.length))
                                failure = PrivacyComparisonFailure.OUTPUT_LIMIT
                                stopImmediately = true
                            } else {
                                answer.append(event.text)
                            }
                        }
                        is ProviderEvent.RequestConfigured -> {
                            event.actualRuntime?.boundedRuntime()?.let {
                                if (actualRuntime != null && actualRuntime != it) configurationChanged = true
                                actualRuntime = it
                            }
                            event.effectiveOutputTokens?.let {
                                if (effectiveOutputTokens != null && effectiveOutputTokens != it) configurationChanged = true
                                effectiveOutputTokens = it
                                if (it <= 0 || it > OUTPUT_TOKENS) {
                                    failure = PrivacyComparisonFailure.UNSUPPORTED_PROFILE
                                    stopImmediately = true
                                }
                            }
                            if (event.advertisedToolCount?.let { it > 0 } == true) {
                                failure = PrivacyComparisonFailure.UNEXPECTED_TOOL
                                stopImmediately = true
                            }
                        }
                        is ProviderEvent.Usage -> {
                            // Do not invent a total from interim, delta or estimated usage.
                            if (event.phase == ProviderUsagePhase.FINAL && event.cumulative) {
                                event.inputTokens?.takeIf { it >= 0 && event.inputSource == "provider_reported" }?.let {
                                    inputTokens = maxOf(inputTokens ?: 0, it)
                                }
                                event.outputTokens?.takeIf { it >= 0 && event.outputSource == "provider_reported" }?.let {
                                    outputTokens = maxOf(outputTokens ?: 0, it)
                                }
                            }
                            if (event.finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit")) failure = PrivacyComparisonFailure.OUTPUT_LIMIT
                            event.actualRuntime?.boundedRuntime()?.let {
                                if (actualRuntime != null && actualRuntime != it) configurationChanged = true
                                actualRuntime = it
                            }
                        }
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> {
                            failure = PrivacyComparisonFailure.UNEXPECTED_TOOL
                            stopImmediately = true
                        }
                        is ProviderEvent.Failed -> {
                            providerFailed = true
                            failure = when (event.errorCode) {
                                "DISPATCH_POLICY_CHANGED", "PRIVATE_DISPATCH_POLICY_CHANGED" -> PrivacyComparisonFailure.POLICY_CHANGED
                                "OUTPUT_LIMIT", "LOCAL_OUTPUT_LIMIT" -> PrivacyComparisonFailure.OUTPUT_LIMIT
                                else -> failure ?: PrivacyComparisonFailure.PROVIDER_FAILED
                            }
                        }
                        ProviderEvent.Completed -> complete = true
                        else -> Unit
                    }
                    !stopImmediately
                }.collect()
                true
            }
            if (finished == null) {
                failure = PrivacyComparisonFailure.TIMEOUT
            } else if (!complete && failure == null) {
                failure = PrivacyComparisonFailure.INCOMPLETE_STREAM
            }
        } catch (cancelled: CancellationException) {
            failure = PrivacyComparisonFailure.CANCELLED
            onInterrupted(snapshot(PrivacyComparisonLegStatus.CANCELLED))
            throw cancelled
        } catch (rejected: PrivacyComparisonBlockedException) {
            failure = rejected.code
            blocked = true
        } catch (_: Exception) {
            failure = if (prepared == null) PrivacyComparisonFailure.INITIALIZATION_FAILED else PrivacyComparisonFailure.PROVIDER_FAILED
        }
        return snapshot()
    }

    private fun String.boundedRuntime(): String? = takeIf { matches(Regex("[A-Za-z0-9_.:/-]{1,80}")) }

    companion object {
        const val OUTPUT_TOKENS = 512
        const val MAX_ANSWER_CHARACTERS = 8192

        internal fun detect(case: PrivacyComparisonCase, policy: PrivacyModeState, receipt: PrivacyProtectionReceipt?): PrivacyComparisonDetection? {
            if (receipt == null || !receipt.confirmed || !receipt.validFor(case.prompt)) return null
            fun occurrences(value: String): List<Int> {
                val offsets = mutableListOf<Int>()
                var offset = case.prompt.indexOf(value, ignoreCase = true)
                while (offset >= 0) {
                    offsets += offset
                    offset = case.prompt.indexOf(value, offset + 1, ignoreCase = true)
                }
                return offsets
            }
            val expected = case.expectedPrivateValues.filter { (_, categories) -> categories.any { it in policy.categories } }
            val protected = expected.count { (value, _) ->
                val positions = occurrences(value)
                positions.isNotEmpty() && positions.all { start -> receipt.ranges.any { it.start <= start && it.endExclusive >= start + value.length } }
            }
            val preserved = case.publicPhrases.count { phrase ->
                val positions = occurrences(phrase)
                positions.isNotEmpty() && positions.all { start -> receipt.ranges.none { it.start < start + phrase.length && it.endExclusive > start } }
            }
            return PrivacyComparisonDetection(expected.size, protected, case.publicPhrases.size, preserved)
        }
    }
}
