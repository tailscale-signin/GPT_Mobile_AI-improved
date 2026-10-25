package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.resetActiveRevision
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.rag.RecalledFactRef
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.chat.updateAssistantSlot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Target frame budgets for streaming token batching:
// - 120Hz display refresh budget is ~8.3ms. 8ms delivers frame-synced token updates without micro-stutter.
// - Standard displays (60Hz) or standard memory tiers target ~33ms (~30 FPS).
// - Low-memory or background power-saving tiers target 250ms.
const val HIGH_REFRESH_FRAME_INTERVAL_MILLIS = 8L
const val STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS = 33L
const val LOW_POWER_STREAM_PUBLISH_INTERVAL_MILLIS = 250L

sealed interface ApiStateFlowOutcome {
    data object Completed : ApiStateFlowOutcome
    data class Failed(val message: String, val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null) : ApiStateFlowOutcome
    data object Incomplete : ApiStateFlowOutcome
}

suspend fun Flow<ApiState>.handleStates(
    messageFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
    turnIndex: Int,
    platformIdx: Int,
    onLoadingComplete: () -> Unit,
    onNotice: (String) -> Unit = {},
    nanoTimeProvider: () -> Long = System::nanoTime,
    currentTimeProvider: () -> Long = { System.currentTimeMillis() / 1000 },
    revisionToAppendOnSuccess: AssistantRevision? = null,
    publishIntervalMillis: Long = STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS
): ApiStateFlowOutcome {
    try {
        val outcome = collectApiStateUpdates(
            onUpdate = { content, thoughts, timeline ->
                messageFlow.setBufferedText(turnIndex, platformIdx, content, thoughts, timeline)
            },
            onNotice = { message, _ -> onNotice(message) },
            nanoTimeProvider = nanoTimeProvider,
            publishIntervalMillis = publishIntervalMillis
        )
        when (outcome) {
            is ApiStateFlowOutcome.Failed -> messageFlow.setErrorMessage(
                turnIndex = turnIndex,
                platformIdx = platformIdx,
                error = outcome.message,
                currentTimeProvider = currentTimeProvider,
                revisionToAppend = revisionToAppendOnSuccess
            )

            ApiStateFlowOutcome.Completed -> messageFlow.setTimestamp(
                turnIndex = turnIndex,
                platformIdx = platformIdx,
                currentTimeProvider = currentTimeProvider,
                revisionToAppend = revisionToAppendOnSuccess
            )

            ApiStateFlowOutcome.Incomplete -> Unit
        }
        return outcome
    } finally {
        onLoadingComplete()
    }
}

internal suspend fun Flow<ApiState>.collectApiStateUpdates(
    onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit,
    onNotice: (String, Boolean) -> Unit = { _, _ -> },
    onPhaseChanged: ((LocalInferencePhase) -> Unit)? = null,
    onNativeProgress: ((dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress) -> Unit)? = null,
    onGatewayProgress: ((GatewayProgress) -> Unit)? = null,
    onResearchReplacement: suspend (String, String) -> Boolean = { _, _ -> false },
    onCombinedReplacement: suspend (dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate) -> Boolean = { false },
    nanoTimeProvider: () -> Long = System::nanoTime,
    publishIntervalMillis: Long = STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS
): ApiStateFlowOutcome {
    val buffer = StreamingMessageBuffer(nanoTimeProvider, publishIntervalMillis)
    var isCompletedSuccessfully = false
    var terminalError: String? = null
    var providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null

    try {
        withPublicationTicks(publishIntervalMillis).takeWhile { pulse ->
            pulse.error?.let { throw it }
            when (val chunk = pulse.state) {
                null -> buffer.publishIfDue(onUpdate)
                is ApiState.ResearchReplacement -> {
                    var accepted = false
                    try {
                        buffer.flush(onUpdate)
                        accepted = onResearchReplacement(chunk.previousDigest, chunk.text)
                        if (accepted) {
                            buffer.replaceResearch(chunk.text)
                            buffer.publishNow(onUpdate)
                        }
                    } finally {
                        chunk.acknowledgement.complete(accepted)
                    }
                }
                is ApiState.CombinedReplacement -> {
                    var accepted = false
                    try {
                        buffer.flush(onUpdate)
                        accepted = onCombinedReplacement(chunk.candidate)
                        if (accepted) {
                            buffer.replaceCombined(chunk.candidate)
                            buffer.publishNow(onUpdate)
                        }
                    } finally {
                        chunk.acknowledgement.complete(accepted)
                    }
                }
                is ApiState.CombinedQuality -> {
                    buffer.updateCombinedQuality(chunk.report)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.TokenUsage -> {
                    buffer.updateGeneration(chunk)
                }

                is ApiState.AssistStatus -> {
                    buffer.updateAssist(chunk.status)
                    buffer.publishNow(onUpdate)
                }

                is ApiState.ProgressCheckpoint -> {
                    buffer.appendProgress(chunk.text, chunk.modelAuthored)
                    if (chunk.modelAuthored) buffer.publishIfDue(onUpdate) else buffer.publishNow(onUpdate)
                }

                is ApiState.ActivitySummary -> {
                    buffer.updateActivitySummary(chunk)
                    buffer.publishNow(onUpdate)
                }

                is ApiState.DelegationText -> {
                    buffer.appendDelegation(chunk)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.Thinking -> {
                    buffer.appendThought(chunk.thinkingChunk)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.Success -> {
                    buffer.appendContent(chunk.textChunk)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.ToolCall -> {
                    buffer.appendTool(chunk.toolSequence, chunk.metrics, chunk.delegated, chunk.personalData)
                    buffer.publishNow(onUpdate)
                }

                is ApiState.MemoryRecalled -> {
                    buffer.appendRecall(chunk.facts)
                    buffer.publishNow(onUpdate)
                }

                is ApiState.Notice -> {
                    if (chunk.persistent || chunk.researchDelivery != null || chunk.personalData.isNotEmpty()) {
                        buffer.appendNotice(chunk.message, chunk.researchDelivery, chunk.personalData)
                    }
                    if (chunk.message.isNotBlank()) onNotice(chunk.message, chunk.persistent)
                    if (chunk.persistent || chunk.researchDelivery != null || chunk.personalData.isNotEmpty()) {
                        buffer.publishNow(onUpdate)
                    }
                }

                is ApiState.PhaseChanged -> {
                    onPhaseChanged?.invoke(chunk.phase)
                    chunk.nativeProgress?.let { onNativeProgress?.invoke(it) }
                }

                is ApiState.GatewayProgressChanged -> {
                    onGatewayProgress?.invoke(chunk.progress)
                }

                ApiState.Done -> {
                    isCompletedSuccessfully = true
                }

                is ApiState.Error -> {
                    providerLimit = chunk.providerLimit
                    chunk.progressDeadline?.let(buffer::appendProgressDeadline)
                    chunk.recoveryBlock?.let(buffer::appendRecoveryBlock)
                    chunk.combinedFailure?.let(buffer::appendCombinedFailure)
                    buffer.appendProfileFailure(chunk.message, chunk.progressDeadline != null || chunk.recoveryBlock != null || chunk.combinedFailure != null)
                    terminalError = chunk.message.ifBlank { "The provider could not complete this response." }
                }

                else -> {}
            }
            // A terminal event is final even when a provider leaves its socket or
            // flow open. Stop upstream now; finally still publishes buffered text.
            !isCompletedSuccessfully && terminalError == null
        }.collect {}
    } finally {
        buffer.finishAssist(isCompletedSuccessfully, terminalError)
        buffer.flush(onUpdate)
    }

    val completedError = terminalError
    return when {
        completedError != null -> ApiStateFlowOutcome.Failed(completedError, providerLimit)
        isCompletedSuccessfully && !buffer.hasResponse -> ApiStateFlowOutcome.Failed("The model finished without a visible answer. Please retry or choose another model.")
        isCompletedSuccessfully -> ApiStateFlowOutcome.Completed
        else -> ApiStateFlowOutcome.Incomplete
    }
}

/** One bounded consumer serializes timed flushes with provider events; no concurrent buffer writes. */
private data class PublicationPulse(val state: ApiState? = null, val error: Throwable? = null)

private fun Flow<ApiState>.withPublicationTicks(intervalMillis: Long): Flow<PublicationPulse> = channelFlow {
    val timer = launch {
        while (isActive) {
            delay(intervalMillis.coerceIn(8L, 250L))
            send(PublicationPulse())
        }
    }
    try {
        this@withPublicationTicks.takeWhile { state ->
            send(PublicationPulse(state = state))
            // Stop in the producer itself: buffered delivery must not let code after
            // a terminal emit run before the consumer observes that receipt.
            state != ApiState.Done && state !is ApiState.Error
        }.collect {}
    } catch (error: Throwable) {
        // Deliver failure after earlier accepted events; a producer exception must not
        // cancel the channel and discard ordinary text waiting in its bounded buffer.
        if (currentCoroutineContext().isActive) send(PublicationPulse(error = error)) else throw error
    } finally {
        timer.cancel()
    }
}

private class StreamingMessageBuffer(
    private val nanoTimeProvider: () -> Long,
    private val publishIntervalMillis: Long
) {
    private val thoughts = StringBuilder()
    private val content = StringBuilder()
    private val timeline = mutableListOf<AssistantTimelineItem>()
    private var pendingTimelineIndex = -1
    private val pendingTimelineText = StringBuilder()
    val hasResponse: Boolean get() = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(content.toString())
    private var lastPublishedAtNanos = 0L
    private var hasPublishedAnswer = false
    private var publishedThoughtLength = 0
    private var publishedContentLength = 0
    private var timelineVersion = 0
    private var publishedTimelineVersion = 0

    fun finishAssist(completed: Boolean, error: String?) {
        val hasCompletedTask = timeline.any { it.assistDiagnostic?.execution == dev.chungjungsoo.gptmobile.data.assist.AssistExecution.COMPLETED }
        for (index in timeline.indices) {
            val state = timeline[index].assistDiagnostic ?: continue
            if (state.terminal) continue
            val expected = state.taskId.startsWith("expected:")
            val execution = when {
                !completed -> dev.chungjungsoo.gptmobile.data.assist.AssistExecution.INTERRUPTED
                expected && hasCompletedTask -> dev.chungjungsoo.gptmobile.data.assist.AssistExecution.COMPLETED
                else -> dev.chungjungsoo.gptmobile.data.assist.AssistExecution.SKIPPED
            }
            val reason = when {
                !completed -> if (error == null) "PARENT_INTERRUPTED" else "PARENT_FAILED"
                expected && hasCompletedTask -> "SEE_TASK_OUTCOMES"
                else -> "NO_ELIGIBLE_TASK_DISPATCHED"
            }
            timeline[index] = timeline[index].copy(assistDiagnostic = state.copy(sequence = state.sequence + 1, execution = execution, reason = reason))
            timelineVersion++
        }
    }

    fun updateAssist(status: dev.chungjungsoo.gptmobile.data.assist.AssistTaskState) {
        flushTimelineText()
        val index = timeline.indexOfFirst { it.assistDiagnostic?.taskId == status.taskId }
        val prior = timeline.getOrNull(index)?.assistDiagnostic
        if (prior != null && !prior.accepts(status)) return
        val item = AssistantTimelineItem(type = AssistantTimelineItemType.NOTICE, assistDiagnostic = status)
        if (index < 0) timeline += item else timeline[index] = item
        timelineVersion++
    }

    fun updateActivitySummary(chunk: ApiState.ActivitySummary) {
        flushTimelineText()
        val item = AssistantTimelineItem(
            type = AssistantTimelineItemType.NOTICE,
            content = chunk.text,
            modelAuthored = chunk.modelAuthored,
            statusSummary = true
        )
        val existing = timeline.indexOfFirst { it.statusSummary }
        if (existing < 0) timeline.add(0, item) else timeline[existing] = item
        timelineVersion++
    }

    fun appendDelegation(chunk: ApiState.DelegationText) {
        flushTimelineText()
        val existing = timeline.indexOfFirst { it.delegationInvocationId == chunk.invocationId }
        if (existing < 0) {
            timeline += AssistantTimelineItem(
                type = AssistantTimelineItemType.NOTICE,
                content = chunk.text.take(64 * 1024),
                delegationInvocationId = chunk.invocationId,
                delegationProfile = chunk.profileName,
                delegationRemote = chunk.remote
            )
        } else {
            val old = timeline[existing]
            timeline[existing] = old.copy(content = chunk.text.take(64 * 1024))
        }
        timelineVersion++
    }

    fun appendThought(chunk: String) {
        if (chunk.isNotEmpty()) {
            thoughts.append(chunk)
            appendTimelineText(AssistantTimelineItemType.THINKING, chunk)
        }
    }

    fun appendContent(chunk: String) {
        if (chunk.isNotEmpty()) {
            content.append(chunk)
            appendTimelineText(AssistantTimelineItemType.TEXT, chunk)
        }
    }

    fun appendTool(toolSequence: Int, metrics: ToolPayloadMetrics?, delegated: Boolean, personalData: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> = emptyList()) {
        flushTimelineText()
        val existing = timeline.indexOfFirst { it.type == AssistantTimelineItemType.TOOL && it.toolSequence == toolSequence }
        if (existing >= 0) {
            if (metrics == null && personalData.isEmpty()) return
            timeline[existing] = timeline[existing].copy(toolMetrics = metrics ?: timeline[existing].toolMetrics, delegatedTool = delegated || timeline[existing].delegatedTool, personalData = (timeline[existing].personalData + personalData).distinct())
        } else {
            timeline += AssistantTimelineItem(
                type = AssistantTimelineItemType.TOOL,
                toolSequence = toolSequence,
                toolMetrics = metrics,
                delegatedTool = delegated,
                personalData = personalData
            )
        }
        timelineVersion += 1
    }

    fun appendProgress(text: String, modelAuthored: Boolean) {
        flushTimelineText()
        val last = timeline.lastOrNull()
        if (modelAuthored && last?.progressCheckpoint == true && !last.statusSummary) {
            timeline[timeline.lastIndex] = last.copy(content = (if (last.modelAuthored) last.content else "") + text, modelAuthored = true)
        } else {
            timeline += AssistantTimelineItem(
                AssistantTimelineItemType.NOTICE,
                content = text,
                progressCheckpoint = true,
                modelAuthored = modelAuthored
            )
        }
        timelineVersion += 1
    }

    fun appendRecall(facts: List<RecalledFactRef>) {
        flushTimelineText()
        if (facts.isEmpty()) return
        timeline += AssistantTimelineItem(type = AssistantTimelineItemType.NOTICE, recalledFacts = facts)
        timelineVersion += 1
    }

    fun updateGeneration(usage: ApiState.TokenUsage) {
        flushTimelineText()
        val index = timeline.indexOfLast { it.generation != null }
        val prior = timeline.getOrNull(index)?.generation
        val receipt = dev.chungjungsoo.gptmobile.data.database.entity.GenerationReceipt(usage.finishReason ?: prior?.finishReason, usage.outputTokens ?: prior?.outputTokens, usage.outputAllowance ?: prior?.outputAllowance)
        val item = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = receipt)
        if (index < 0) timeline += item else timeline[index] = item
        timelineVersion += 1
    }

    fun appendProfileFailure(message: String, attachToTypedFailure: Boolean) {
        flushTimelineText()
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(message)
        val index = if (attachToTypedFailure) timeline.indexOfLast { it.progressDeadline != null || it.recoveryBlock != null || it.combinedFailure != null } else -1
        if (index >= 0) {
            timeline[index] = timeline[index].copy(profileFailure = failure)
        } else {
            timeline.add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = failure))
        }
        timelineVersion++
    }

    fun appendCombinedFailure(failure: dev.chungjungsoo.gptmobile.data.agent.CombinedRecoveryFailure) {
        flushTimelineText()
        timeline.add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedFailure = failure))
        timelineVersion += 1
    }

    fun appendRecoveryBlock(block: dev.chungjungsoo.gptmobile.data.agent.RecoveryBlock) {
        flushTimelineText()
        if (timeline.any { it.recoveryBlock?.generationId == block.generationId && it.recoveryBlock?.fingerprint == block.fingerprint }) return
        timeline.add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = block.message, recoveryBlock = block))
        timelineVersion += 1
    }

    fun appendProgressDeadline(deadline: dev.chungjungsoo.gptmobile.data.agent.ProgressDeadlineOutcome) {
        flushTimelineText()
        timeline.add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = "${deadline.deadline}: stage ${deadline.stage}; last progress ${deadline.lastProgressAgeMillis} ms ago; cleanup ${deadline.cleanup}.", progressDeadline = deadline))
        timelineVersion += 1
    }

    fun updateCombinedQuality(report: dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport) {
        flushTimelineText()
        val index = timeline.indexOfFirst { it.combinedQuality != null }
        val item = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report)
        if (index < 0) timeline += item else timeline[index] = item
        timelineVersion++
    }

    fun replaceResearch(text: String) {
        flushTimelineText()
        content.setLength(0)
        pendingTimelineIndex = -1
        pendingTimelineText.setLength(0)
        timeline.removeAll { it.type == AssistantTimelineItemType.TEXT }
        appendContent(text)
        timelineVersion++
    }

    fun replaceCombined(candidate: dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate) {
        flushTimelineText()
        content.setLength(0)
        pendingTimelineIndex = -1
        pendingTimelineText.setLength(0)
        timeline.removeAll { it.type == AssistantTimelineItemType.TEXT || it.combinedQuality != null }
        appendContent(candidate.text)
        updateCombinedQuality(candidate.report)
        // Equal-length replacements must publish too.
        timelineVersion++
    }

    fun appendNotice(message: String, receipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt? = null, personalData: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> = emptyList()) {
        flushTimelineText()
        if (message.isBlank() && receipt == null && personalData.isEmpty()) return
        timeline += AssistantTimelineItem(type = AssistantTimelineItemType.NOTICE, content = message, researchDeliveryReceipt = receipt, personalData = personalData)
        timelineVersion += 1
    }

    suspend fun publishIfDue(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit
    ) {
        if (!hasPendingChanges()) return

        val now = nanoTimeProvider()
        if ((!hasPublishedAnswer && hasResponse) ||
            lastPublishedAtNanos == 0L ||
            now - lastPublishedAtNanos >= adaptiveStreamIntervalMillis(publishIntervalMillis, content.length + thoughts.length) * 1_000_000
        ) {
            publish(onUpdate, now)
        }
    }

    suspend fun flush(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit
    ) {
        if (!hasPendingChanges()) return
        publish(onUpdate, nanoTimeProvider())
    }

    suspend fun publishNow(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit
    ) {
        if (!hasPendingChanges()) return
        publish(onUpdate, nanoTimeProvider())
    }

    private suspend fun publish(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit,
        publishedAtNanos: Long
    ) {
        flushTimelineText()
        onUpdate(content.toString(), thoughts.toString(), timeline.toList())
        hasPublishedAnswer = hasPublishedAnswer || hasResponse
        publishedContentLength = content.length
        publishedThoughtLength = thoughts.length
        publishedTimelineVersion = timelineVersion
        lastPublishedAtNanos = publishedAtNanos
    }

    private fun appendTimelineText(type: AssistantTimelineItemType, chunk: String) {
        val last = timeline.lastOrNull()
        if (last?.type != type || last.toolSequence != null) {
            flushTimelineText()
            timeline += AssistantTimelineItem(type = type, content = "")
        }
        if (pendingTimelineIndex != timeline.lastIndex) {
            flushTimelineText()
            pendingTimelineIndex = timeline.lastIndex
            pendingTimelineText.append(timeline.last().content)
        }
        pendingTimelineText.append(chunk)
        timelineVersion += 1
    }

    private fun flushTimelineText() {
        if (pendingTimelineIndex >= 0) {
            val previous = timeline[pendingTimelineIndex]
            timeline[pendingTimelineIndex] = previous.copy(content = pendingTimelineText.toString())
            pendingTimelineIndex = -1
            pendingTimelineText.setLength(0)
        }
    }

    private fun hasPendingChanges(): Boolean = content.length != publishedContentLength ||
        thoughts.length != publishedThoughtLength ||
        timelineVersion != publishedTimelineVersion
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setBufferedText(
    turnIndex: Int,
    platformIdx: Int,
    content: String,
    thoughts: String,
    timeline: List<AssistantTimelineItem>
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            if (currentMessage.content == content &&
                currentMessage.thoughts == thoughts &&
                currentMessage.timeline == timeline
            ) {
                currentMessage
            } else {
                currentMessage.copy(
                    content = content,
                    thoughts = thoughts,
                    timeline = timeline
                )
            }
        }
    }
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setErrorMessage(
    turnIndex: Int,
    platformIdx: Int,
    error: String,
    currentTimeProvider: () -> Long,
    revisionToAppend: AssistantRevision?
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            val updatedContent = buildAssistantErrorContent(currentMessage.content, error)
            val appendedError = assistantErrorAppendedText(currentMessage.content, updatedContent)
            currentMessage.copy(
                content = updatedContent,
                timeline = currentMessage.timeline.appendErrorText(appendedError),
                createdAt = currentTimeProvider(),
                revisions = revisionToAppend
                    ?.let { listOf(it) + currentMessage.revisions }
                    ?: currentMessage.revisions
            )
        }
    }
}

private fun List<AssistantTimelineItem>.appendErrorText(errorText: String): List<AssistantTimelineItem> {
    if (isEmpty()) return this
    val last = last()
    return if (last.type == AssistantTimelineItemType.TEXT && last.toolSequence == null) {
        dropLast(1) + last.copy(content = last.content + errorText)
    } else {
        this + AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = errorText)
    }
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setTimestamp(
    turnIndex: Int,
    platformIdx: Int,
    currentTimeProvider: () -> Long,
    revisionToAppend: AssistantRevision?
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            currentMessage.copy(
                createdAt = currentTimeProvider(),
                revisions = revisionToAppend
                    ?.let { listOf(it) + currentMessage.revisions }
                    ?: currentMessage.revisions
            ).resetActiveRevision()
        }
    }
}
