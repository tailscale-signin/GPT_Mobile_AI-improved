package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextMarkupGuardTest {
    @Test fun shortOrdinaryTextStreamsBeforeTheProviderFinishes() = runBlocking {
        val events = withTimeout(1000) {
            flow {
                emit(ProviderEvent.TextDelta("partial"))
                awaitCancellation()
            }.withAssistantTextMarkupGuard().take(1).toList()
        }
        assertEquals(listOf(ProviderEvent.TextDelta("partial")), events)
    }

    @Test fun searchClaimIsWithheldAtEveryChunkBoundary() = runBlocking {
        for (claim in listOf("I searched the web", "We have checked current sources", "Our internet search")) {
            for (split in 1 until claim.length) {
                val events = flowOf(
                    ProviderEvent.TextDelta("Answer. " + claim.take(split)),
                    ProviderEvent.TextDelta(claim.drop(split) + " and found results."),
                    ProviderEvent.Completed
                ).withAssistantTextMarkupGuard().toList()
                assertEquals("Answer. ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
                assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("no successful web-search"))
            }
        }
    }

    @Test fun assistantToolLineIsWithheldAtEveryChunkBoundary() = runBlocking {
        val markup = "assistant  to=browser.search {\"query\":\"private\"}"
        for (split in 1 until markup.length) {
            val events = flowOf(
                ProviderEvent.TextDelta("Answer.\n  " + markup.take(split)),
                ProviderEvent.TextDelta(markup.drop(split)),
                ProviderEvent.Completed
            ).withAssistantTextMarkupGuard().toList()
            assertEquals("Answer.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }.trimEnd())
            assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("unparsed tool-call markup"))
        }
    }

    @Test fun ordinaryWordsKeepTheirBoundariesAcrossChunks() = runBlocking {
        for (parts in listOf(listOf("h", "i searched the web"), listOf("The ", "assistant to=search {} example"), listOf("I", "celand"), listOf("a"), listOf("assistant"))) {
            val events = flow {
                parts.forEach { emit(ProviderEvent.TextDelta(it)) }
                emit(ProviderEvent.Completed)
            }.withAssistantTextMarkupGuard().toList()
            assertEquals(parts.joinToString(""), events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(events.filterIsInstance<ProviderEvent.Failed>().isEmpty())
        }
    }

    @Test fun controlLinesAfterSplitLineEndingsAreWithheld() = runBlocking {
        for (newline in listOf("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
            val events = flowOf(
                ProviderEvent.TextDelta("Answer.$newline"),
                ProviderEvent.TextDelta("assistant to=search {\"query\":\"private\"}"),
                ProviderEvent.Completed
            ).withAssistantTextMarkupGuard().toList()
            assertEquals("Answer.$newline", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("unparsed tool-call markup"))
        }
    }

    @Test fun splitRawToolMarkupIsWithheldBeforeItReachesTheConsumer() = runBlocking {
        val events = flowOf(
            ProviderEvent.TextDelta("A useful sentence. <tool_"),
            ProviderEvent.TextDelta("call name=browser.search>{\"query\":\"private\"}</tool_call> leaked payload"),
            ProviderEvent.Completed
        ).withAssistantTextMarkupGuard().toList()

        assertEquals("A useful sentence. ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("unparsed tool-call markup"))
        assertFalse(events.filterIsInstance<ProviderEvent.TextDelta>().any { "private" in it.text || "leaked" in it.text })
    }

    @Test fun ordinaryTextAndSeparateStructuredToolCallsPassThrough() = runBlocking {
        val tool = ProviderEvent.ToolCall("call-1", "search", kotlinx.serialization.json.buildJsonObject {})
        val events = flowOf(ProviderEvent.TextDelta("The word invoke is ordinary prose."), tool, ProviderEvent.Completed)
            .withAssistantTextMarkupGuard().toList()

        assertEquals("The word invoke is ordinary prose.", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(events.any { it === tool })
        assertTrue(events.any { it == ProviderEvent.Completed })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().isEmpty())
    }

    @Test fun gemmaToolCallControlTokenIsWithheldWhenNoAuthorizedExecutorHandlesIt() = runBlocking {
        val events = flowOf(
            ProviderEvent.TextDelta("<|tool_call>call:web_search{query:latest news}"),
            ProviderEvent.Completed
        ).withAssistantTextMarkupGuard().toList()

        assertTrue(events.none { it is ProviderEvent.TextDelta })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("unparsed tool-call markup"))
    }

    @Test fun webSearchClaimsWithoutSuccessfulSearchEvidenceAreWithheld() = runBlocking {
        val events = flowOf(
            ProviderEvent.TextDelta("I searched the web and found three headlines."),
            ProviderEvent.Completed
        ).withAssistantTextMarkupGuard().toList()

        assertTrue(events.none { it is ProviderEvent.TextDelta })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("no successful web-search tool result"))
    }

    @Test fun successfulSearchResultAuthorizesSearchClaim() = runBlocking {
        val call = ProviderEvent.ToolCall("search-1", "web_search", kotlinx.serialization.json.buildJsonObject {})
        val result = dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(
            callId = call.callId,
            content = dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text("Three headlines"),
            isError = false
        )
        val events = flowOf(
            ProviderEvent.ToolResult(call, result),
            ProviderEvent.TextDelta("I searched the web and found three headlines."),
            ProviderEvent.Completed
        ).withAssistantTextMarkupGuard(searchToolNames = setOf(call.name)).toList()

        assertEquals("I searched the web and found three headlines.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().isEmpty())
    }

    @Test fun trustedPreviousRoundSearchAuthorizesNextRoundClaim() = runBlocking {
        val events = flowOf(ProviderEvent.TextDelta("I searched the web and found evidence."), ProviderEvent.Completed)
            .withAssistantTextMarkupGuard(initialSuccessfulSearch = true).toList()
        assertTrue(events.none { it is ProviderEvent.Failed })
        assertTrue(events.any { it is ProviderEvent.TextDelta })
    }

    @Test fun toolNameAloneCannotAuthorizeSearchClaim() = runBlocking {
        val call = ProviderEvent.ToolCall("s", "web_search", kotlinx.serialization.json.buildJsonObject {})
        val result = AgentToolResult("s", ToolResultContent.Text("data"), false)
        val events = flowOf(ProviderEvent.ToolResult(call, result), ProviderEvent.TextDelta("I searched the web."), ProviderEvent.Completed)
            .withAssistantTextMarkupGuard().toList()
        assertTrue(events.any { it is ProviderEvent.Failed })
    }
}
