package dev.chungjungsoo.gptmobile.data.agent.tool

import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpInteractionScopeTest {
    @Test fun anExpiredInputCannotAcceptAnAnswerEvenIfStillVisible() = runBlocking {
        val interactions = McpInteractions()
        val response = kotlinx.coroutines.CompletableDeferred<io.modelcontextprotocol.kotlin.sdk.types.ElicitResult>()
        val request = McpInputRequest("expired", "server", "Choice", kotlinx.serialization.json.JsonObject(emptyMap()), response, "run", deadlineNanos = System.nanoTime() - 1)
        interactions.pending.value = listOf(request)
        interactions.respond("expired", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(io.modelcontextprotocol.kotlin.sdk.types.ElicitResult.Action.Cancel, response.await().action)
    }

    @Test fun simultaneousRunInputsKeepTheirOriginAndAnswersSeparate() = runBlocking {
        withTimeout(5000) {
            val interactions = McpInteractions()
            val request = Json.decodeFromString<ElicitRequest>("""{"method":"elicitation/create","params":{"message":"Choose a layout","requestedSchema":{"type":"object","properties":{"layout":{"type":"string","enum":["Compact","Spacious"]}},"required":["layout"]}}}""")
            val first = async(Dispatchers.IO) { interactions.request("same-server", request, "run-one") }
            val second = async(Dispatchers.IO) { interactions.request("same-server", request, "run-two") }
            val pending = interactions.pending.first { it.size == 2 }
            assertEquals(setOf("run-one", "run-two"), pending.map { it.runId }.toSet())
            interactions.respond(pending.single { it.runId == "run-one" }.id, null)
            first.await()
            assertTrue(second.isActive)
            assertEquals("run-two", interactions.pending.value.single().runId)
            interactions.respond(pending.single { it.runId == "run-two" }.id, null)
            second.await()
            assertTrue(interactions.pending.value.isEmpty())
        }
    }
}
