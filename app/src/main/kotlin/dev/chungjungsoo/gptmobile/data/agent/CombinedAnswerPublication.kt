package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash

/** Invariants shared by the event reducer and the durable final-answer checkpoint. */
internal object CombinedAnswerPublication {
    fun validate(text: String, report: CombinedQualityReport, formatVersion: Int = 1) {
        require(formatVersion == 1) { "COMBINED_FINAL_BUNDLE_VERSION_UNSUPPORTED" }
        require(report.revisionId.isNotBlank()) { "COMBINED_FINAL_REVISION_MISSING" }
        // A reasoning-only or empty writer response is not a deliverable, even if its digest and
        // zero-valued display metrics are internally consistent. Reject before persistence/render.
        require(text.isNotBlank() && visibleWordCount(text) > 0) { "COMBINED_FINAL_EMPTY_VISIBLE_OUTPUT" }
        require(report.answerDigest == researchHash(text)) { "COMBINED_FINAL_BODY_DIGEST_MISMATCH" }
        require(report.claimMapping?.matches(text) != false) { "COMBINED_FINAL_MAPPING_DIGEST_MISMATCH" }
        fun valid(link: CombinedCoverageLink): Boolean = link.startUtf16 >= 0 &&
            link.endUtf16 > link.startUtf16 &&
            link.endUtf16 <= text.length &&
            researchHash(text.substring(link.startUtf16, link.endUtf16)) == link.textDigest
        require(
            report.passages.all { passage ->
                passage.matches(text) &&
                    passage.links.all { link ->
                        link.start >= passage.start && link.end <= passage.end && link.matches(text)
                    }
            }
        ) { "COMBINED_FINAL_ATTRIBUTION_RANGE_MISMATCH" }
        require(report.decisions.all { it.finalSpans.all(::valid) }) { "COMBINED_FINAL_DECISION_RANGE_MISMATCH" }
        require(
            report.sourceDispositions.all { disposition ->
                disposition.finalStart >= 0 &&
                    disposition.finalEnd >= disposition.finalStart &&
                    disposition.finalEnd <= text.length &&
                    disposition.finalLinks.all(::valid)
            }
        ) { "COMBINED_FINAL_SOURCE_ACCOUNTING_RANGE_MISMATCH" }
        require(
            report.verificationReceipts.all { receipt ->
                receipt.answerDigest == report.answerDigest && receipt.revisionId == report.revisionId && valid(receipt.finalSpan)
            }
        ) { "COMBINED_FINAL_EVIDENCE_REVISION_MISMATCH" }
        if (report.version >= 6) {
            val committedDraft = report.version >= 7 && report.assessmentStage == CombinedCommittedDraft.STAGE
            if (committedDraft) {
                require(
                    !report.finalAssessment &&
                        !report.complete &&
                        !report.protocolComplete &&
                        !report.semanticCoverageVerified &&
                        !report.factualAccuracyVerified &&
                        report.verificationReceipts.isEmpty() &&
                        report.displayableDraft &&
                        report.deliveredScopeStatus == "PARTIAL" &&
                        report.verificationStatus == "UNVERIFIED" &&
                        report.status in setOf("WRITING", "FAILED", "INTERRUPTED", "CANCELED")
                ) { "COMBINED_DRAFT_COMPLETION_CONTRADICTORY" }
            } else {
                require(report.finalAssessment && report.assessmentStage == "FINAL_DISPLAY") { "COMBINED_FINAL_ASSESSMENT_MISSING" }
            }
            require(report.wordCount == visibleWordCount(text) && report.displayedCharacters == text.length) { "COMBINED_FINAL_DISPLAY_METRICS_MISMATCH" }
            require(report.writerCharacters >= 0 && report.locallyAddedCharacters >= 0 && report.locallyRemovedCharacters >= 0) { "COMBINED_FINAL_ASSEMBLY_METRICS_INVALID" }
            if (report.writerAnswerDigest.isNotBlank()) {
                require(report.writerCharacters.toLong() + report.locallyAddedCharacters - report.locallyRemovedCharacters == report.displayedCharacters.toLong()) { "COMBINED_FINAL_ASSEMBLY_METRICS_MISMATCH" }
                require(report.writerAssessment?.let { it.answerDigest == report.writerAnswerDigest && it.revisionId == report.revisionId } == true) { "COMBINED_WRITER_ASSESSMENT_MISMATCH" }
            }
            report.claimMapping?.let { mapping ->
                require(
                    report.fullyLinkedClaims == mapping.claims.count { it.fullyLinked } &&
                        report.partialSourceLinks == mapping.claims.count { it.status == "PARTIAL" } &&
                        report.unresolvedSourceLinks == mapping.claims.count { it.status in setOf("UNMATCHED", "NOT_EVALUATED") }
                ) { "COMBINED_FINAL_SOURCE_METRICS_MISMATCH" }
            }
            require(!report.complete || report.protocolComplete && !report.correctionRequired) { "COMBINED_FINAL_COMPLETION_CONTRADICTORY" }
            require(!report.complete || report.semanticCoverageVerified) { "COMBINED_FINAL_SEMANTIC_COVERAGE_UNVERIFIED" }
        }
    }
}
