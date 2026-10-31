package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedFeedbackDeliveryTest {
    private fun writer(text: String, outputTokens: Int? = null) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            emit(ProviderEvent.TextDelta(text))
            if (outputTokens != null) emit(ProviderEvent.Usage(outputTokens = outputTokens))
            emit(ProviderEvent.Completed)
        }
    }

    @Test fun providerOverrunCannotPublishCheckedAnswerEvenAfterEndRecord() = runTest {
        val session = CombinedDeliverySession(
            writer("A brief answer.", 8956),
            emptyList(),
            10000,
            validateAccess = {},
            outputTokenAllowance = 5708,
            maxRepairRounds = 0
        ) { _, _ -> error("No extra generation authorized") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertFalse(events.any { it is ProviderEvent.CombinedAnswer && it.report.complete })
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.last() == ProviderEvent.Completed)
        assertTrue(events.filterIsInstance<ProviderEvent.Usage>().any { it.outputTokens == 8956 })
    }

    @Test fun unresolvedRequestCheckBlocksFinalPublication() = runTest {
        val session = CombinedDeliverySession(
            writer("An answer with no list."),
            emptyList(),
            10000,
            validateAccess = {},
            answerContract = CombinedAnswerPolicy(requiredFormat = "BULLETS"),
            maxRepairRounds = 0
        ) { _, _ -> error("No extra generation authorized") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertFalse(events.any { it is ProviderEvent.CombinedAnswer })
        assertTrue(events.combinedReport().correctionRequired || !events.combinedReport().complete)
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun duplicateProseIsRemovedBeforeFinalAssessment() = runTest {
        val paragraph = "The dough is folded around its filling and sealed before cooking."
        val session = CombinedDeliverySession(
            writer("$paragraph\n\n$paragraph"),
            emptyList(),
            10000,
            validateAccess = {},
            maxRepairRounds = 0
        ) { _, _ -> error("No repair needed") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(paragraph, events.combinedAnswerText())
        assertTrue(events.combinedReport().complete)
    }

    @Test fun shorteningStructuredTextCannotEraseProtocolFailure() = runTest {
        val wire = """{"type":"passage","text":"A short answer with several extra words.","contributors":[],"claims":[],"evidence":[]}
            |{"type":"invalid"}
        """.trimMargin()
        val session = CombinedDeliverySession(
            writer(wire),
            emptyList(),
            10000,
            validateAccess = {},
            structured = true,
            recordProtocol = true,
            answerContract = CombinedAnswerPolicy(maximumWords = 3),
            maxRepairRounds = 0
        ) { _, _ -> error("No repair authorized") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.combinedReport().wordCount <= 3)
        assertTrue("INVALID_DELIVERY_RECORD" in events.combinedReport().issues)
        assertFalse(events.combinedReport().protocolComplete)
        assertFalse(events.any { it is ProviderEvent.CombinedAnswer })
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun sourceStatusGateDistinguishesGlobalFailureFromOneContributor() {
        val readable = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/article", passage = "A retained passage.", retrieval = "READABLE_PASSAGE")))
        fun conflicts(text: String, evidence: CombinedEvidenceLedger) = CombinedFinalCheck.check(text, emptyList(), evidence, CombinedAnswerPolicy()).checks.any { it.id == "SOURCE_STATUS_CONTRADICTS_EVIDENCE" }
        assertTrue(conflicts("No live sources were retrievable.", readable))
        assertFalse(conflicts("Profile Alpha could not retrieve any live sources. Profile Beta retrieved a readable passage.", readable))
        assertFalse(conflicts("No live sources were retrievable.", CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/article")))))
    }
}
