package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal enum class ContinuationTrigger { PROVIDER_OUTPUT_LIMIT, REQUESTED_LENGTH, STREAM_INTERRUPTED, RESTORED_DRAFT, NO_PROGRESS }

/** Bounded text-only continuations. Completed actions are evidence, never replayed. */
internal class OutputLimitRecoverySession(
    private var active: AgentProviderSession,
    private val maxContinuations: Int = 1,
    private val targetWords: Int? = null,
    private val recoverTransientFailures: Boolean = false,
    initialDraft: String = "",
    private val allowInternalToolRecovery: Boolean = false,
    private val continueEmptyOutput: Boolean = true,
    private var effectiveOutputCap: Int? = null,
    private val maxAnswerCharacters: Int = 1_048_576,
    private val combined: CombinedExecutionContext? = null,
    private val adaptToObservedOutput: Boolean = false,
    private val saveProgress: suspend (draft: String, index: Int, outputCap: Int?, reason: String) -> Unit = { _, _, _, _ -> },
    private val onContinuationTrigger: (ContinuationTrigger) -> Unit = {},
    private val continuation: suspend (String, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    private var continuations = 0
    private var transientRetries = 0
    private var noProgressRetries = 0
    private var lowYieldLimitRounds = 0
    private val answer = StringBuilder(initialDraft)
    private val retainedCharacterLimit = maxAnswerCharacters.takeIf { it > 0 } ?: Int.MAX_VALUE
    private var continuationLimit = maxContinuations.coerceAtLeast(0)
    private var observedWordYield: Int? = null
    private var completedAnswerLength: Int? = null

    private fun visibleWords(): Int = if (combined == null) LongResponsePolicy.countWords(answer.toString()) else CombinedWordCounter.count(answer.toString())

    init {
        require(initialDraft.length <= retainedCharacterLimit) { "Saved answer exceeds the configured retention allowance; it was not truncated." }
    }

    private var resumePending = initialDraft.isNotBlank()
    override val handlesToolsInternally: Boolean get() = active.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (handlesToolsInternally && !allowInternalToolRecovery) {
            emitAll(active.streamRound(tools, exchanges).withAssistantTextMarkupGuard(validateSearchClaims = false))
            return@flow
        }
        val evidence = exchanges // Re-admission decides fit; recovery must not silently replace completed evidence with snippets.
        if (resumePending) {
            resumePending = false
            emit(ProviderEvent.TextDelta(answer.toString()))
            if (continuationLimit == 0) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_DISABLED: The saved answer is retained after 0 continuation requests; automatic continuation is disabled.", errorCode = "OUTPUT_CONTINUATION_DISABLED"))
                return@flow
            }
            onContinuationTrigger(ContinuationTrigger.RESTORED_DRAFT)
            active = try {
                continuation(answer.toString(), evidence)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        saveProgress(answer.toString(), continuations, effectiveOutputCap, "CANCELLED")
                    } catch (saveFailure: Exception) {
                        cancelled.addSuppressed(saveFailure)
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_UNAVAILABLE: The saved answer is retained, but its continuation could not fit the context or reach the provider.", errorCode = "OUTPUT_CONTINUATION_UNAVAILABLE"))
                return@flow
            }
            continuations = 1
        }
        while (true) {
            val continuing = continuations > 0 || transientRetries > 0
            var failure: ProviderEvent.Failed? = null
            var completed = false
            var reportedOutputTokens: Int? = null
            var reportedFinishReason: String? = null
            val pendingCalls = mutableListOf<ProviderEvent.ToolCall>()
            val admission = ToolRoundAdmission()
            val segment = StringBuilder()
            var savedCharacters = 0
            try {
                active.streamRound(if (continuing) emptyList() else tools, if (continuing) emptyList() else exchanges)
                    .withAssistantTextMarkupGuard(validateSearchClaims = false).withSummedRequestUsage().collect { event ->
                        when (event) {
                            is ProviderEvent.ToolCall -> {
                                if (!admission.admit(event)) throw ToolBatchOversizeException()
                                if (continuing) failure = ProviderEvent.Failed("The continuation requested a tool despite tools being disabled. No action was repeated.") else pendingCalls += event
                            }
                            is ProviderEvent.ToolResult -> if (continuing) {
                                failure = ProviderEvent.Failed("The continuation returned a tool result despite tools being disabled. No action was repeated.")
                            } else {
                                emit(event)
                            }
                            is ProviderEvent.RequestConfigured -> {
                                effectiveOutputCap = listOfNotNull(event.effectiveOutputTokens, event.serializedOutputTokens, event.providerOutputCeiling)
                                    .filter { it > 0 }.minOrNull() ?: effectiveOutputCap
                                emit(event)
                            }
                            is ProviderEvent.Usage -> {
                                reportedOutputTokens = event.outputTokens ?: reportedOutputTokens
                                reportedFinishReason = event.finishReason ?: reportedFinishReason
                                emit(event)
                            }
                            is ProviderEvent.Failed -> failure = event
                            is ProviderEvent.TextDelta -> {
                                // Bound retained text, including misbehaving providers that ignore output caps.
                                check(answer.length.toLong() + segment.length + event.text.length <= retainedCharacterLimit) { "Response exceeded the safe continuation buffer. Partial output is saved." }
                                segment.append(event.text)
                                if (!continuing) emit(event)
                                if (segment.length - savedCharacters >= 4096) {
                                    val progress = if (continuing) continuationSuffix(answer.toString(), segment.toString()) else segment.toString()
                                    saveProgress(answer.toString() + progress, continuations, effectiveOutputCap, "WRITING")
                                    savedCharacters = segment.length
                                }
                            }
                            ProviderEvent.Completed -> completed = true
                            else -> emit(event)
                        }
                    }
            } catch (failure: Exception) {
                // Preserve even a short, interrupted segment. Never emit from this catch: a
                // downstream collector failure must retain Flow exception transparency.
                val progress = if (continuing) continuationSuffix(answer.toString(), segment.toString()) else segment.toString()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        saveProgress(answer.toString() + progress, continuations, effectiveOutputCap, if (failure is kotlinx.coroutines.CancellationException) "CANCELLED" else "PARTIAL")
                    } catch (saveFailure: Exception) {
                        failure.addSuppressed(saveFailure)
                    }
                }
                throw failure
            }
            val novel = if (continuing) continuationSuffix(answer.toString(), segment.toString()) else segment.toString()
            if (continuing && novel.isNotEmpty()) emit(ProviderEvent.TextDelta(novel))
            answer.append(novel)
            saveProgress(answer.toString(), continuations, effectiveOutputCap, "SEGMENT_COMMITTED")
            val limitFailure = failure?.let { dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true ||
                reportedFinishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") ||
                (pendingCalls.isEmpty() && LongResponsePolicy.unfinishedQueuedPacket(segment.toString(), reportedFinishReason, effectiveOutputCap))
            if (completed && !limitFailure && failure == null && pendingCalls.isEmpty() && answer.isNotBlank() && (!continuing || novel.isNotBlank())) completedAnswerLength = answer.length
            suspend fun retainedFailure(original: ProviderEvent.Failed) {
                val recovering = continuations > 0 || transientRetries > 0
                val earlierComplete = completedAnswerLength == answer.length && recovering
                if (completedAnswerLength != null && recovering) {
                    emit(ProviderEvent.Notice("The earlier answer is saved. Its additional continuation did not finish; any unmet length requirement still applies.", persistent = true))
                }
                emit(if (earlierComplete) original.copy(transportComplete = true, errorCode = original.errorCode ?: "CONTINUATION_REQUIREMENT_UNMET") else original)
            }
            if (limitFailure && answer.isBlank() && !continueEmptyOutput) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "FAILED")
                emit(failure ?: ProviderEvent.Failed("The model reached its output limit without a visible answer. Check reasoning and output settings before retrying."))
                return@flow
            }
            lowYieldLimitRounds = if (limitFailure && (reportedOutputTokens ?: 0) >= 1024 && novel.toByteArray(Charsets.UTF_8).size <= 256) lowYieldLimitRounds + 1 else 0
            if (lowYieldLimitRounds >= 2) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                retainedFailure(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_VISIBLE_PROGRESS: Two output-limited requests produced negligible visible output. The partial answer and evidence are saved. Check the profile's reasoning and output settings before explicitly retrying."))
                return@flow
            }
            val transientFailure = failure != null &&
                !limitFailure &&
                recoverTransientFailures &&
                (tools.isEmpty() || continuing) &&
                pendingCalls.isEmpty() &&
                isRecoverableStreamFailure(requireNotNull(failure).message) &&
                transientRetries < 2
            if (failure != null && !limitFailure && !transientFailure) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, if (answer.isNotEmpty()) "PARTIAL" else "FAILED")
                retainedFailure(requireNotNull(failure))
                return@flow
            }
            val needsMore = if (combined == null) LongResponsePolicy.needsMore(answer.toString(), targetWords) else targetWords?.let { visibleWords() < it } == true
            val shortAnswer = completed && pendingCalls.isEmpty() && needsMore
            val noProgress = continuing && novel.isBlank() && !transientFailure && pendingCalls.isEmpty()
            // A successful but repeated/empty packet gets one text-only regeneration
            // within the existing request allowance. Provider failures retain their own
            // recovery policy; a second repetition cannot become an unbounded loop.
            val regenerateNoProgress = noProgress && completed && failure == null && noProgressRetries == 0 && continuations < continuationLimit && answer.length < retainedCharacterLimit
            if (noProgress && !regenerateNoProgress) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                retainedFailure(
                    ProviderEvent.Failed(
                        "OUTPUT_CONTINUATION_NO_PROGRESS: The provider returned no new answer text. The existing ${visibleWords()} words are saved; the answer was not restarted or duplicated.",
                        errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS"
                    )
                )
                return@flow
            }
            if (!limitFailure && !shortAnswer && !transientFailure && !regenerateNoProgress) {
                pendingCalls.forEach { emit(it) }
                saveProgress(answer.toString(), continuations, effectiveOutputCap, if (completed) "COMPLETED" else "PARTIAL")
                if (completed) emit(ProviderEvent.Completed)
                return@flow
            }
            if (adaptToObservedOutput && continuationLimit > 0 && needsMore && novel.isNotBlank()) {
                val words = LongResponsePolicy.countWords(novel).takeIf { it > 0 }
                if (words != null) observedWordYield = minOf(observedWordYield ?: words, words)
                // The serialized cap and observed yield can be much lower than the profile's
                // advertised allowance. Fund the remaining requested work from what was
                // actually delivered instead of repeating a fixed two-request policy.
                val remaining = (requireNotNull(targetWords) - visibleWords()).coerceAtLeast(0)
                val basedOnYield = observedWordYield?.let { yield ->
                    ((remaining.toLong() + yield - 1) / yield + continuations + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } ?: 0
                continuationLimit = maxOf(continuationLimit, basedOnYield, LongResponsePolicy.continuationLimit(targetWords, effectiveOutputCap))
            }
            if ((!transientFailure && continuations >= continuationLimit) || answer.length >= retainedCharacterLimit) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                val reason = if (continuationLimit == 0) "OUTPUT_CONTINUATION_DISABLED" else "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED"
                retainedFailure(
                    ProviderEvent.Failed(
                        "$reason: The response is incomplete after $continuations continuation requests (${visibleWords()} words" +
                            (targetWords?.let { " of approximately $it requested" } ?: "") + "). The partial answer and completed evidence are saved; no tool actions were repeated.",
                        errorCode = reason
                    )
                )
                return@flow
            }
            if (transientFailure) {
                transientRetries++
                emit(ProviderEvent.Notice("The response stream was interrupted. Retrying from saved output ($transientRetries/2).", false))
                kotlinx.coroutines.delay(transientRetries * 1000L)
            } else {
                continuations++
                if (regenerateNoProgress) noProgressRetries++
            }
            if (regenerateNoProgress) {
                emit(ProviderEvent.Notice("The continuation added no new text. Regenerating the unfinished continuation once from the saved answer; completed actions will not repeat.", false))
            } else if (!transientFailure) {
                emit(ProviderEvent.Notice("Continuing the response (part ${continuations + 1}) from the saved answer and completed evidence.", false))
            }
            val trigger = when {
                transientFailure -> ContinuationTrigger.STREAM_INTERRUPTED
                regenerateNoProgress -> ContinuationTrigger.NO_PROGRESS
                limitFailure -> ContinuationTrigger.PROVIDER_OUTPUT_LIMIT
                else -> ContinuationTrigger.REQUESTED_LENGTH
            }
            onContinuationTrigger(trigger)
            saveProgress(answer.toString(), continuations, effectiveOutputCap, trigger.name)
            active = try {
                continuation(answer.toString(), evidence)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        saveProgress(answer.toString(), continuations, effectiveOutputCap, "CANCELLED")
                    } catch (saveFailure: Exception) {
                        cancelled.addSuppressed(saveFailure)
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                retainedFailure(ProviderEvent.Failed("Automatic continuation could not fit the context or reach the provider. The partial answer and completed tool results are saved."))
                return@flow
            }
        }
    }
}

/** Remove literal restart/overlap without guessing that similar but distinct facts are duplicates. */
internal fun continuationSuffix(previous: String, next: String): String {
    if (next.isBlank()) return ""
    val trimmed = next.trim()
    if (trimmed == previous.trim() || (trimmed.length >= 20 && trimmed in previous)) return ""
    // Prefix-function matching is linear even for long, repetitive model output.
    val prefix = IntArray(next.length)
    for (index in 1 until next.length) {
        var matched = prefix[index - 1]
        while (matched > 0 && next[index] != next[matched]) matched = prefix[matched - 1]
        if (next[index] == next[matched]) matched++
        prefix[index] = matched
    }
    var overlap = 0
    for (index in (previous.length - next.length).coerceAtLeast(0) until previous.length) {
        if (overlap == next.length) overlap = prefix[overlap - 1]
        while (overlap > 0 && previous[index] != next[overlap]) overlap = prefix[overlap - 1]
        if (previous[index] == next[overlap]) overlap++
    }
    return if (overlap >= 20) next.substring(overlap) else next
}

/** Convert cumulative provider snapshots into one additive usage event per request. */
internal fun Flow<ProviderEvent>.withSummedRequestUsage(): Flow<ProviderEvent> = flow {
    val input = ProviderUsageCounter()
    val output = ProviderUsageCounter()
    val total = ProviderUsageCounter()
    var reasoning: Int? = null
    var speed: Double? = null
    var metadata: ProviderEvent.Usage? = null
    fun add(previous: Int?, value: Int, cumulative: Boolean): Int =
        if (cumulative) maxOf(previous ?: 0, value) else ((previous ?: 0).toLong() + value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    collect { event ->
        if (event is ProviderEvent.Usage) {
            val previous = metadata
            metadata = event.copy(
                finishReason = event.finishReason ?: previous?.finishReason,
                retryDurationMs = event.retryDurationMs ?: previous?.retryDurationMs,
                retryAttempts = event.retryAttempts ?: previous?.retryAttempts,
                actualRuntime = event.actualRuntime ?: previous?.actualRuntime,
                writerObservationKey = event.writerObservationKey ?: previous?.writerObservationKey,
                effectiveSettingsConfirmed = event.effectiveSettingsConfirmed || previous?.effectiveSettingsConfirmed == true
            )
            input.accept(event.inputTokens, event.cumulative, event.inputSource, event.phase)
            output.accept(event.outputTokens, event.cumulative, event.outputSource, event.phase)
            total.accept(event.totalTokens, event.cumulative, if (measuredUsageSource(event.inputSource, event.phase) && measuredUsageSource(event.outputSource, event.phase)) "mixed_reported" else "estimated", event.phase)
            event.reasoningTokens?.let { reasoning = add(reasoning, it, event.cumulative) }
            event.decodeTokensPerSecond?.let { speed = it }
        } else {
            emit(event)
        }
    }
    metadata?.let { last ->
        emit(
            last.copy(
                inputTokens = input.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                outputTokens = output.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                totalTokens = total.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                inputSource = input.source,
                outputSource = output.source,
                phase = if (listOf(input, output, total).filter { it.value != null }.all { it.phase == ProviderUsagePhase.FINAL }) ProviderUsagePhase.FINAL else ProviderUsagePhase.INTERIM,
                cumulative = false, decodeTokensPerSecond = speed, reasoningTokens = reasoning
            )
        )
    }
}
