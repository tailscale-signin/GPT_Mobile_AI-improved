package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRepairRejectionDeliveryTest {
    private val source = ResearchSource("S1", "https://example.org/trial", "Trial", "Read", "The trial enrolled 20 participants. Scores were 9 versus 14 points.")
    private val packet = ResearchDeliveryPacket.build(ResearchSnapshot("trial", sources = listOf(source)), ResearchReportDetail.BRIEF)
    private val good = "The trial enrolled 20 participants. [S1]"
    private val bad = "The trial cured 999 participants. [S1]"
    private val original = "$good\n\n$bad\n\nPartial coverage. Independent review is not available."

    private fun session(block: suspend FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(block)
    }

    @Test fun invalidPatchPersistsItsReasonWithoutInventingAnEmptyReport() = runTest {
        val initial = session {
            emit(ProviderEvent.TextDelta(original))
            emit(ProviderEvent.Completed)
        }
        val correction = session {
            emit(ProviderEvent.TextDelta("{\"replacements\":["))
            emit(ProviderEvent.Completed)
        }
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val delivery = ResearchDeliverySession(initial, { packet }, saveReceipt = { receipts += it }) { _, repair -> if (repair) correction else initial }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        val saved = receipts.single()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(saved.issueCodes.contains("REPAIR_INVALID_JSON"))
        assertFalse(saved.issueCodes.contains("EMPTY_REPORT"))
        assertEquals(ResearchReportValidity.EVIDENCE_ONLY, saved.reportValidity)
        assertTrue(text.contains(good))
        assertFalse(text.contains(bad))
        assertFalse(text.contains("{\"replacements\""))
        assertFalse(events.contains(ProviderEvent.Completed))
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("correction could not be safely applied"))
    }

    @Test fun emptyInterruptedFailedAndTimedOutCorrectionsHaveDifferentReceipts() = runTest {
        val initial = session {
            emit(ProviderEvent.TextDelta(original))
            emit(ProviderEvent.Completed)
        }
        val outcomes = listOf(
            "REPAIR_EMPTY_RESPONSE" to session { emit(ProviderEvent.Completed) },
            "REPAIR_INCOMPLETE_RESPONSE" to session { emit(ProviderEvent.TextDelta("{\"replacements\":[")) },
            "REPAIR_REQUEST_FAILED" to session { emit(ProviderEvent.Failed("Connection closed")) },
            "REPAIR_TIMEOUT" to session { awaitCancellation() }
        )
        for ((code, correction) in outcomes) {
            val receipts = mutableListOf<ResearchDeliveryReceipt>()
            val delivery = ResearchDeliverySession(initial, { packet }, saveReceipt = { receipts += it }) { _, repair -> if (repair) correction else initial }
            val events = delivery.streamRound(emptyList(), emptyList()).toList()
            assertTrue(receipts.single().issueCodes.contains(code))
            assertFalse(receipts.single().issueCodes.contains("EMPTY_REPORT"))
            assertFalse(events.contains(ProviderEvent.Completed))
        }
    }

    @Test fun cancellationRemainsCancellationWithoutPublishingAReplacement() = runTest {
        val initial = session {
            emit(ProviderEvent.TextDelta(original))
            emit(ProviderEvent.Completed)
        }
        val cancelled = session { throw CancellationException("User stopped the run") }
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val events = mutableListOf<ProviderEvent>()
        val delivery = ResearchDeliverySession(initial, { packet }, saveReceipt = { receipts += it }) { _, repair -> if (repair) cancelled else initial }
        val failure = runCatching { delivery.streamRound(emptyList(), emptyList()).collect { events += it } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(receipts.isEmpty())
        assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
    }
}
