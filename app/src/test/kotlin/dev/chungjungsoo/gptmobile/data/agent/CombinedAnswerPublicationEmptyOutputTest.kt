package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedAnswerPublicationEmptyOutputTest {
    @Test fun emptyVisibleBodyCannotBeAcceptedEvenWhenItsDigestMatches() {
        val empty = ""
        val report = CombinedQualityReport(
            revisionId = "empty-writer-revision",
            answerDigest = researchHash(empty)
        )

        val failure = runCatching { CombinedAnswerPublication.validate(empty, report) }.exceptionOrNull()

        assertEquals("COMBINED_FINAL_EMPTY_VISIBLE_OUTPUT", failure?.message)
    }

    @Test fun whitespaceOnlyVisibleBodyCannotBeAccepted() {
        val whitespace = " \n\t "
        val report = CombinedQualityReport(
            revisionId = "whitespace-writer-revision",
            answerDigest = researchHash(whitespace)
        )

        val failure = runCatching { CombinedAnswerPublication.validate(whitespace, report) }.exceptionOrNull()

        assertEquals("COMBINED_FINAL_EMPTY_VISIBLE_OUTPUT", failure?.message)
    }
}
