package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReviewPacketTest {
    private val quote = "The documented server supports two parallel requests and preserves completed responses after a connection interruption."
    private val outside = "A contrary result exists only in the omitted appendix and was not supplied for this review."
    private val body = outside + " The article describes the test procedure and its relevant limitations. ".repeat(300) + quote + " Additional archival discussion follows the main result. ".repeat(2)
    private val source = ResearchSource("S1", "https://example.org/article", "Test article", "Read", body, versionId = "v1")
    private val claim = ResearchClaim("Two parallel requests are supported.", source.id, quote, questionId = "Q1", exactQuoteVerified = true, sourceVersion = "v1", passageHash = researchHash(body), extractedBy = "extractor")
    private val questions = listOf(ResearchQuestion("Q1", "What request limit is documented?"))
    private val budget = ResearchModelRequestBudget(256, 1200, true)
    private val reply = ResearchModelReply("{}", "reviewer", "review-invocation", independent = true)

    @Test fun longCaptureBecomesOneBoundedReviewWithExactSourceBindingsAndNoMutation() {
        val packet = requireNotNull(ResearchReviewPackets.prepare(source, claim, questions, "Research request limits", 1, budget))
        assertTrue(ContextBudgetService.estimate(researchStagePrompt(packet.instruction, "Research request limits", packet.evidence, 1)) <= budget.inputTokens)
        assertTrue(quote in packet.passage)
        assertFalse(outside in packet.passage)
        assertTrue(packet.passage.length < source.passage.length)
        assertEquals(source.passage.substring(packet.start, packet.end), packet.passage)
        assertEquals("v1", packet.sourceVersion)
        assertEquals(researchHash(body), packet.sourceHash)
        assertEquals(body, source.passage)
        val result = buildJsonObject {
            put("packetId", packet.id)
            put("verdict", "Supported")
            put("relevance", "Relevant")
            put("questionId", "Q1")
            put("rationale", "The supplied quote states the request limit.")
        }
        val reviewed = requireNotNull(ResearchReviewPackets.accept(packet, result, claim, source, questions, reply))
        assertEquals(claim.sourceVersion, reviewed.sourceVersion)
        assertEquals(claim.passageHash, reviewed.passageHash)
        assertEquals("v1", reviewed.reviewedSourceVersion)
        assertTrue(reviewed.reviewRubric.startsWith("bounded-passage-v1:"))
        assertTrue(reviewed.independentReview)
    }

    @Test fun unseenCounterevidenceWrongPacketWrongQuestionAndChangedCaptureCannotBeAccepted() {
        val packet = requireNotNull(ResearchReviewPackets.prepare(source, claim, questions, "Research request limits", 1, budget))
        fun result(id: String = packet.id, question: String = "Q1", counter: String = "", verdict: String = "Supported") = buildJsonObject {
            put("packetId", id)
            put("verdict", verdict)
            put("relevance", "Relevant")
            put("questionId", question)
            put("rationale", "Review reason")
            put("counterEvidence", counter)
        }
        assertNull(ResearchReviewPackets.accept(packet, result(id = "different"), claim, source, questions, reply))
        assertNull(ResearchReviewPackets.accept(packet, result(question = "Q-missing"), claim, source, questions, reply))
        for (verdict in listOf("Supported", "Contradicted", "Insufficient")) {
            assertNull(ResearchReviewPackets.accept(packet, result(counter = outside, verdict = verdict), claim, source, questions, reply))
        }
        assertNull(ResearchReviewPackets.accept(packet, result(), claim, source.copy(passage = source.passage + " changed"), questions, reply))
        assertNull(ResearchReviewPackets.accept(packet, result(verdict = "Contradicted"), claim, source, questions, reply))
    }

    @Test fun oversizedMandatoryTaskOrQuoteRemainsPendingWithoutTruncation() {
        assertNull(ResearchReviewPackets.prepare(source, claim, questions, "Mandatory task ".repeat(2000), 1, budget))
        val longClaim = claim.copy(quote = body.take(7000))
        assertNull(ResearchReviewPackets.prepare(source, longClaim, questions, "Research request limits", 1, budget))
        assertEquals(body, source.passage)
        assertEquals(quote, claim.quote)
    }
}
