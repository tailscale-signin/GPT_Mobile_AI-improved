package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash

/** A saved, displayable prefix is not a completed or semantically checked answer. */
internal object CombinedCommittedDraft {
    const val STAGE = "COMMITTED_DRAFT"

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
