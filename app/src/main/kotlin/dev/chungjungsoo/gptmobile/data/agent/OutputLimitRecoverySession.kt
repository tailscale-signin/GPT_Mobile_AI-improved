package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** One bounded text-only continuation. Completed actions are evidence, never replayed. */
internal class OutputLimitRecoverySession(
    private var active: AgentProviderSession,
    private val continuation: suspend (String, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    private var recovered = false
    private val answerTail = StringBuilder()
    override val handlesToolsInternally: Boolean get() = active.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (handlesToolsInternally) {
            emitAll(active.streamRound(tools, exchanges))
            return@flow
        }
        var outputFailure: ProviderEvent.Failed? = null
        val pendingCalls = mutableListOf<ProviderEvent.ToolCall>()
        active.streamRound(if (recovered) emptyList() else tools, if (recovered) emptyList() else exchanges).withSummedRequestUsage().collect { event ->
            when {
                event is ProviderEvent.ToolCall -> pendingCalls += event
                event is ProviderEvent.Failed && event.message.contains("reached its output limit", true) && !recovered && !handlesToolsInternally -> outputFailure = event
                else -> {
                    if (event is ProviderEvent.TextDelta) {
                        answerTail.append(event.text)
                        if (answerTail.length > 12_000) answerTail.delete(0, answerTail.length - 12_000)
                    }
                    // Do not emit a premature terminal event after an output-limit failure.
                    if (outputFailure == null || event is ProviderEvent.Usage) emit(event)
                }
            }
        }
        if (outputFailure == null) {
            pendingCalls.forEach { emit(it) }
            return@flow
        }
        recovered = true
        emit(ProviderEvent.Notice("The response reached its output limit. Continuing once from the saved answer and completed evidence.", false))
        val evidence = ToolExchangeCompactor.compact(exchanges, maxReplayTokens = 4_000, maxResultTokens = 512)
        active = try {
            continuation(answerTail.toString(), evidence)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(ProviderEvent.Failed("The response reached its output limit and automatic continuation was unavailable. The partial answer and completed tool results are saved."))
            return@flow
        }
        // This fresh request has no tool definitions and no pending tool calls.
        // A second truncation remains a failure with the partial output intact.
        active.streamRound(emptyList(), emptyList()).withSummedRequestUsage().collect { event ->
            if (event is ProviderEvent.ToolCall || event is ProviderEvent.ToolResult) {
                emit(ProviderEvent.Failed("The continuation requested a tool despite tools being disabled. No action was repeated."))
            } else {
                emit(event)
            }
        }
    }
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
