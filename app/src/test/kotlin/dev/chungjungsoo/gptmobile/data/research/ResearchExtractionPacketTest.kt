package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchExtractionPacketTest {
    private val passage = "The device supports two parallel requests. The complete finding includes its observed limitations and contrary evidence. ".repeat(40)
    private val source = ResearchSource("S1", "https://example.org/source", "Source", "Read", passage, versionId = "v1")
    private val questions = listOf(ResearchQuestion("Q1", "What are the documented limits?"))

    @Test fun hordeAndSmallRoutesReceiveOneBoundedClaimWithFullSerializedInputAdmission() {
        for (output in listOf(256, 512)) {
            val budget = ResearchModelRequestBudget(output, 900, true)
            val packet = requireNotNull(ResearchExtractionPackets.next(source, questions, "Research the limits", 1, budget, emptySet()))
            assertEquals(1, packet.maxClaims)
            assertTrue(packet.instruction.contains("at most 1 claims"))
            assertTrue(packet.instruction.contains("under ${output * 2} characters"))
            assertTrue(ContextBudgetService.estimate(researchStagePrompt(packet.instruction, "Research the limits", packet.evidence, 1)) <= budget.inputTokens)
            assertFalse(packet.canCompleteSource)
            assertTrue(packet.end < source.passage.length)
            assertEquals(passage, source.passage)
        }
    }

    @Test fun processedPacketDoesNotRepeatAndChangedTaskCannotReuseItsAnalysisReceipt() {
        val budget = ResearchModelRequestBudget(256, 1200, true)
        val first = requireNotNull(ResearchExtractionPackets.next(source, questions, "Research the limits", 1, budget, emptySet()))
        val next = requireNotNull(ResearchExtractionPackets.next(source, questions, "Research the limits", 1, budget, setOf(first.key)))
        assertEquals(first.end, next.start)
        assertNotEquals(first.key, next.key)
        val changed = requireNotNull(ResearchExtractionPackets.next(source, questions, "Research contrary findings", 1, budget, setOf(first.key)))
        assertEquals(0, changed.start)
        assertNotEquals(first.key, changed.key)
    }

    @Test fun shortFinalPacketCanCompleteAtSmallOutputAndTinyTailNeverBecomesUnreachable() {
        val budget = ResearchModelRequestBudget(256, 2000, true)
        val short = source.copy(passage = passage.take(100))
        assertTrue(requireNotNull(ResearchExtractionPackets.next(short, questions, "Research the limits", 1, budget, emptySet())).canCompleteSource)
        val edge = source.copy(passage = passage.take(1030))
        val processed = linkedSetOf<String>()
        var end = 0
        do {
            val packet = requireNotNull(ResearchExtractionPackets.next(edge, questions, "Research the limits", 1, budget, processed))
            assertEquals(end, packet.start)
            assertTrue(packet.end - packet.start >= 20)
            processed += packet.key
            end = packet.end
            if (end == edge.passage.length) assertTrue(packet.canCompleteSource)
        } while (end < edge.passage.length)
        assertEquals(edge.passage.length, end)
        assertNull(ResearchExtractionPackets.next(edge, questions, "Research the limits", 1, budget, processed))
    }

    @Test fun oversizedTaskAndQuestionsAreRetainedInsteadOfTruncatedToForceDispatch() {
        assertNull(ResearchExtractionPackets.next(source, questions, "Task ".repeat(2000), 1, ResearchModelRequestBudget(256, 500, true), emptySet()))
        assertEquals(passage, source.passage)
    }
}
