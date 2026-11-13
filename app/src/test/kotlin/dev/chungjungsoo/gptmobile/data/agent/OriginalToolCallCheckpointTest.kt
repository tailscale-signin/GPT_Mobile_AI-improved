package dev.chungjungsoo.gptmobile.data.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalToolCallCheckpointTest {
    @Test fun originalArgumentsRemainInTheContinuationTokenBudget() {
        val canonical = ProviderEvent.ToolCall("call", "read", JsonObject(emptyMap()))
        val original = canonical.copy(originalProviderName = "read", originalProviderArguments = buildJsonObject { put("query", "preserved long query ".repeat(300)) })
        assertTrue(ToolExchangeCompactor.estimateTokens(listOf(AgentToolExchange(listOf(original), emptyList()))) > ToolExchangeCompactor.estimateTokens(listOf(AgentToolExchange(listOf(canonical), emptyList()))))
    }

    @Test fun futureJournalVersionIsRejected() = runBlocking {
        val file = File.createTempFile("future-call", ".json")
        try {
            file.writeText("""{"schemaVersion":3,"binding":"bound","pending":[],"entries":{}}""")
            assertTrue(runCatching { ToolBatchCheckpoint(file, "bound").pending() }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun completedCallRestoresOriginalArgumentsAndNeverReplays() = runBlocking {
        val file = File.createTempFile("original-call", ".json").apply { delete() }
        try {
            val call = ProviderEvent.ToolCall("call", "github_ci__github_api", buildJsonObject { put("action", "workflows") }, originalProviderName = "github_ci__github_api", originalProviderArguments = JsonObject(emptyMap()))
            val checkpoint = ToolBatchCheckpoint(file, "bound")
            checkpoint.enqueue(listOf(call))
            var dispatched = 0
            checkpoint.execute(call) {
                dispatched++
                AgentToolResult(call.callId, ToolResultContent.Text("retained"), false)
            }
            val restored = ToolBatchCheckpoint(file, "bound")
            val replay = restored.recoverySnapshot().completedExchanges.single().calls.single()
            assertEquals(call, replay)
            assertEquals(JsonObject(emptyMap()), replay.providerIdentity().arguments)
            assertEquals(JsonPrimitive("workflows"), replay.arguments["action"])
            assertTrue(restored.execute(replay) { error("A completed call must not execute again") }.sharedResult)
            assertEquals(1, dispatched)
            assertEquals(JsonPrimitive(2), Json.parseToJsonElement(file.readText()).jsonObject["schemaVersion"])
            assertTrue(runCatching { restored.execute(replay.copy(originalProviderArguments = buildJsonObject { put("action", "cancel") })) { error("Changed identity cannot execute") } }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun legacyCheckpointRemainsReadableWithoutInventingOriginalIdentity() = runBlocking {
        val file = File.createTempFile("legacy-call", ".json")
        try {
            file.writeText("""{"schemaVersion":1,"binding":"bound","pending":["call"],"entries":{"call":{"call":{"id":"call","name":"read","arguments":{}}}}}""")
            val checkpoint = ToolBatchCheckpoint(file, "bound")
            val call = checkpoint.pending().single()
            assertNull(call.originalProviderName)
            assertEquals(call.arguments, call.providerIdentity().arguments)
            checkpoint.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("saved"), false) }
            assertEquals(JsonPrimitive(2), Json.parseToJsonElement(file.readText()).jsonObject["schemaVersion"])
        } finally {
            file.delete()
        }
    }

    @Test fun originalIdentityMustBeComplete() {
        val broken = ProviderEvent.ToolCall("call", "read", JsonObject(emptyMap()), originalProviderName = "read")
        assertTrue(runCatching { broken.providerIdentity() }.isFailure)
        assertFalse(runCatching { broken.copy(originalProviderArguments = JsonObject(emptyMap())).providerIdentity() }.isFailure)
    }
}
