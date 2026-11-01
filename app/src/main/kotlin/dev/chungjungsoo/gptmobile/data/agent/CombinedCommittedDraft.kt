package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash

/** A saved, displayable prefix is not a completed or semantically checked answer. */
internal object CombinedCommittedDraft {
    const val STAGE = "COMMITTED_DRAFT"

    /** Preserve useful text and diagnostics while making a failed final assessment non-final. */
    fun fromFinal(text: String, report: CombinedQualityReport): CombinedQualityReport = report.copy(
        version = 7,
        complete = false,
        status = "FAILED",
        deliveredScopeStatus = "PARTIAL",
        verificationStatus = "UNVERIFIED",
        correctionRequired = true,
        finalAssessment = false,
        assessmentStage = STAGE,
        displayedCharacters = text.length,
        wordCount = visibleWordCount(text),
        displayableDraft = text.isNotBlank(),
        protocolComplete = false,
        semanticCoverageVerified = false,
        factualAccuracyVerified = false,
        verificationReceipts = emptyList()
    )

    fun report(text: String, revisionId: String, passages: List<CombinedPassageRecord>, owners: Set<String>): CombinedQualityReport =
        CombinedQualityReport(
            version = 7,
            answerDigest = researchHash(text),
            revisionId = revisionId,
            status = "WRITING",
            deliveredScopeStatus = "PARTIAL",
            wordCount = visibleWordCount(text),
            displayedCharacters = text.length,
            displayableDraft = true,
            passages = passages,
            includedProfileUids = owners,
            consideredProfileUids = owners,
            sourceLinkStatus = "DRAFT_LINKS",
            assessmentStage = STAGE
        )
}
