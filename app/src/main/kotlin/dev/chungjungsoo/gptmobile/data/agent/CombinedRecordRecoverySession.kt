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
        var continuations = protocol.checkpoint().recoveryAttempts
        var noProgress = 0
        var pendingCharacters = 0
        var savedAt = System.nanoTime()
        if (restored) {
            val checkpoint = protocol.checkpoint()
            checkpoint.records.forEach { emit(ProviderEvent.TextDelta(it.wire + "\n")) }
            if (checkpoint.ended) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            if (continuations >= maxContinuations || checkpoint.failure != null && !protocol.canRecoverMalformed) {
                emit(ProviderEvent.Failed("${checkpoint.failure ?: "COMBINED_RECORD_RECOVERY_LIMIT"}: Saved records remain available; this stream requires an explicit new revision."))
                return@flow
            }
            validateAccess()
            val context = protocol.continuationContext(repairMalformed = protocol.canRecoverMalformed)
            save(protocol.checkpoint())
            continuations++
            active = continuation(protocol.checkpoint(), context)
        }
        while (true) {
            validateAccess()
            val before = protocol.checkpoint()
            var complete = false
            var failure: ProviderEvent.Failed? = null
            var finishReason: String? = null
            var rejectedRecord: CombinedRecordFailure? = null
            try {
                active.streamRound(emptyList(), emptyList()).withSummedRequestUsage().collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (protocol.failureReason == null) {
                                val records = protocol.accept(event.text)
                                // The transaction includes raw fragments, sequence, text and attribution records.
                                // A stale owner cannot publish after this save fails.
                                pendingCharacters += event.text.length
                                val elapsed = System.nanoTime() - savedAt
                                if (records.isNotEmpty() || pendingCharacters >= 4096 || elapsed >= 250_000_000L || protocol.failureReason != null) {
                                    save(protocol.checkpoint())
                                    pendingCharacters = 0
                                    savedAt = System.nanoTime()
                                }
                                records.forEach { emit(ProviderEvent.TextDelta(it.wire + "\n")) }
                                protocol.failureReason?.let { reason -> throw CombinedRecordFailure(reason).also { rejectedRecord = it } }
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
                // Only this exact parser sentinel can enter bounded reconstruction. Collector,
                // persistence and authorization failures must propagate without further emission.
                if (error !== rejectedRecord) throw error
                failure = ProviderEvent.Failed(error.message.orEmpty())
            }
            val after = protocol.checkpoint()
            save(after)
            if (failure == null && complete && after.ended && after.pending.isBlank()) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            val outputLimit = finishReason in setOf("length", "max_tokens", "MAX_TOKENS", "output_limit") || failure?.let { dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true
            val recoverable = protocol.canRecoverMalformed || outputLimit || (failure == null && complete && !after.ended)
            noProgress = if (after.lastSequence == before.lastSequence) noProgress + 1 else 0
            if (!recoverable || continuations >= maxContinuations || noProgress >= 2 || after.failure != null && !protocol.canRecoverMalformed) {
                emit(failure ?: ProviderEvent.Failed("COMBINED_PARTIAL: Structured delivery paused at record ${after.lastSequence + 1}. Committed passages and the unfinished record are saved."))
                return@flow
            }
            validateAccess()
            val context = protocol.continuationContext(repairMalformed = protocol.canRecoverMalformed)
            save(protocol.checkpoint())
            continuations++
            emit(ProviderEvent.Notice("Continuing the unfinished answer from saved record ${after.lastSequence + 1}."))
            active = continuation(protocol.checkpoint(), context)
        }
    }
}

internal class CombinedRecordFailure(reason: String) : IllegalStateException(reason)
