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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OriginalToolCallCheckpointTest {
    @Test fun originalArgumentsRemainInTheContinuationTokenBudget() {
        val canonical = ProviderEvent.ToolCall("call", "read", JsonObject(emptyMap()))
        val original = canonical.copy(arguments = buildJsonObject { put("query", "preserved long query ".repeat(300)) }).captureProviderInvocation().copy(arguments = canonical.arguments)
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
            val call = ProviderEvent.ToolCall("call", "github_ci__github_api", JsonObject(emptyMap())).captureProviderInvocation().copy(arguments = buildJsonObject { put("action", "workflows") })
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
            assertEquals(JsonObject(emptyMap()), replay.providerInvocation().arguments)
            assertEquals(JsonPrimitive("workflows"), replay.arguments["action"])
            assertTrue(restored.execute(replay) { error("A completed call must not execute again") }.sharedResult)
            assertEquals(1, dispatched)
            assertEquals(JsonPrimitive(2), Json.parseToJsonElement(file.readText()).jsonObject["schemaVersion"])
            assertTrue(runCatching { restored.execute(replay.copy(originalInvocation = ProviderToolInvocation(replay.name, buildJsonObject { put("action", "cancel") }))) { error("Changed identity cannot execute") } }.isFailure)
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
            assertNull(call.originalInvocation)
            assertEquals(call.arguments, call.providerInvocation().arguments)
            checkpoint.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("saved"), false) }
            assertEquals(JsonPrimitive(2), Json.parseToJsonElement(file.readText()).jsonObject["schemaVersion"])
        } finally {
            file.delete()
        }
    }

    @Test fun versionTwoSplitIdentitySurvivesReloadAndDispatch() = runBlocking {
        val file = File.createTempFile("split-call", ".json")
        try {
            val original = ProviderToolInvocation("read", JsonObject(emptyMap()))
            val hash = original.fingerprint("call")
            file.writeText("""{"schemaVersion":2,"binding":"bound","pending":["call"],"entries":{"call":{"call":{"id":"call","name":"routed","arguments":{},"originalInvocationFingerprint":"$hash","originalProviderName":"read","originalProviderArguments":{}}}}}""")
            val checkpoint = ToolBatchCheckpoint(file, "bound")
            val call = checkpoint.pending().single()
            assertEquals(original, call.providerInvocation())
            checkpoint.enqueue(listOf(call))
            checkpoint.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("saved"), false) }
            val restored = ToolBatchCheckpoint(file, "bound").recoverySnapshot().completedExchanges.single()
            assertEquals(original, restored.calls.single().providerInvocation())
            assertEquals("routed", restored.calls.single().name)
        } finally {
            file.delete()
        }
    }
}
