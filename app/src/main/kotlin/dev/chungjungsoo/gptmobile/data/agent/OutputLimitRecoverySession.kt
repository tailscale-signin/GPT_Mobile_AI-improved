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
    private val continuation: suspend (String, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    private var continuations = 0
    private val answer = StringBuilder()
    override val handlesToolsInternally: Boolean get() = active.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (handlesToolsInternally) {
            emitAll(active.streamRound(tools, exchanges))
            return@flow
        }
        val evidence = ToolExchangeCompactor.compact(exchanges, maxReplayTokens = 4_000, maxResultTokens = 512)
        while (true) {
            val continuing = continuations > 0
            var failure: ProviderEvent.Failed? = null
            var completed = false
            val pendingCalls = mutableListOf<ProviderEvent.ToolCall>()
            val segment = StringBuilder()
            active.streamRound(if (continuing) emptyList() else tools, if (continuing) emptyList() else exchanges)
                .withSummedRequestUsage().collect { event ->
                    when (event) {
                        is ProviderEvent.ToolCall -> {
                            if (continuing) failure = ProviderEvent.Failed("The continuation requested a tool despite tools being disabled. No action was repeated.") else pendingCalls += event
                        }
                        is ProviderEvent.ToolResult -> if (continuing) {
                            failure = ProviderEvent.Failed("The continuation returned a tool result despite tools being disabled. No action was repeated.")
                        } else {
                            emit(event)
                        }
                        is ProviderEvent.Failed -> failure = event
                        is ProviderEvent.TextDelta -> {
                            // Bound retained text, including misbehaving providers that ignore output caps.
                            check(segment.length + event.text.length <= MAX_ANSWER_CHARACTERS) { "Response exceeded the safe continuation buffer. Partial output is saved." }
                            segment.append(event.text)
                            if (!continuing) emit(event)
                        }
                        ProviderEvent.Completed -> completed = true
                        else -> emit(event)
                    }
                }
            val novel = if (continuing) continuationSuffix(answer.toString(), segment.toString()) else segment.toString()
            if (continuing && novel.isNotEmpty()) emit(ProviderEvent.TextDelta(novel))
            answer.append(novel)
            val limitFailure = failure?.let { dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true
            if (failure != null && !limitFailure) {
                emit(requireNotNull(failure))
                return@flow
            }
            val shortAnswer = completed && pendingCalls.isEmpty() && LongResponsePolicy.needsMore(answer.toString(), targetWords)
            if (!limitFailure && !shortAnswer) {
                pendingCalls.forEach { emit(it) }
                if (completed) emit(ProviderEvent.Completed)
                return@flow
            }
            if (continuations >= maxContinuations.coerceIn(0, 16) || answer.length >= MAX_ANSWER_CHARACTERS || continuing && novel.isBlank()) {
                emit(
                    ProviderEvent.Failed(
                        "The response is incomplete after bounded continuation (${LongResponsePolicy.countWords(answer.toString())} words" +
                            (targetWords?.let { " of approximately $it requested" } ?: "") + "). The partial answer and completed evidence are saved; no tool actions were repeated."
                    )
                )
                return@flow
            }
            continuations++
            emit(ProviderEvent.Notice("Continuing the response ($continuations/${maxContinuations.coerceIn(0, 16)}) from the saved answer and completed evidence.", false))
            active = try {
                continuation(answer.toString(), evidence)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emit(ProviderEvent.Failed("Automatic continuation could not fit the context or reach the provider. The partial answer and completed tool results are saved."))
                return@flow
            }
        }
    }

    private companion object {
        const val MAX_ANSWER_CHARACTERS = 160_000
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
