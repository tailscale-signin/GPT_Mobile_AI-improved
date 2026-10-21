package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchDeliveryBoundaryTest {
    private val source = ResearchSource(
        "S1",
        "https://example.org/trial",
        "Sleep trial",
        "Read",
        "The randomized trial enrolled 20 participants. Scores were 9 versus 14 points. Follow-up was short."
    )
    private val packet = ResearchDeliveryPacket.build(ResearchSnapshot("sleep trial", sources = listOf(source)), ResearchReportDetail.BRIEF)
    private val good = "The randomized trial enrolled 20 participants. [S1]"
    private val bad = "The trial cured 999 participants. [S1]"
    private val report = "Partial coverage. Independent review is not available.\n\n$good\n\n## References\n\n[S1] ${source.url}"
    private val invalidReport = report.replace("\n\n## References", "\n\n$bad\n\n## References")

    private fun session(block: suspend FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(block)
    }

    @Test fun consentRevokedAfterBufferedCompletionCannotPublishTextOrReceipt() = runTest {
        var allowed = true
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val writer = session {
            emit(ProviderEvent.TextDelta(report))
            allowed = false
            emit(ProviderEvent.Completed)
        }
        val delivery = ResearchDeliverySession(
            writer,
            { packet },
            saveReceipt = { receipts += it },
            validateAccess = { check(allowed) { "DOCUMENT_RECALL_STALE: consent changed" } }
        ) { _, _ -> writer }
        val events = mutableListOf<ProviderEvent>()
        val error = runCatching { delivery.streamRound(emptyList(), emptyList()).collect { events += it } }.exceptionOrNull()
        assertEquals("DOCUMENT_RECALL_STALE: consent changed", error?.message)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        assertTrue(receipts.isEmpty())
    }

    @Test fun revocationDuringRepairCannotExposePreservedParagraphsOrEvidenceFallback() = runTest {
        var allowed = true
        val initial = session {
            emit(ProviderEvent.TextDelta(invalidReport))
            emit(ProviderEvent.Completed)
        }
        val correction = session {
            emit(ProviderEvent.TextDelta("{\"replacements\":[],\"append\":\"\"}"))
            allowed = false
            emit(ProviderEvent.Completed)
        }
        val delivery = ResearchDeliverySession(
            initial,
            { packet },
            validateAccess = { check(allowed) { "PROFILE_CONFIGURATION_CHANGED: endpoint changed" } }
        ) { _, repair -> if (repair) correction else initial }
        val events = mutableListOf<ProviderEvent>()
        val error = runCatching { delivery.streamRound(emptyList(), emptyList()).collect { events += it } }.exceptionOrNull()
        assertEquals("PROFILE_CONFIGURATION_CHANGED: endpoint changed", error?.message)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        assertFalse(events.filterIsInstance<ProviderEvent.Notice>().any { it.researchDelivery != null })
    }

    @Test fun typedAuthorizationCodesAbortRepairEvenWhenTheMessageHasNoCode() = runTest {
        for (code in listOf("DOCUMENT_RECALL_STALE", "PROFILE_CONFIGURATION_CHANGED", "PROFILE_REMOVED", "PROFILE_DISABLED", "PROFILE_IDENTITY_AMBIGUOUS", "REQUEST_IDENTITY_CHANGED")) {
            val initial = session {
                emit(ProviderEvent.TextDelta(invalidReport))
                emit(ProviderEvent.Completed)
            }
            val denied = session { emit(ProviderEvent.Failed("The retained destination is no longer authorized.", errorCode = code)) }
            val delivery = ResearchDeliverySession(initial, { packet }) { _, repair -> if (repair) denied else initial }
            val events = delivery.streamRound(emptyList(), emptyList()).toList()
            assertEquals(code, events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
            assertFalse(events.filterIsInstance<ProviderEvent.Notice>().any { it.researchDelivery != null })
        }
    }

    @Test fun wrappedSecurityDenialIsNotConvertedToAnEvidenceFallback() = runTest {
        val initial = session {
            emit(ProviderEvent.TextDelta(invalidReport))
            emit(ProviderEvent.Completed)
        }
        val denial = IllegalStateException("Dispatch rejected", SecurityException("Destination changed"))
        val delivery = ResearchDeliverySession(initial, { packet }) { _, repair -> if (repair) throw denial else initial }
        val events = mutableListOf<ProviderEvent>()
        val error = runCatching { delivery.streamRound(emptyList(), emptyList()).collect { events += it } }.exceptionOrNull()
        assertTrue(error === denial)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
    }

    @Test fun failedRepairRetainsExactValidParagraphBytesAndRejectsTheReport() = runTest {
        val preserved = "The randomized trial enrolled 20 participants.\n  Follow-up was short. [S1]"
        val initial = session {
            emit(ProviderEvent.TextDelta("$preserved\n\n$bad\n\nPartial coverage. Independent review is not available."))
            emit(ProviderEvent.Completed)
        }
        val interrupted = session {
            emit(ProviderEvent.TextDelta("{\"replacements\":["))
            emit(ProviderEvent.Failed("DNS lookup failed", transportComplete = false, errorCode = "DNS_FAILURE"))
        }
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val delivery = ResearchDeliverySession(initial, { packet }, saveReceipt = { receipts += it }) { _, repair -> if (repair) interrupted else initial }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains(preserved))
        assertFalse(text.contains(bad))
        assertFalse(text.contains("{\"replacements\""))
        assertTrue(text.contains("Unresolved delivery checks"))
        assertTrue(text.contains("Saved evidence and remaining gaps"))
        assertEquals(ResearchReportValidity.EVIDENCE_ONLY, receipts.single().reportValidity)
        assertFalse(receipts.single().accepted)
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun receiptRechecksAccessAfterPublicationAndBeforeItsDurableWrite() = runTest {
        var allowed = true
        val receipts = mutableListOf<ResearchDeliveryReceipt>()
        val writer = session {
            emit(ProviderEvent.TextDelta(report))
            emit(ProviderEvent.Completed)
        }
        val delivery = ResearchDeliverySession(
            writer,
            { packet },
            saveReceipt = { receipts += it },
            validateAccess = { check(allowed) { "SOURCE_PERMISSION_REVOKED" } }
        ) { _, _ -> writer }
        val events = mutableListOf<ProviderEvent>()
        val error = runCatching {
            delivery.streamRound(emptyList(), emptyList()).collect { event ->
                events += event
                if (event is ProviderEvent.TextDelta) allowed = false
            }
        }.exceptionOrNull()
        assertEquals("SOURCE_PERMISSION_REVOKED", error?.message)
        assertTrue(receipts.isEmpty())
        assertFalse(events.filterIsInstance<ProviderEvent.Notice>().any { it.researchDelivery?.accepted == true })
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun initialWrapperPersistsAcrossToolRoundsWhileThePacketIsRefreshed() = runTest {
        var rounds = 0
        var prepared = 0
        var created = 0
        var packetReads = 0
        val call = ProviderEvent.ToolCall("read", "read_url", JsonObject(emptyMap()))
        val writer = session {
            rounds++
            if (rounds == 1) emit(call) else emit(ProviderEvent.TextDelta(report))
            emit(ProviderEvent.Completed)
        }
        val delivery = ResearchDeliverySession(
            writer,
            {
                packetReads++
                packet
            },
            prepareInitial = { raw, instruction ->
                prepared++
                assertTrue(instruction.contains("Untrusted research packet"))
                raw
            }
        ) { _, _ ->
            created++
            writer
        }
        val first = delivery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(call), first.filterIsInstance<ProviderEvent.ToolCall>())
        val exchange = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Saved passage"), false)))
        val second = delivery.streamRound(emptyList(), listOf(exchange)).toList()
        assertEquals(1, created)
        assertEquals(1, prepared)
        assertEquals(2, rounds)
        assertEquals(2, packetReads)
        assertTrue(second.filterIsInstance<ProviderEvent.Notice>().single { it.researchDelivery != null }.researchDelivery!!.accepted)
    }

    @Test fun ordinaryChatAlsoPreparesTheInitialWrapperOnlyOnce() = runTest {
        var prepared = 0
        var rounds = 0
        val base = session {
            rounds++
            emit(ProviderEvent.TextDelta("Hello"))
            emit(ProviderEvent.Completed)
        }
        val delivery = ResearchDeliverySession(base, { null }, prepareInitial = { raw, instruction ->
            prepared++
            assertEquals("", instruction)
            raw
        }) { _, _ -> error("Ordinary chat must use its existing provider session") }
        repeat(2) { delivery.streamRound(emptyList(), emptyList()).toList() }
        assertEquals(1, prepared)
        assertEquals(2, rounds)
    }
}
