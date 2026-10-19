package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Saves validated records and unfinished raw bytes before publishing any record to the delivery decoder. */
internal class CombinedRecordRecoverySession(
    private var active: AgentProviderSession,
    private val protocol: CombinedRecordProtocol,
    private val maxContinuations: Int,
    private val restored: Boolean = false,
    private val validateAccess: suspend () -> Unit,
    private val save: suspend (CombinedRecordCheckpoint) -> Unit,
    private val continuation: suspend (CombinedRecordCheckpoint, String) -> AgentProviderSession
) : AgentProviderSession {
    override val handlesToolsInternally = false

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        var continuations = 0
        var noProgress = 0
        if (restored) {
            val checkpoint = protocol.checkpoint()
            checkpoint.records.forEach { emit(ProviderEvent.TextDelta(it.wire + "\n")) }
            if (checkpoint.failure != null) {
                emit(ProviderEvent.Failed("${checkpoint.failure}: Saved records remain available; this stream requires an explicit new revision."))
                return@flow
            }
            if (checkpoint.ended) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            validateAccess()
            val context = protocol.continuationContext()
            save(protocol.checkpoint())
            active = continuation(protocol.checkpoint(), context)
        }
        while (true) {
            validateAccess()
            val before = protocol.checkpoint()
            var complete = false
            var failure: ProviderEvent.Failed? = null
            var finishReason: String? = null
            try {
                active.streamRound(emptyList(), emptyList()).withSummedRequestUsage().collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (protocol.checkpoint().failure == null) {
                                val records = protocol.accept(event.text)
                                // The transaction includes raw fragments, sequence, text and attribution records.
                                // A stale owner cannot publish after this save fails.
                                save(protocol.checkpoint())
                                records.forEach { emit(ProviderEvent.TextDelta(it.wire + "\n")) }
                                protocol.checkpoint().failure?.let { throw CombinedRecordFailure(it) }
                            }
                        }
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> throw CombinedRecordFailure("COMBINED_TOOL_REQUEST_REJECTED")
                        is ProviderEvent.Usage -> {
                            finishReason = event.finishReason ?: finishReason
                            emit(event)
                        }
                        is ProviderEvent.Failed -> failure = event
                        ProviderEvent.Completed -> complete = true
                        else -> emit(event)
                    }
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    try {
                        save(protocol.checkpoint())
                    } catch (saveError: Exception) {
                        error.addSuppressed(saveError)
                    }
                }
                if (error is CancellationException) throw error
                // Do not emit from catch: the exception may have originated in a downstream collector.
                throw error
            }
            val after = protocol.checkpoint()
            save(after)
            if (failure == null && complete && after.ended && after.pending.isBlank()) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            val outputLimit = finishReason in setOf("length", "max_tokens", "MAX_TOKENS", "output_limit") || failure?.let { dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true
            val recoverable = outputLimit || (failure == null && complete && !after.ended)
            noProgress = if (after.lastSequence == before.lastSequence) noProgress + 1 else 0
            if (!recoverable || continuations >= maxContinuations || noProgress >= 2 || after.failure != null) {
                emit(failure ?: ProviderEvent.Failed("COMBINED_PARTIAL: Structured delivery paused at record ${after.lastSequence + 1}. Committed passages and the unfinished record are saved."))
                return@flow
            }
            validateAccess()
            val context = protocol.continuationContext()
            save(protocol.checkpoint())
            continuations++
            emit(ProviderEvent.Notice("Continuing the unfinished answer from saved record ${after.lastSequence + 1}."))
            active = continuation(protocol.checkpoint(), context)
        }
    }
}

internal class CombinedRecordFailure(reason: String) : IllegalStateException(reason)
