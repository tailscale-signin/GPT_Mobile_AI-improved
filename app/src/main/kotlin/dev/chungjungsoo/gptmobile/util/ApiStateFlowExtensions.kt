package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.resetActiveRevision
import dev.chungjungsoo.gptmobile.data.diagnostics.FirstOutputPublication
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineageAccumulator
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputPublicationTracker
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import dev.chungjungsoo.gptmobile.data.diagnostics.captureIssueReport
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

// Start interactive presentation at a bounded 33ms cadence on every refresh rate.
// A faster display is not evidence that rebuilding text at 120Hz improves latency.
// Low-memory or background power-saving tiers may explicitly select 250ms.
const val HIGH_REFRESH_FRAME_INTERVAL_MILLIS = 33L
const val STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS = 33L
const val LOW_POWER_STREAM_PUBLISH_INTERVAL_MILLIS = 250L

sealed interface ApiStateFlowOutcome {
    data object Completed : ApiStateFlowOutcome
    data class Failed(val message: String, val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null, val errorCode: String? = null, val taskOutcome: String? = null) : ApiStateFlowOutcome
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
    onCombinedAnswer: suspend (String, String, List<AssistantTimelineItem>) -> Boolean = { _, _, _ -> false },
    nanoTimeProvider: () -> Long = System::nanoTime,
    publishIntervalMillis: Long = STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS,
    restoredTimeline: List<AssistantTimelineItem> = emptyList(),
    onOutputPublication: suspend (content: String, lineage: OutputLineage, first: List<FirstOutputPublication>) -> Unit = { _, _, _ -> }
): ApiStateFlowOutcome {
    val buffer = StreamingMessageBuffer(nanoTimeProvider, publishIntervalMillis, restoredTimeline, onOutputPublication)
    var isCompletedSuccessfully = false
    var pendingCombinedAnswer: ApiState.CombinedAnswer? = null
    suspend fun publishCombined(chunk: ApiState.CombinedAnswer) {
        dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerPublication.validate(chunk.text, chunk.report, chunk.formatVersion)
        val timeline = buffer.combinedAnswerTimeline(chunk.text, chunk.report)
        check(onCombinedAnswer(chunk.text, buffer.thoughtText(), timeline)) { "COMBINED_FINAL_PUBLICATION_REJECTED" }
        buffer.replaceCombinedAnswer(chunk.text, chunk.report)
        buffer.publishNow(onUpdate)
    }
    var terminalError: String? = null
    var providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null
    var errorCode: String? = null
    var taskOutcome: String? = null

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
                is ApiState.CombinedAnswer -> {
                    dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerPublication.validate(chunk.text, chunk.report, chunk.formatVersion)
                    // A checked final is staged until the producer confirms its terminal
                    // outcome. Committed draft passages can still appear while writing.
                    if (chunk.report.complete) pendingCombinedAnswer = chunk else publishCombined(chunk)
                }

                is ApiState.TokenUsage -> {
                    buffer.updateGeneration(chunk)
                }

                is ApiState.ResponseSourceCaptured -> {
                    buffer.updateResponseSource(chunk.source)
                    buffer.publishNow(onUpdate)
                }

                is ApiState.AssistStatus -> {
                    buffer.updateAssist(chunk.status)
                    buffer.publishNow(onUpdate)
                }

                is ApiState.ProgressCheckpoint -> {
                    buffer.observeProgress(chunk.text, chunk.invocationId)
                    buffer.appendProgress(chunk.text, chunk.modelAuthored)
                    if (chunk.modelAuthored) buffer.publishIfDue(onUpdate) else buffer.publishNow(onUpdate)
                }

                ApiState.GitHubRoundStarted -> {
                    buffer.beginGitHubRound()
                    buffer.publishNow(onUpdate)
                }

                is ApiState.ActivitySummary -> {
                    buffer.observeProgress(chunk.text, chunk.invocationId)
                    buffer.updateActivitySummary(chunk)
                    buffer.publishIfDue(onUpdate)
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
                    buffer.appendContent(chunk.textChunk, chunk.lineage)
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
                        buffer.observeProgress(chunk.message)
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
                    if (onGatewayProgress != null) {
                        buffer.observeProgress(chunk.progress.message.orEmpty())
                        buffer.publishOutputMilestones()
                    }
                }

                ApiState.Done -> {
                    pendingCombinedAnswer?.let { publishCombined(it) }
                    pendingCombinedAnswer = null
                    isCompletedSuccessfully = true
                }

                is ApiState.Error -> {
                    buffer.observeSystemError(chunk.message, chunk.invocationId)
                    pendingCombinedAnswer = null
                    providerLimit = chunk.providerLimit
                    errorCode = chunk.errorCode
                    taskOutcome = chunk.taskOutcome
                    chunk.progressDeadline?.let(buffer::appendProgressDeadline)
                    chunk.recoveryBlock?.let(buffer::appendRecoveryBlock)
                    chunk.combinedFailure?.let(buffer::appendCombinedFailure)
                    if (chunk.errorCode == "QUEUE_JOB_PENDING") {
                        buffer.appendNotice(chunk.message)
                    } else if (chunk.providerLimit?.mayWait != true) {
                        buffer.appendFailure(chunk.message, chunk.progressDeadline != null || chunk.recoveryBlock != null || chunk.combinedFailure != null, chunk.errorCode, chunk.failureDetails)
                        captureIssueReport {
                            IssueReportIncidents.chatFailure(chunk.errorCode, chunk.providerLimit != null, null, chunk.failureDetails.orEmpty(), chunk.invocationId)
                        }
                    }
                    terminalError = chunk.message.ifBlank { "The provider could not complete this response." }
                }

                else -> {}
            }
            // A terminal event is final even when a provider leaves its socket or
            // flow open. Stop upstream now; finally still publishes buffered text.
            !isCompletedSuccessfully && terminalError == null
        }.collect {}
    } finally {
        if (isCompletedSuccessfully && terminalError == null && buffer.hasResponse) buffer.finishGitHubProgress()
        buffer.finishAssist(isCompletedSuccessfully, terminalError)
        buffer.flush(onUpdate)
    }

    val completedError = terminalError
    return when {
        completedError != null -> ApiStateFlowOutcome.Failed(completedError, providerLimit, errorCode, taskOutcome)
        isCompletedSuccessfully && !buffer.hasResponse -> {
            captureIssueReport { IssueReportIncidents.failure(ReportFeature.CHAT, "NO_VISIBLE_ANSWER") }
            ApiStateFlowOutcome.Failed("The model finished without a visible answer. Please retry or choose another model.")
        }
        isCompletedSuccessfully -> ApiStateFlowOutcome.Completed
        else -> ApiStateFlowOutcome.Incomplete
    }
}

/** One bounded consumer serializes timed flushes with provider events; no concurrent buffer writes. */
private data class PublicationPulse(val state: ApiState? = null, val error: Throwable? = null)

private fun Flow<ApiState>.withPublicationTicks(intervalMillis: Long): Flow<PublicationPulse> = channelFlow {
    val timer = launch {
        while (isActive) {
            delay(intervalMillis.coerceIn(33L, 250L))
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
    private val publishIntervalMillis: Long,
    restoredTimeline: List<AssistantTimelineItem>,
    private val onOutputPublication: suspend (String, OutputLineage, List<FirstOutputPublication>) -> Unit
) {
    private val thoughts = StringBuilder()
    private val content = StringBuilder()
    private val contentLineage = OutputLineageAccumulator()
    private val githubPrefixLineage = OutputLineageAccumulator()
    private val outputPublications = OutputPublicationTracker()
    private val timeline = mutableListOf<AssistantTimelineItem>()
    private var githubProgress = false
    private var progressSequence = restoredTimeline.maxOfOrNull { it.progressSequence ?: 0L } ?: 0L
    private var githubRoundAwaitingAnswer = false
    private val githubAnswerPrefix = StringBuilder()
    private var pendingTimelineIndex = -1
    private val pendingTimelineText = StringBuilder()
    private var responseSource: dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource? = null
    private val restoredText = restoredTimeline.filter { it.type == AssistantTimelineItemType.TEXT && it.content.isNotEmpty() }
    private var restoredIndex = 0
    private var restoredOffset = 0
    private var contentSnapshot: String? = null
    private fun currentContent(): String = contentSnapshot ?: content.toString().also { contentSnapshot = it }
    val hasResponse: Boolean get() = !githubRoundAwaitingAnswer && dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(currentContent())
    private var lastPublishedAtNanos = 0L
    private var hasPublishedAnswer = false
    private var publishedThoughtLength = 0
    private var publishedContentLength = 0
    private var timelineVersion = 0
    private var publishedTimelineVersion = 0

    fun observeProgress(text: String, invocationId: String? = null) {
        if (text.isNotBlank()) outputPublications.observe(OutputLineage(OutputOrigin.PROGRESS_NOTICE, invocationId), text.length)
    }

    fun observeSystemError(text: String, invocationId: String?) {
        if (text.isNotBlank()) outputPublications.observe(OutputLineage(OutputOrigin.SYSTEM_ERROR, invocationId), text.length)
    }

    suspend fun publishOutputMilestones() {
        val first = outputPublications.drain(dev.chungjungsoo.gptmobile.data.diagnostics.OutputPublicationKind.FIRST_PROGRESS)
        if (first.isNotEmpty()) onOutputPublication("", OutputLineage(), first)
    }

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
        if (githubProgress) {
            if (timeline.none { it.statusSummary && it.content == item.content.take(1200) }) {
                timeline += item.copy(content = item.content.take(1200), progressCheckpoint = true, progressSequence = ++progressSequence)
            }
            chunk.preview?.takeIf(String::isNotBlank)?.let { preview ->
                appendProgress(preview, modelAuthored = false)
            }
            boundGitHubProgress()
        } else {
            val existing = timeline.indexOfFirst { it.statusSummary }
            if (existing < 0) timeline.add(0, item) else timeline[existing] = item
        }
        timelineVersion++
    }

    fun beginGitHubRound() {
        flushTimelineText()
        githubProgress = true
        // A continuation can stop before producing any replacement. Keep its
        // already-public findings visible; a new round is not a new answer.
        githubRoundAwaitingAnswer = true
        githubAnswerPrefix.setLength(0)
        githubPrefixLineage.reset()
    }

    private fun replaceGitHubRoundAnswer() {
        if (!githubRoundAwaitingAnswer) return
        flushTimelineText()
        for (index in timeline.indices) {
            val item = timeline[index]
            if (item.type == AssistantTimelineItemType.TEXT) {
                timeline[index] = item.copy(type = AssistantTimelineItemType.NOTICE, content = item.content.take(4000), progressCheckpoint = true, modelAuthored = true)
            }
        }
        contentSnapshot = null
        content.setLength(0)
        contentLineage.reset()
        githubRoundAwaitingAnswer = false
        boundGitHubProgress()
        timelineVersion++
    }

    fun finishGitHubProgress() {
        if (!githubProgress) return
        flushTimelineText()
        timeline.removeAll { it.progressCheckpoint || it.statusSummary }
        timelineVersion++
    }

    private fun boundGitHubProgress() {
        if (!githubProgress) return
        var excess = timeline.count { it.progressCheckpoint } - 48
        if (excess > 0) timeline.removeAll { it.progressCheckpoint && dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.liveKey(it.content) == null && excess-- > 0 }
        // Retained rows keep their identity when the bounded history shifts.
        for (index in timeline.indices) {
            val item = timeline[index]
            if (item.progressCheckpoint && item.progressId == null) {
                timeline[index] = item.copy(progressId = java.util.UUID.randomUUID().toString())
            }
        }
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

    fun appendContent(rawChunk: String, lineage: OutputLineage = OutputLineage()) {
        if (rawChunk.isNotEmpty() && githubRoundAwaitingAnswer) githubPrefixLineage.append(lineage)
        val effectiveLineage = if (githubRoundAwaitingAnswer) githubPrefixLineage.lineage else lineage
        val chunk = if (githubRoundAwaitingAnswer) {
            githubAnswerPrefix.append(rawChunk)
            val pending = githubAnswerPrefix.toString()
            val visible = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(pending)
            if (visible.isBlank() || "<think>".startsWith(visible.trimStart(), ignoreCase = true)) return
            pending
        } else {
            rawChunk
        }
        if (chunk.isNotEmpty()) {
            replaceGitHubRoundAnswer()
            githubAnswerPrefix.setLength(0)
            contentSnapshot = null
            content.append(chunk)
            fun observeOutput(text: String, owner: OutputLineage) {
                contentLineage.append(owner)
                if (dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(text).isNotBlank()) outputPublications.observe(owner, text.length)
            }
            var offset = 0
            while (offset < chunk.length && restoredIndex < restoredText.size) {
                val prior = restoredText[restoredIndex]
                val length = minOf(prior.content.length - restoredOffset, chunk.length - offset)
                if (!chunk.regionMatches(offset, prior.content, restoredOffset, length)) {
                    restoredIndex = restoredText.size
                    break
                }
                val restored = chunk.substring(offset, offset + length)
                val retainedOwner = OutputLineage(OutputOrigin.RETAINED_CONTENT)
                observeOutput(restored, retainedOwner)
                appendTimelineText(AssistantTimelineItemType.TEXT, restored, prior.responseSource, retainedOwner)
                offset += length
                restoredOffset += length
                if (restoredOffset == prior.content.length) {
                    restoredIndex++
                    restoredOffset = 0
                }
            }
            if (offset < chunk.length) {
                val novel = chunk.substring(offset)
                observeOutput(novel, effectiveLineage)
                appendTimelineText(AssistantTimelineItemType.TEXT, novel, responseSource, effectiveLineage)
            }
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

    private fun repeatsGitHubPreview(text: String): Boolean {
        if (!githubProgress) return false
        val previous = timeline.lastOrNull { it.progressCheckpoint && !it.statusSummary } ?: return false
        return dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.lineKey(previous.content) ==
            dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.lineKey(text.take(4000))
    }

    fun appendProgress(text: String, modelAuthored: Boolean) {
        flushTimelineText()
        val liveKey = if (githubProgress && !modelAuthored) dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.liveKey(text) else null
        if (liveKey != null) {
            val index = timeline.indexOfLast { it.progressCheckpoint && dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.liveKey(it.content) == liveKey }
            val current = text.take(4000)
            if (timeline.getOrNull(index)?.content == current) return
            val item = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = current, progressCheckpoint = true, progressId = liveKey, progressSequence = ++progressSequence)
            if (index < 0) timeline += item else timeline[index] = item
            boundGitHubProgress()
            timelineVersion++
            return
        }
        if (!modelAuthored && repeatsGitHubPreview(text)) return
        // A ticker summary can arrive between two chunks of the same public
        // update. It must not split the user's explanation into separate tails.
        val lastIndex = if (githubProgress && modelAuthored) timeline.indexOfLast { !it.statusSummary || it.modelAuthored } else timeline.lastIndex
        val last = timeline.getOrNull(lastIndex)
        if (modelAuthored && last?.progressCheckpoint == true && (!githubProgress || last.modelAuthored) && !last.statusSummary) {
            val combined = (if (last.modelAuthored) last.content else "") + text
            timeline[lastIndex] = last.copy(content = if (githubProgress) combined.takeLast(4000) else combined, modelAuthored = true, progressSequence = if (githubProgress) ++progressSequence else null)
        } else {
            timeline += AssistantTimelineItem(
                AssistantTimelineItemType.NOTICE,
                content = if (githubProgress) text.take(4000) else text,
                progressCheckpoint = true,
                modelAuthored = modelAuthored,
                progressSequence = if (githubProgress) ++progressSequence else null
            )
        }
        boundGitHubProgress()
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
        val receipt = dev.chungjungsoo.gptmobile.data.database.entity.GenerationReceipt(usage.finishReason ?: prior?.finishReason, usage.outputTokens ?: prior?.outputTokens, usage.outputAllowance ?: prior?.outputAllowance, responseSource ?: prior?.responseSource)
        val item = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = receipt)
        if (index < 0) timeline += item else timeline[index] = item
        timelineVersion += 1
    }

    fun updateResponseSource(source: dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource) {
        if (source == responseSource) return
        flushTimelineText()
        responseSource = source
        timeline += AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = dev.chungjungsoo.gptmobile.data.database.entity.GenerationReceipt(responseSource = source))
        timelineVersion++
    }

    fun appendFailure(message: String, attachToTypedFailure: Boolean, errorCode: String? = null, details: kotlinx.serialization.json.JsonObject? = null) {
        flushTimelineText()
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(message, errorCode, details)
        val taskFailure = dev.chungjungsoo.gptmobile.data.agent.TaskVerificationFailure.from(failure)
        val profileFailure = failure.takeUnless { taskFailure != null }
        val index = if (attachToTypedFailure) timeline.indexOfLast { it.progressDeadline != null || it.recoveryBlock != null || it.combinedFailure != null } else -1
        if (index >= 0) {
            timeline[index] = timeline[index].copy(profileFailure = profileFailure, taskVerificationFailure = taskFailure)
        } else {
            timeline.add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = profileFailure, taskVerificationFailure = taskFailure))
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
        contentSnapshot = null
        content.setLength(0)
        contentLineage.reset()
        pendingTimelineIndex = -1
        pendingTimelineText.setLength(0)
        timeline.removeAll { it.type == AssistantTimelineItemType.TEXT }
        restoredIndex = restoredText.size
        appendContent(text)
        timelineVersion++
    }

    fun replaceCombined(candidate: dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate) {
        replaceCombinedAnswer(candidate.text, candidate.report)
    }

    fun thoughtText(): String = thoughts.toString()

    fun combinedAnswerTimeline(text: String, report: dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport): List<AssistantTimelineItem> {
        flushTimelineText()
        return timeline.filterNot { it.type == AssistantTimelineItemType.TEXT || it.combinedQuality != null } +
            listOf(
                AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = text, responseSource = responseSource),
                AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report)
            )
    }

    fun replaceCombinedAnswer(text: String, report: dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport) {
        flushTimelineText()
        contentSnapshot = null
        content.setLength(0)
        contentLineage.reset()
        pendingTimelineIndex = -1
        pendingTimelineText.setLength(0)
        restoredIndex = restoredText.size
        timeline.removeAll { it.type == AssistantTimelineItemType.TEXT || it.combinedQuality != null }
        appendContent(text)
        updateCombinedQuality(report)
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
        if (hasPendingChanges()) {
            publish(onUpdate, nanoTimeProvider())
        } else {
            val first = outputPublications.drain()
            if (first.isNotEmpty()) onOutputPublication(currentContent(), contentLineage.lineage, first)
        }
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
        onOutputPublication(currentContent(), contentLineage.lineage, outputPublications.drain())
        onUpdate(currentContent(), thoughts.toString(), timeline.toList())
        hasPublishedAnswer = hasPublishedAnswer || hasResponse
        publishedContentLength = content.length
        publishedThoughtLength = thoughts.length
        publishedTimelineVersion = timelineVersion
        lastPublishedAtNanos = publishedAtNanos
    }

    private fun appendTimelineText(type: AssistantTimelineItemType, chunk: String, source: dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource? = null, lineage: OutputLineage? = null) {
        val last = timeline.lastOrNull()
        if (last?.type != type || last.toolSequence != null || last.responseSource != source) {
            flushTimelineText()
            timeline += AssistantTimelineItem(type = type, content = "", responseSource = source)
        }
        if (pendingTimelineIndex != timeline.lastIndex) {
            flushTimelineText()
            pendingTimelineIndex = timeline.lastIndex
            pendingTimelineText.append(timeline.last().content)
        }
        if (type == AssistantTimelineItemType.TEXT && chunk.isNotEmpty()) {
            val previous = timeline.last()
            val owner = if (pendingTimelineText.isEmpty()) lineage else previous.outputLineage.takeIf { it == lineage }
            if (previous.outputLineage != owner) timeline[timeline.lastIndex] = previous.copy(outputLineage = owner)
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
