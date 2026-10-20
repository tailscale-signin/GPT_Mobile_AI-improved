package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchBatchReviewTest {
    private val source = ResearchSource("S1", "https://example.org/trial", "Trial", "Read", "Methods included 100 people. The treatment showed no significant benefit. Adverse events were reported.", versionId = "v1")
    private val questions = listOf(ResearchQuestion("Q1", "Does the treatment help?"))
    private val claims = listOf(ResearchClaim("Benefit was uncertain", "S1", "The treatment showed no significant benefit.", questionId = "Q1", exactQuoteVerified = true), ResearchClaim("Harms were reported", "S1", "Adverse events were reported.", questionId = "Q1", exactQuoteVerified = true))
    private fun row(claim: ResearchClaim, verdict: String = "Supported", version: String = "v1"): JsonObject = buildJsonObject {
        put("claimId", ResearchBatchReview.id(claim))
        put("verdict", verdict)
        put("questionId", "Q1")
        put("relevance", "Relevant")
        put("sourceVersionId", version)
        put("sourceHash", researchHash(source.passage))
        put("rationale", "Exact result is retained")
        put("counterEvidence", "The treatment showed no significant benefit.")
    }

    @Test fun partialBatchCommitsOnlyValidItemsAndKeepsPerItemProvenance() {
        val reply = ResearchModelReply("", "different-reviewer", "physical-2", true)
        val result = ResearchBatchReview.apply(listOf(row(claims[0])), claims, source, questions, reply)
        assertEquals("Supported", result[0].verdict)
        assertEquals("physical-2", result[0].reviewInvocationId)
        assertTrue(result[0].independentReview)
        assertEquals(claims[1], result[1])
    }

    @Test fun changedSourceAndDuplicateClaimRecordsCannotReuseAReview() {
        assertEquals(claims, ResearchBatchReview.apply(listOf(row(claims[0], version = "v0")), claims, source, questions, null))
        assertEquals(claims, ResearchBatchReview.apply(listOf(row(claims[0]), row(claims[0])), claims, source, questions, null))
    }

    @Test fun unversionedWebReviewIsBoundToTheExactBodyHash() {
        val web = source.copy(versionId = "")
        val reviewed = ResearchBatchReview.apply(listOf(row(claims[0], version = "")), claims, web, questions, null)[0]
        assertEquals(researchHash(web.passage), reviewed.reviewedSourceVersion)
        val changed = web.copy(passage = web.passage + " A correction changed the conclusion.")
        val snapshot = ResearchSnapshot("treatment trial benefit", sources = listOf(changed), claims = listOf(reviewed), questionPlan = questions)
        assertTrue(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket.build(snapshot).claims.isEmpty())
    }

    @Test fun contradictionRequiresExactCounterevidenceFromThatCapture() {
        val valid = row(claims[0], "Contradicted")
        assertEquals("Contradicted", ResearchBatchReview.apply(listOf(valid), claims, source, questions, null)[0].verdict)
        val invalid = JsonObject(valid - "counterEvidence")
        assertEquals(claims, ResearchBatchReview.apply(listOf(invalid), claims, source, questions, null))
    }
}
