package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedTextSpanIndexTest {
    @Test fun longSoftWrappedClaimKeepsItsWholeOriginalSpanAndEnding() {
        val prefix = "## Original contributor\n\n- "
        val body = "😀 " + (1..10000).joinToString("") { if (it % 17 == 0) "Details\r\n\t" else "Details " } + "Unique ending A."
        val source = CombinedModelResponse("one", "One", content = prefix + body)
        val claim = CombinedClaimSemantics.records(listOf(source)).single()
        val occurrence = claim.occurrences.single()
        val span = requireNotNull(occurrence.sourceSpan)

        assertTrue(claim.proposition.endsWith("Unique ending A."))
        assertEquals(prefix.length, span.startUtf16)
        assertEquals(source.content.length, span.endUtf16)
        assertEquals(body, source.content.substring(span.startUtf16, span.endUtf16))
        assertEquals(researchHash(body), span.textDigest)
        assertEquals(researchHash(prefix + body), occurrence.bodyDigest)
    }

    @Test fun longFinalSentenceKeepsACompleteDestinationAndRevisionReceipt() {
        val text = "Details ".repeat(10000) + "Unique ending A."
        val prefix = "## Result\n\n"
        val answer = prefix + text
        val ledger = CombinedEvidenceLedger()
        val result = CombinedFinalCheck.check(
            answer,
            listOf(CombinedModelResponse("one", "One", content = text)),
            ledger,
            CombinedAnswerPolicy(),
            revisionId = "long-revision"
        )
        val decision = result.decisions.single()
        val span = decision.finalSpans.single()

        assertEquals("INCLUDED", decision.disposition)
        assertEquals(prefix.length, span.startUtf16)
        assertEquals(answer.length, span.endUtf16)
        assertEquals(text, answer.substring(span.startUtf16, span.endUtf16))
        assertEquals(researchHash(text), span.textDigest)
        assertEquals(span, result.receipts.single().finalSpan)
        assertTrue(CombinedEvidenceVerifier.validateReceipt(answer, "long-revision", ledger, result.receipts.single()))
    }

    @Test fun whitespaceNormalizationRetainsRawRangesAndSubsequentOccurrences() {
        val first = "Alpha\t\r\n \u000b\u000cbeta."
        val second = "Alpha\t beta."
        val prefix = "😀 "
        val source = prefix + first + "\n\n- " + second
        val spans = CombinedTextSpanIndex(source)
        val a = requireNotNull(spans.find("Alpha beta."))
        val b = requireNotNull(spans.find("Alpha beta.", a.endUtf16))

        assertEquals(prefix.length, a.startUtf16)
        assertEquals(first, source.substring(a.startUtf16, a.endUtf16))
        assertEquals(researchHash(first), a.textDigest)
        assertEquals(source.length - second.length, b.startUtf16)
        assertEquals(second, source.substring(b.startUtf16, b.endUtf16))
        assertEquals(researchHash(second), b.textDigest)
        assertNull(spans.find("Alpha beta.", b.endUtf16))
    }

    @Test fun nonWhitespaceCharactersRemainLiteralAndCannotManufactureCoverage() {
        val text = "😀 [claim]+ retained\u00a0qualification."
        val spans = CombinedTextSpanIndex(text)

        assertEquals(CombinedCoverageLink(0, text.length, researchHash(text)), spans.find(text))
        assertNull(spans.find(text.replace("[claim]+", "claim")))
        assertNull(spans.find(text.replace("qualification", "Qualification")))
        assertNull(spans.find(text.replace('\u00a0', ' ')))
        assertNull(spans.find(""))
    }
}
