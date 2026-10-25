package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** Recovery can establish text lineage from saved sources; it cannot certify writer completion. */
internal object CombinedAttributionRecovery {
    const val PENDING = "ATTRIBUTION_PENDING"
    const val RECOVERED = "ATTRIBUTION_RECOVERED"

    fun pending(answer: String, revisionId: String): CombinedQualityReport = CombinedQualityReport(
        answerDigest = researchHash(answer),
        revisionId = revisionId,
        status = PENDING,
        sourceLinkStatus = PENDING,
        issues = listOf(PENDING)
    )

    fun needed(report: CombinedQualityReport?): Boolean = report == null ||
        report.status == PENDING ||
        report.status == "WRITING" &&
        report.claimMapping == null ||
        (report.claimMapping?.version ?: 2) < 2

    fun reconstruct(answer: String, sources: List<CombinedModelResponse>, revisionId: String, previous: CombinedQualityReport? = null): CombinedQualityReport {
        // Candidate IDs use original contributor positions. Removing an unavailable
        // earlier source would relabel C2 claims as C1 and break immutable references.
        val retained = sources.map { source ->
            if (source.content.isNotBlank() && (source.sourceDigest.isBlank() || source.sourceDigest == researchHash(source.content))) source else source.copy(content = "")
        }
        val eligible = retained.filter { it.content.isNotBlank() }
        if (eligible.isEmpty()) return pending(answer, revisionId)
        val mapping = CombinedAnswerMappings.get(answer, retained)
        val sameFinalBody = previous?.takeIf { it.status != "WRITING" && it.answerDigest == researchHash(answer) && it.status != PENDING }
        val legacyAssessment = sameFinalBody != null && sameFinalBody.version < 6
        val receipts = sameFinalBody?.verificationReceipts.orEmpty().filter { receipt ->
            val span = receipt.finalSpan
            receipt.answerDigest == researchHash(answer) &&
                receipt.revisionId == revisionId &&
                span.startUtf16 >= 0 &&
                span.endUtf16 > span.startUtf16 &&
                span.endUtf16 <= answer.length &&
                researchHash(answer.substring(span.startUtf16, span.endUtf16)) == span.textDigest
        }
        val passages = CombinedAnswerMappings.passages(answer, mapping, sameFinalBody?.passages.orEmpty())
        return (sameFinalBody ?: pending(answer, revisionId)).copy(
            revisionId = revisionId,
            status = sameFinalBody?.status ?: RECOVERED,
            finalAssessment = !legacyAssessment && sameFinalBody?.finalAssessment == true,
            assessmentStage = if (legacyAssessment) "LEGACY_UNCONFIRMED" else sameFinalBody?.assessmentStage ?: "RECOVERED_LINEAGE",
            semanticCoverageVerified = !legacyAssessment && sameFinalBody?.semanticCoverageVerified == true,
            factualAccuracyVerified = !legacyAssessment && sameFinalBody?.factualAccuracyVerified == true,
            verificationStatus = if (legacyAssessment) "LEGACY_ASSESSMENT_UNCONFIRMED" else sameFinalBody?.verificationStatus ?: "UNVERIFIED",
            verificationReceipts = receipts,
            sourceLinkStatus = if (mapping.evaluated) "RECOVERED_LOCAL_LINKS" else PENDING,
            passages = passages,
            claimMapping = mapping,
            representedProfileUids = mapping.links.flatMapTo(linkedSetOf()) { it.profileUids },
            includedProfileUids = eligible.mapTo(linkedSetOf()) { it.platformUid },
            issues = (sameFinalBody?.issues ?: listOf("COMPLETION_NOT_RECORDED")) +
                (if (legacyAssessment) listOf("LEGACY_FINAL_ASSESSMENT_UNCONFIRMED") else emptyList()) +
                (if (mapping.evaluated) emptyList() else listOf(PENDING))
        )
    }
}
