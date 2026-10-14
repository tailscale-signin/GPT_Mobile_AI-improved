package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedCorpusMultipassSessionTest {
    private val settings = TokenBudgetSettings(contextTokens = 4096, outputTokens = 128)
    private val frozen = listOf(
        ConversationTurn(MessageV2(content = "Historical facts ".repeat(650), platformType = null), null, false),
        ConversationTurn(MessageV2(content = "{\"original_request\":\"Include each fact\"}", platformType = null), null, true)
    )

    @Test fun samePrimaryInspectsAllSpansBeforeAnyFinalAnswer() = runTest {
        val plan = CombinedCorpusMultipass.plan(frozen, "", settings)
        assertTrue(plan.passes.size > 1)
        val opened = mutableListOf<String>()
        var finalTurns: List<ConversationTurn>? = null
        val session = CombinedCorpusMultipassSession(
            plan = plan,
            system = "",
            limits = settings,
            open = { turns, kind, cap ->
                opened += kind
                if (kind == "combined_corpus_inspection") {
                    assertEquals(plan.inspectionOutputTokens, cap)
                    assertTrue(turns.single().userMessage.content.contains("BEGIN FROZEN SOURCE"))
                } else {
                    assertEquals("synthesis_after_full_corpus", kind)
                    assertEquals(null, cap)
                }
                object : AgentProviderSession {
                    override fun streamRound(
                        tools: List<AgentToolDefinition>,
                        exchanges: List<AgentToolExchange>
                    ): Flow<ProviderEvent> = if (kind == "combined_corpus_inspection") {
                        flowOf(ProviderEvent.TextDelta("Supported source passage ${opened.size}"), ProviderEvent.Completed)
                    } else {
                        flowOf(ProviderEvent.TextDelta("FINAL ANSWER"), ProviderEvent.Completed)
                    }
                }
            },
            onFinalContext = { finalTurns = it }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(plan.passes.size, opened.count { it == "combined_corpus_inspection" })
        assertEquals("synthesis_after_full_corpus", opened.last())
        assertEquals("FINAL ANSWER", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue(finalTurns!!.single().userMessage.content.contains("Supported source passage"))
    }

    @Test fun permissionFailureStopsInspectionBeforeFinalRequest() = runTest {
        val plan = CombinedCorpusMultipass.plan(frozen, "", settings)
        val opened = mutableListOf<String>()
        val session = CombinedCorpusMultipassSession(
            plan,
            "",
            settings,
            open = { _, kind, _ ->
                opened += kind
                object : AgentProviderSession {
                    override fun streamRound(
                        tools: List<AgentToolDefinition>,
                        exchanges: List<AgentToolExchange>
                    ): Flow<ProviderEvent> =
                        flowOf(ProviderEvent.Failed("SOURCE_PERMISSION_REVOKED"), ProviderEvent.Completed)
                }
            },
            onFinalContext = { error("Final must not be admitted") }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("combined_corpus_inspection"), opened)
        assertTrue(events.last() is ProviderEvent.Failed)
        assertTrue((events.last() as ProviderEvent.Failed).message.contains("SOURCE_PERMISSION_REVOKED"))
        assertFalse(events.any { it is ProviderEvent.Completed || it is ProviderEvent.TextDelta })
    }

    @Test fun missingCompletionIsNeverCreditedAsAValidInspection() = runTest {
        val plan = CombinedCorpusMultipass.plan(frozen, "", settings)
        var physicalCalls = 0
        val session = CombinedCorpusMultipassSession(
            plan,
            "",
            settings,
            open = { _, _, _ ->
                physicalCalls++
                object : AgentProviderSession {
                    override fun streamRound(
                        tools: List<AgentToolDefinition>,
                        exchanges: List<AgentToolExchange>
                    ): Flow<ProviderEvent> = flowOf(ProviderEvent.TextDelta("Unfinished"))
                }
            },
            onFinalContext = { error("No completed inspection") }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, physicalCalls)
        assertTrue((events.last() as ProviderEvent.Failed).message.contains("COMBINED_MULTIPASS_INCOMPLETE"))
    }
}
