package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionUsage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptContext
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptStore
import dev.chungjungsoo.gptmobile.data.openrouter.account.recordOpenRouterReceipts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterReceiptsTest {
    private fun context(connection: String = "a") = OpenRouterReceiptContext(connection, "revision", "https://openrouter.ai/api/v1", "profile", "fixture/model", "primary")

    @Test fun finalUsageWithoutChoicesRecordsExactCostAndDeduplicatesGeneration() = runBlocking {
        val store = OpenRouterReceiptStore(InMemoryRouterPreferences())
        val source = flowOf(ChatCompletionChunk(id = "gen-fixture", choices = listOf(Choice(finishReason = "stop"))), ChatCompletionChunk(id = "gen-fixture", choices = emptyList(), usage = ChatCompletionUsage(promptTokens = 2, completionTokens = 3, cost = JsonPrimitive("0.00000001"))))
        source.recordOpenRouterReceipts(context(), store).toList()
        source.recordOpenRouterReceipts(context(), store).toList()
        val records = store.observe("a").first()
        assertEquals(1, records.size)
        assertEquals("0.00000001", records.single().costUsd)
        assertEquals(3, records.single().outputTokens)
    }

    @Test fun interruptedStreamsRemainPendingCostAndSeparateConnections() = runBlocking {
        val store = OpenRouterReceiptStore(InMemoryRouterPreferences())
        try {
            flow {
                emit(ChatCompletionChunk(id = "gen-fixture"))
                throw CancellationException("fixture")
            }.recordOpenRouterReceipts(context(), store).toList()
        } catch (_: CancellationException) { }
        flowOf(ChatCompletionChunk(id = "gen-fixture", usage = ChatCompletionUsage(cost = JsonPrimitive(0)))).recordOpenRouterReceipts(context("b"), store).toList()
        assertNull(store.observe("a").first().single().costUsd)
        assertEquals("cancelled", store.observe("a").first().single().status)
        assertEquals("0", store.observe("b").first().single().costUsd)
    }

    @Test fun distinctRetryGenerationsAreNeverCollapsed() = runBlocking {
        val store = OpenRouterReceiptStore(InMemoryRouterPreferences())
        flowOf(ChatCompletionChunk(id = "gen-first", usage = ChatCompletionUsage(cost = JsonPrimitive(1))), ChatCompletionChunk(id = "gen-second", usage = ChatCompletionUsage(cost = JsonPrimitive(2)))).recordOpenRouterReceipts(context(), store).toList()
        assertEquals(2, store.observe("a").first().size)
    }
}
