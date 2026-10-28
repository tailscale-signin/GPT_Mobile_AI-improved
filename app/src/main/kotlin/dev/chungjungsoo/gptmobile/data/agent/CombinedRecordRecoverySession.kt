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
    private val acceptedAppend: Boolean = false,
    private val validateAccess: suspend () -> Unit,
    private val save: suspend (CombinedRecordCheckpoint) -> Unit,
    private val observeAttempt: (Boolean, Int) -> Unit = { _, _ -> },
    private val observeRoute: (String) -> Unit = {},
    private val continuation: suspend (CombinedRecordCheckpoint, String) -> AgentProviderSession
) : AgentProviderSession {
    override val handlesToolsInternally = false

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        var continuations = protocol.checkpoint().recoveryAttempts
        var noProgress = if (acceptedAppend) protocol.checkpoint().noProgressAttempts else 0
        var pendingCharacters = 0
        var savedAt = System.nanoTime()
        if (restored) {
            val checkpoint = protocol.checkpoint()
            checkpoint.records.forEach { emit(ProviderEvent.TextDelta(it.wire + "\n")) }
            if (checkpoint.ended) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            if (acceptedAppend && checkpoint.lastAttemptOutcome == null && checkpoint.records.isEmpty() && checkpoint.pending.isEmpty()) {
                // The initial cursor was saved before any physical attempt was started.
            } else {
                if (acceptedAppend && (!checkpoint.appendReady || checkpoint.lastAttemptOutcome !in setOf("COMPLETED", "OUTPUT_LIMIT", "KNOWN_PROVIDER_REJECTION"))) {
                    emit(ProviderEvent.Failed("COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN: The previous request's completion is unknown. Saved passages remain available; review before retrying.", errorCode = "COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN"))
                    return@flow
                }
                if (acceptedAppend && checkpoint.retryAtMillis?.let { System.currentTimeMillis() < it } == true) {
                    emit(ProviderEvent.Failed("COMBINED_APPEND_PROVIDER_WAIT: The saved provider reset deadline has not arrived.", errorCode = "COMBINED_APPEND_PROVIDER_WAIT"))
                    return@flow
                }
                if (continuations >= maxContinuations || noProgress >= 2 || checkpoint.failure != null && !protocol.canRecoverMalformed && !protocol.canContinueUnderlength) {
                    emit(ProviderEvent.Failed("${checkpoint.failure ?: "COMBINED_RECORD_RECOVERY_LIMIT"}: Saved records remain available; this stream requires an explicit new revision."))
                    return@flow
                }
                validateAccess()
                val context = protocol.continuationContext(repairMalformed = protocol.canRecoverMalformed, continueUnderlength = protocol.canContinueUnderlength)
                save(protocol.checkpoint())
                continuations++
                active = continuation(protocol.checkpoint(), context)
            }
        }
        while (true) {
            validateAccess()
            val before = protocol.checkpoint()
            if (acceptedAppend) {
                protocol.markAttemptStarted()
                save(protocol.checkpoint())
            }
            var complete = false
            var failure: ProviderEvent.Failed? = null
            var finishReason: String? = null
            var rejectedRecord: CombinedRecordFailure? = null
            var reasoningObserved = false
            var textObserved = false
            try {
                active.streamRound(emptyList(), emptyList()).withSummedRequestUsage().collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            textObserved = textObserved || event.text.isNotBlank()
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
                                protocol.failureReason?.let { reason ->
                                    rejectedRecord = CombinedRecordFailure(reason)
                                    if (!acceptedAppend) throw requireNotNull(rejectedRecord)
                                }
                            } else if (acceptedAppend) {
                                protocol.retainRejected(event.text)
                            }
                        }
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> throw CombinedRecordFailure("COMBINED_TOOL_REQUEST_REJECTED")
                        is ProviderEvent.ThinkingDelta -> {
                            reasoningObserved = reasoningObserved || event.text.isNotBlank()
                            emit(event)
                        }
                        is ProviderEvent.Usage -> {
                            event.writerObservationKey?.let(observeRoute)
                            reasoningObserved = reasoningObserved || (event.reasoningTokens ?: 0) > 0
                            finishReason = event.finishReason ?: finishReason
                            emit(event)
                        }
                        is ProviderEvent.Failed -> failure = event
                        ProviderEvent.Completed -> complete = true
                        else -> emit(event)
                    }
                }
            } catch (error: Exception) {
                if (acceptedAppend) protocol.markAttemptFinished("UNKNOWN")
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
                failure = ProviderEvent.Failed("OUTPUT_PROTOCOL_INVALID: ${error.message.orEmpty()}", errorCode = "OUTPUT_PROTOCOL_INVALID")
            }
            if (complete || failure?.transportComplete == true || finishReason in setOf("length", "max_tokens", "MAX_TOKENS", "output_limit")) protocol.finishPacket()
            if (rejectedRecord != null && failure == null) {
                failure = if (protocol.canContinueUnderlength) {
                    ProviderEvent.Failed("COMBINED_LENGTH_REQUIREMENT_UNMET: The saved answer ended before its requested length was met.", errorCode = "COMBINED_LENGTH_REQUIREMENT_UNMET", transportComplete = complete)
                } else {
                    ProviderEvent.Failed("OUTPUT_PROTOCOL_INVALID: ${rejectedRecord?.message.orEmpty()}", errorCode = "OUTPUT_PROTOCOL_INVALID", transportComplete = complete)
                }
            }
            fun passageText(record: CombinedCommittedRecord): String? {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(record.wire) as? kotlinx.serialization.json.JsonObject
                if ((parsed?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content != "passage") return null
                return (parsed["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.replace(Regex("\\s+"), " ")
            }
            val previousPassages = before.records.mapNotNull(::passageText).toSet()
            val newPassages = protocol.checkpoint().records.drop(before.records.size).mapNotNull(::passageText)
            val passagesAdded = if (acceptedAppend) newPassages.distinct().count { it !in previousPassages } else newPassages.size
            observeAttempt(reasoningObserved, passagesAdded)
            val outputLimit = finishReason in setOf("length", "max_tokens", "MAX_TOKENS", "output_limit") || failure?.let { dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true
            if (acceptedAppend && (textObserved || reasoningObserved) && failure?.providerLimit?.knownNotAccepted == true) {
                failure = failure?.copy(providerLimit = failure?.providerLimit?.copy(knownNotAccepted = false))
            }
            val knownRejection = failure?.providerLimit?.takeIf { it.knownNotAccepted && !textObserved && !reasoningObserved }
            if (acceptedAppend) {
                val knownBoundary = complete || failure?.transportComplete == true || finishReason in setOf("length", "max_tokens", "MAX_TOKENS", "output_limit")
                protocol.markAttemptFinished(
                    when {
                        knownRejection != null -> "KNOWN_PROVIDER_REJECTION"
                        outputLimit && knownBoundary -> "OUTPUT_LIMIT"
                        complete && (failure == null || rejectedRecord != null) -> "COMPLETED"
                        else -> "UNKNOWN"
                    },
                    knownRejection?.retryAt
                )
                if (knownRejection == null) protocol.recordProgress(passagesAdded > 0)
            }
            val after = protocol.checkpoint()
            save(after)
            if (failure == null && complete && after.ended && after.pending.isBlank()) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            if (acceptedAppend && knownRejection != null) {
                // The durable wait owner schedules this exact cursor; never spin or sleep here.
                emit(requireNotNull(failure))
                return@flow
            }
            val recoverable = (protocol.canRecoverMalformed || protocol.canContinueUnderlength || outputLimit || (failure == null && complete && !after.ended)) && (!acceptedAppend || after.appendReady)
            noProgress = if (passagesAdded == 0) noProgress + 1 else 0
            if (!recoverable || continuations >= maxContinuations || noProgress >= 2 || after.failure != null && !protocol.canRecoverMalformed && !protocol.canContinueUnderlength) {
                emit(
                    if (reasoningObserved && passagesAdded == 0 && (noProgress >= 2 || continuations >= maxContinuations)) {
                        ProviderEvent.Failed("COMBINED_WRITER_STALLED: Reasoning consumed the allowance without completed answer passages. Retained contributions remain available; an explicit user retry is required.", errorCode = "COMBINED_WRITER_STALLED")
                    } else if (failure == null && passagesAdded == 0 && after.records.isEmpty() && after.pending.isBlank()) {
                        ProviderEvent.Failed("COMBINED_EMPTY_OUTPUT: The writer returned no answer records.", errorCode = "COMBINED_EMPTY_OUTPUT")
                    } else if (failure == null && complete && !outputLimit && (!after.ended || after.pending.isNotBlank())) {
                        ProviderEvent.Failed("OUTPUT_PROTOCOL_INVALID: The writer ended without a valid final record. Committed passages and unfinished bytes are saved.", errorCode = "OUTPUT_PROTOCOL_INVALID")
                    } else {
                        failure ?: ProviderEvent.Failed("COMBINED_PARTIAL: Structured delivery paused at record ${after.lastSequence + 1}. Committed passages and the unfinished record are saved.")
                    }
                )
                return@flow
            }
            validateAccess()
            val context = (if (!acceptedAppend && reasoningObserved && passagesAdded == 0) "WRITER_RECOVERY_DIRECT: The previous attempt produced reasoning without an answer passage. Do not repeat analysis or inventory accounting. Write the next complete, short answer paragraph immediately, following the current task and outline. Emit decisions only for material disagreements. " else "") + protocol.continuationContext(repairMalformed = protocol.canRecoverMalformed, continueUnderlength = protocol.canContinueUnderlength)
            save(protocol.checkpoint())
            continuations++
            emit(ProviderEvent.Notice("Continuing the unfinished answer from saved record ${after.lastSequence + 1}."))
            active = continuation(protocol.checkpoint(), context)
        }
    }
}

internal class CombinedRecordFailure(reason: String) : IllegalStateException(reason)
