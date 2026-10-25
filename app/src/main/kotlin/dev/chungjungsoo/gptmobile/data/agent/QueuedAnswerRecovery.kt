package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.GenerationReceipt
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf

internal data class QueuedAnswerRecoveryResult(val text: String, val report: CombinedQualityReport? = null, val failure: String? = null, val recordCheckpoint: CombinedRecordCheckpoint? = null)

/** Interpret a saved result locally. Inspection never starts another generation or verification call. */
internal object QueuedAnswerRecovery {
    fun plain(previous: String, answer: String, continuationPrefixDigest: String?): String {
        require(answer.isNotBlank()) { "QUEUE_RESULT_EMPTY" }
        if (previous.isBlank() || answer.startsWith(previous)) return answer
        require(continuationPrefixDigest == researchHash(previous)) { "QUEUE_RESULT_REVIEW_REQUIRED: The saved result cannot be safely joined to the earlier answer. Its receipt and the earlier answer are retained." }
        return previous + continuationSuffix(previous, answer)
    }

    suspend fun combined(
        answer: String,
        previous: String,
        continuationPrefixDigest: String?,
        frozen: CombinedInputSnapshot,
        runId: String,
        records: CombinedRecordCheckpoint?,
        validateAccess: suspend () -> Unit
    ): QueuedAnswerRecoveryResult {
        val revision = frozen.liveRevisionId(runId)
        var terminal: ProviderEvent = ProviderEvent.Completed
        var recordCheckpoint: CombinedRecordCheckpoint? = null
        val wire = if (frozen.deliveryProtocol == "PASSAGES_V3") {
            val initial = records ?: CombinedRecordCheckpoint(snapshotDigest = frozen.inputDigest, revisionId = revision)
            require(initial.snapshotDigest == frozen.inputDigest && initial.revisionId == revision) { "QUEUE_RESULT_SNAPSHOT_CHANGED" }
            val protocol = CombinedRecordProtocol(
                initial,
                (frozen.claimInventory?.claims ?: CombinedClaimSemantics.records(frozen.sources)).flatMap { it.candidateIds + it.id + it.canonicalId }.toSet(),
                frozen.sources.indices.mapTo(linkedSetOf()) { "C${it + 1}" },
                frozen.evidence.records.mapTo(linkedSetOf()) { it.id },
                Int.MAX_VALUE,
                frozen.evidence.records.mapIndexed { index, record -> record.id to "[${index + 1}](${record.url})" }.toMap()
            )
            // A process can stop after saving validated records but before publishing
            // their message. The immutable END is sufficient for local replay; feeding
            // the receipt's optional opening code fence again would corrupt recovery.
            if (!initial.ended || initial.pending.isNotBlank() || initial.failure != null) {
                protocol.accept(answer)
                protocol.markAttemptFinished("COMPLETED")
                protocol.recordProgress(protocol.checkpoint().records.size > initial.records.size)
            }
            val completed = protocol.checkpoint()
            recordCheckpoint = completed
            if (protocol.failureReason != null || !completed.ended || completed.pending.isNotBlank()) {
                terminal = ProviderEvent.Failed("OUTPUT_PROTOCOL_INVALID: The saved remote result did not finish a valid answer record. Its receipt and committed passages are retained.", errorCode = "OUTPUT_PROTOCOL_INVALID", transportComplete = true)
            }
            completed.records.joinToString("\n", postfix = "\n") { it.wire }
        } else if (frozen.deliveryProtocol == "PASSAGES_V2") {
            require(previous.isBlank()) { "QUEUE_RESULT_REVIEW_REQUIRED: This legacy record continuation needs an explicit revision. Its saved result is retained." }
            answer
        } else {
            plain(previous, answer, continuationPrefixDigest)
        }
        val cached = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(wire), terminal)
        }
        val session = CombinedDeliverySession(
            base = cached,
            sources = frozen.sources,
            maxCharacters = Int.MAX_VALUE,
            committedPrefix = previous,
            validateAccess = validateAccess,
            maxRepairRounds = 0,
            originalRequest = frozen.userMessage.content,
            evidence = frozen.evidence,
            turnContext = frozen.turnContext,
            structured = frozen.deliveryProtocol in setOf("PASSAGES_V2", "PASSAGES_V3"),
            recordProtocol = frozen.deliveryProtocol == "PASSAGES_V3",
            combined = frozen.combined,
            answerContract = frozen.answerContract,
            layoutContract = CombinedLayoutContract.fromPrompt(frozen.prompt),
            revisionId = revision,
            researchObligations = CombinedResearchObligation.fromPrompt(frozen.prompt),
            repair = { _, _ -> error("A saved remote result cannot authorize another model generation.") }
        )
        var bundle: ProviderEvent.CombinedAnswer? = null
        var failure: String? = null
        session.streamRound(emptyList(), emptyList()).collect { event ->
            if (event is ProviderEvent.CombinedAnswer) bundle = event
            if (event is ProviderEvent.Failed) failure = event.message
        }
        val final = bundle ?: return QueuedAnswerRecoveryResult(previous, failure = failure ?: "QUEUE_RESULT_EMPTY: No usable answer was recovered from the saved job.", recordCheckpoint = recordCheckpoint)
        CombinedAnswerPublication.validate(final.text, final.report)
        return QueuedAnswerRecoveryResult(final.text, final.report, failure, recordCheckpoint)
    }

    fun message(previous: MessageV2, recovered: QueuedAnswerRecoveryResult): MessageV2 {
        val retained = clearPending(previous).timeline.filterNot { it.type == AssistantTimelineItemType.TEXT || it.combinedQuality != null || it.generation != null }
        val timeline = retained + listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = recovered.text),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = GenerationReceipt(finishReason = "queue_complete")),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = "The saved remote answer was imported without resubmitting the job.")
        ) + listOfNotNull(recovered.report?.let { AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = it) })
        return previous.copy(content = recovered.text, timeline = timeline)
    }

    fun clearPending(message: MessageV2): MessageV2 = message.copy(
        timeline = message.timeline.filterNot {
            it.profileFailure?.category == "QUEUED_JOB" ||
                it.combinedFailure?.remoteJobStatus == "PENDING" ||
                it.content.startsWith("QUEUE_JOB_PENDING:") ||
                it.runError?.startsWith("QUEUE_JOB_PENDING:") == true
        }
    )
}
