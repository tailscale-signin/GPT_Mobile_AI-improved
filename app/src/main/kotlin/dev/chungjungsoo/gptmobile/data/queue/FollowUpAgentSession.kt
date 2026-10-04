package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Applies admitted additions at request boundaries without interrupting or repeating tool calls. */
class FollowUpAgentSession(
    initial: AgentProviderSession,
    private val inbox: FollowUpInbox,
    private val continuation: suspend (String, String, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    private var active = initial
    private var applied = 0
    private val replayedCallIds = mutableSetOf<String>()
    private val answerTail = StringBuilder()
    override val handlesToolsInternally get() = active.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        var definitions = tools
        do {
            val additions = inbox.snapshot()
            if (additions.size > applied) {
                val handoff = additions.joinToString("") { "\n\nFollow-up from user:\n${it.text}" }
                active = continuation(handoff, answerTail.toString(), exchanges)
                replayedCallIds += exchanges.flatMap { it.calls }.map { it.callId }
                applied = additions.size
                emit(ProviderEvent.Notice("Follow-up added to the original request.", persistent = false))
            }
            var completed = false
            var failed = false
            var toolCalled = false
            var inputUsage: Int? = null
            var outputUsage: Int? = null
            var totalUsage: Int? = null
            var decodeSpeed: Double? = null
            fun usage(previous: Int?, value: Int, cumulative: Boolean): Int =
                if (cumulative) maxOf(previous ?: 0, value) else ((previous ?: 0).toLong() + value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // Previous exchanges are retained as evidence in the handoff. A fresh
            // stateful provider must not receive orphan results for its predecessor.
            val newExchanges = exchanges.mapNotNull { exchange ->
                val calls = exchange.calls.filterNot { it.callId in replayedCallIds }
                if (calls.isEmpty()) null else AgentToolExchange(calls, exchange.results.filter { result -> calls.any { it.callId == result.callId } })
            }
            active.streamRound(definitions, newExchanges).collect { event ->
                when (event) {
                    ProviderEvent.Completed -> completed = true
                    is ProviderEvent.Failed -> { failed = true; emit(event) }
                    is ProviderEvent.ToolCall -> { toolCalled = true; emit(event) }
                    is ProviderEvent.Usage -> {
                        event.inputTokens?.let { inputUsage = usage(inputUsage, it, event.cumulative) }
                        event.outputTokens?.let { outputUsage = usage(outputUsage, it, event.cumulative) }
                        event.totalTokens?.let { totalUsage = usage(totalUsage, it, event.cumulative) }
                        event.decodeTokensPerSecond?.let { decodeSpeed = it }
                    }
                    is ProviderEvent.TextDelta -> {
                        answerTail.append(event.text)
                        if (answerTail.length > 6000) answerTail.delete(0, answerTail.length - 6000)
                        emit(event)
                    }
                    else -> emit(event)
                }
            }
            if (inputUsage != null || outputUsage != null || totalUsage != null || decodeSpeed != null) {
                emit(ProviderEvent.Usage(inputUsage, outputUsage, totalUsage, cumulative = false, decodeTokensPerSecond = decodeSpeed))
            }
            if (failed || (!completed && !toolCalled)) return@flow
            if (toolCalled) {
                // Return control so pending tools execute exactly once before a handoff.
                emit(ProviderEvent.Completed)
                return@flow
            }
            inbox.awaitCountdown()
            if (inbox.snapshot().size <= applied) {
                inbox.close()
                // Closing joins admission, including any transaction already committing.
                if (inbox.snapshot().size <= applied) {
                    emit(ProviderEvent.Completed)
                    return@flow
                }
            }
            emit(ProviderEvent.TextDelta("\n\n"))
            definitions = emptyList()
        } while (true)
    }
}
