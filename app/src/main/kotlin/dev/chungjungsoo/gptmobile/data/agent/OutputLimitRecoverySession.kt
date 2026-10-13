package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Bounded text-only continuations. Completed actions are evidence, never replayed. */
internal class OutputLimitRecoverySession(
    private var active: AgentProviderSession,
    private val maxContinuations: Int = 1,
    private val targetWords: Int? = null,
    private val recoverTransientFailures: Boolean = false,
    initialDraft: String = "",
    private var effectiveOutputCap: Int? = null,
    private val maxAnswerCharacters: Int = 1_048_576,
    private val saveProgress: suspend (draft: String, index: Int, outputCap: Int?, reason: String) -> Unit = { _, _, _, _ -> },
    private val continuation: suspend (String, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    private var continuations = 0
    private var transientRetries = 0
    private val answer = StringBuilder(initialDraft)
    private val retainedCharacterLimit = maxAnswerCharacters.takeIf { it > 0 } ?: Int.MAX_VALUE

    init {
        require(initialDraft.length <= retainedCharacterLimit) { "Saved answer exceeds the configured retention allowance; it was not truncated." }
    }

    private var resumePending = initialDraft.isNotBlank()
    override val handlesToolsInternally: Boolean get() = active.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (handlesToolsInternally) {
            emitAll(active.streamRound(tools, exchanges))
            return@flow
        }
        val evidence = exchanges // Re-admission decides fit; recovery must not silently replace completed evidence with snippets.
        if (resumePending) {
            resumePending = false
            emit(ProviderEvent.TextDelta(answer.toString()))
            active = continuation(answer.toString(), evidence)
            continuations = 1
        }
        while (true) {
            val continuing = continuations > 0 || transientRetries > 0
            var failure: ProviderEvent.Failed? = null
            var completed = false
            val pendingCalls = mutableListOf<ProviderEvent.ToolCall>()
            val admission = ToolRoundAdmission()
            val segment = StringBuilder()
            var savedCharacters = 0
            try {
                active.streamRound(if (continuing) emptyList() else tools, if (continuing) emptyList() else exchanges)
                    .withSummedRequestUsage().collect { event ->
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
                                effectiveOutputCap = event.effectiveOutputTokens ?: effectiveOutputCap
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
            val limitFailure = failure?.let { dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true
            val transientFailure = failure != null &&
                !limitFailure &&
                recoverTransientFailures &&
                (tools.isEmpty() || continuing) &&
                pendingCalls.isEmpty() &&
                isRecoverableStreamFailure(requireNotNull(failure).message) &&
                transientRetries < 2
            if (failure != null && !limitFailure && !transientFailure) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, if (answer.isNotEmpty()) "PARTIAL" else "FAILED")
                emit(requireNotNull(failure))
                return@flow
            }
            val shortAnswer = completed && pendingCalls.isEmpty() && LongResponsePolicy.needsMore(answer.toString(), targetWords)
            if (!limitFailure && !shortAnswer && !transientFailure) {
                pendingCalls.forEach { emit(it) }
                saveProgress(answer.toString(), continuations, effectiveOutputCap, if (completed) "COMPLETED" else "PARTIAL")
                if (completed) emit(ProviderEvent.Completed)
                return@flow
            }
            if ((!transientFailure && continuations >= maxContinuations.coerceIn(0, 16)) || answer.length >= retainedCharacterLimit || (!transientFailure && continuing && novel.isBlank())) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                emit(
                    ProviderEvent.Failed(
                        "The response is incomplete after bounded continuation (${LongResponsePolicy.countWords(answer.toString())} words" +
                            (targetWords?.let { " of approximately $it requested" } ?: "") + "). The partial answer and completed evidence are saved; no tool actions were repeated."
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
            }
            if (!transientFailure) emit(ProviderEvent.Notice("Continuing the response ($continuations/${maxContinuations.coerceIn(0, 16)}) from the saved answer and completed evidence.", false))
            active = try {
                continuation(answer.toString(), evidence)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveProgress(answer.toString(), continuations, effectiveOutputCap, "PARTIAL")
                emit(ProviderEvent.Failed("Automatic continuation could not fit the context or reach the provider. The partial answer and completed tool results are saved."))
                return@flow
            }
        }
    }
}

/** Remove literal restart/overlap without guessing that similar but distinct facts are duplicates. */
internal fun continuationSuffix(previous: String, next: String): String {
    if (next.isBlank()) return ""
    if (next.trim() in previous) return ""
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
private fun Flow<ProviderEvent>.withSummedRequestUsage(): Flow<ProviderEvent> = flow {
    var input: Int? = null
    var output: Int? = null
    var total: Int? = null
    var speed: Double? = null
    fun add(previous: Int?, value: Int, cumulative: Boolean): Int =
        if (cumulative) maxOf(previous ?: 0, value) else ((previous ?: 0).toLong() + value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    collect { event ->
        if (event is ProviderEvent.Usage) {
            event.inputTokens?.let { input = add(input, it, event.cumulative) }
            event.outputTokens?.let { output = add(output, it, event.cumulative) }
            event.totalTokens?.let { total = add(total, it, event.cumulative) }
            event.decodeTokensPerSecond?.let { speed = it }
        } else {
            emit(event)
        }
    }
    if (input != null || output != null || total != null || speed != null) {
        emit(ProviderEvent.Usage(input, output, total, cumulative = false, decodeTokensPerSecond = speed))
    }
}
