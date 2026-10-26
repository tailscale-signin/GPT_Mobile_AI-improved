package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.OutputLimitRecoverySession
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReportContinuationTest {
    private val passage = "The randomized trial enrolled 20 participants. Scores were 9 versus 14 points. Follow-up was short."
    private val packet = ResearchDeliveryPacket.build(
        ResearchSnapshot("sleep trial", sources = listOf(ResearchSource("S1", "https://example.org/trial", "Sleep trial", "Read", passage))),
        ResearchReportDetail.BRIEF
    )
    private val first = "Partial coverage. Independent review is not available.\n\nThe randomized trial enrolled 20 participants. [S1]"
    private val suffix = "\n\nScores were 9 versus 14 points. Follow-up was short. [S1]\n\n## References\n\n[S1] https://example.org/trial"

    private fun writer(text: String, limited: Boolean = false, interrupted: Boolean = false) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            emit(ProviderEvent.TextDelta(text))
            if (interrupted) {
                emit(ProviderEvent.Failed("DNS lookup failed", errorCode = "DNS_FAILURE", transportComplete = false))
            } else {
                if (limited) emit(ProviderEvent.Usage(outputTokens = 128, finishReason = "max_tokens"))
                emit(ProviderEvent.Completed)
            }
        }
    }

    @Test fun oneOuterValidatorAcceptsTheAssembledReportAcrossPhysicalContinuations() = runTest {
        var continuations = 0
        var createdWriters = 0
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val recovered = OutputLimitRecoverySession(writer(first, limited = true), maxContinuations = 1) { draft, exchanges ->
            assertEquals(first, draft)
            assertTrue(exchanges.isEmpty())
            continuations++
            writer(suffix)
        }
        val delivery = ResearchDeliverySession(recovered, { packet }, outputCap = 1024, saveReceipt = { receipts += it }) { _, _ ->
            createdWriters++
            recovered
        }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, continuations)
        assertEquals("The accepted assembled report must not trigger a suffix-only repair", 1, createdWriters)
        assertEquals(first + suffix, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertFalse(events.any { it is ProviderEvent.Failed })
        assertEquals(ResearchReportValidity.ACCEPTED, receipts.single().reportValidity)
        assertEquals(1024, receipts.single().outputCap)
        assertTrue(receipts.single().evidenceAvailable)
    }

    @Test fun transportFailureKeepsTheDraftAndReceiptWithoutStartingSemanticRepair() = runTest {
        var createdWriters = 0
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val interrupted = writer(first, interrupted = true)
        val delivery = ResearchDeliverySession(interrupted, { packet }, saveReceipt = { receipts += it }) { _, _ ->
            createdWriters++
            interrupted
        }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, createdWriters)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains(first))
        assertFalse(events.contains(ProviderEvent.Completed))
        assertEquals(ResearchReportValidity.EVIDENCE_ONLY, receipts.single().reportValidity)
        assertEquals(listOf("DNS_FAILURE"), receipts.single().issueCodes)
        assertTrue(receipts.single().retryEligible)
    }
}
