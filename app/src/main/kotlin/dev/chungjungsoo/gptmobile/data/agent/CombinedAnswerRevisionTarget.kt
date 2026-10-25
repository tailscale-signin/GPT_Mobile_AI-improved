package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedManifest
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.database.entity.hasHistoricalRevisionSelected
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The exact completed answer selected for an explicit new turn, never an append cursor. */
@Serializable
data class CombinedAnswerRevisionTarget(
    val messageId: Int,
    val chatId: Int,
    val userMessageId: Int,
    val currentRunId: String?,
    val ownershipFence: Long,
    val selectedRevisionIndex: Int,
    val runId: String,
    val answerDigest: String,
    val reportRevisionId: String,
    val assessmentDigest: String,
    val manifest: CombinedManifestReference? = null,
    val parentRootBudgetId: String? = null
) {
    /** Selection is checked in the UI; storage rechecks the frozen index independently of UI preferences. */
    fun validate(message: MessageV2, run: AgentRun, requireDisplayedSelection: Boolean = true) {
        require(!requireDisplayedSelection || message.activeRevisionIndex == selectedRevisionIndex) { "The selected answer changed. Review it before creating a revision." }
        require(capture(message.copy(activeRevisionIndex = selectedRevisionIndex), run) == this) { "The selected answer changed. Review it before creating a revision." }
    }

    companion object {
        fun capture(message: MessageV2, run: AgentRun): CombinedAnswerRevisionTarget {
            require(message.activeRevisionIndex == ACTIVE_REVISION_LATEST || message.activeRevisionIndex in message.revisions.indices) { "The selected answer revision is unavailable." }
            require(run.runId.startsWith("combined-synthesis:") && run.runId == message.effectiveRunId() && run.chatId == message.chatId && run.assistantMessageId == message.id && run.userMessageId == message.linkedMessageId) { "The selected answer belongs to a different turn." }
            require(run.status in setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED")) { "Stop the selected answer before creating a revision." }
            require(CombinedRecoveryFailure.retained(message, run.terminalError)?.category == CombinedRecoveryCategory.REQUIREMENTS_UNMET) { "This answer does not have a completed correction assessment." }
            val report = requireNotNull(message.effectiveTimeline().lastOrNull { it.combinedQuality != null }?.combinedQuality) { "The selected answer assessment is unavailable." }
            require(report.finalAssessment && report.protocolComplete && !report.complete && (report.correctionRequired || report.lengthCompliance == "UNMET" || report.topicCoverage == "UNMET")) { "The selected answer has not finished its assessment." }
            val reference = message.effectiveCombinedManifest()
            val digest = if (reference == null) {
                researchHash(message.effectiveContent())
            } else {
                require((message.hasHistoricalRevisionSelected() || reference.runId == run.runId) && reference.id.isNotBlank() && reference.runId.isNotBlank() && reference.revisionId.isNotBlank() && (report.revisionId.isBlank() || reference.revisionId == report.revisionId) && reference.bodyLength > 0) { "The selected answer document changed." }
                require(!reference.materialized || researchHash(message.effectiveContent()) == reference.bodyDigest) { "The selected answer document changed." }
                reference.bodyDigest
            }
            require(report.answerDigest.isNotBlank() && report.answerDigest == digest) { "The selected answer and its assessment disagree." }
            // Match CombinedDocuments' compact timeline shape: only its externalized
            // diagnostic collections may differ; every retained assessment field is bound.
            val assessment = report.copy(
                passages = emptyList(),
                decisions = emptyList(),
                verificationReceipts = emptyList(),
                supportEdges = emptyList(),
                checks = emptyList(),
                verificationNeeds = emptyList(),
                sourceDispositions = emptyList()
            )
            return CombinedAnswerRevisionTarget(
                message.id, message.chatId, requireNotNull(message.linkedMessageId), message.currentRunId,
                message.ownershipFence, message.activeRevisionIndex, run.runId, digest, report.revisionId,
                researchHash(Json.encodeToString(assessment)), reference?.copy(materialized = false),
                CombinedAcceptedTurn.context(run.synthesisSnapshot)?.rootBudgetId
            )
        }
    }
}
