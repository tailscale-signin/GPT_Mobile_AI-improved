package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubContinuationInvocationTest {
    private val limited = ProviderEvent.Failed("The response reached its output limit.", transportComplete = true)
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }
    private fun tool(name: String, action: suspend (String, JsonObject) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Authorized fixture", buildJsonObject { put("type", "object") })
        override suspend fun execute(callId: String, arguments: JsonObject) = action(callId, arguments)
    }
    private fun fingerprint(call: ProviderEvent.ToolCall) = GitHubEvidenceBook.digest("${call.callId}:${call.name}:${call.arguments}")

    @Test fun authorizedAliasAndCompactedArgumentsResolveTheOriginalHandoff() = runBlocking {
        // Fit the 16 KiB admission limit while still exceeding the 512-token replay allowance.
        val call = ProviderEvent.ToolCall("read", "github_read", buildJsonObject { put("path", "original-path-".repeat(600)) }, originalInvocationFingerprint = "untrusted-provider-value")
        assertTrue("The fixture must reach the authorized tool before replay compaction.", ToolRoundAdmission().admit(call))
        var executions = 0
        var reopens = 0
        val read = tool("github_read__fixture") { id, arguments ->
            executions++
            assertEquals(call.arguments, arguments)
            AgentToolResult(id, ToolResultContent.Text("Confirmed read receipt"), false)
        }
        val continuation = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Saved prefix."), limited), maxContinuations = 1) { _, exchanges, _ ->
            if (++reopens == 1) {
                session(call, ProviderEvent.Completed)
            } else {
                val delivered = exchanges.single().calls.single()
                assertEquals(read.definition.name, delivered.name)
                assertTrue(delivered.arguments.isEmpty())
                assertEquals(fingerprint(call), delivered.originalInvocationFingerprint)
                session(ProviderEvent.TextDelta(" Confirmed suffix."), ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, maxReplayTokens = 512, maxReplayResultTokens = 128)).run(continuation, listOf(read)).toList()
        assertEquals(1, executions)
        assertEquals(2, reopens)
        assertTrue(events.none { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertEquals("Saved prefix. Confirmed suffix.", events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { (it.event as? ProviderEvent.TextDelta)?.text }.joinToString(""))
    }

    @Test fun restoredAliasedInvocationKeepsUnknownDurableOutcomePendingDespiteStaleCompactedSuccess() = runBlocking {
        val original = ProviderEvent.ToolCall(
            "write",
            "github_commit",
            buildJsonObject { put("message", "Prepared change") },
            originalInvocationFingerprint = "f".repeat(64)
        )
        val definition = AgentToolDefinition("github_commit__fixture", "Authorized fixture", buildJsonObject { put("type", "object") })
        var checkpoint: JsonObject? = null
        GitHubOutputContinuationSession(session(original, ProviderEvent.Completed), saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("Unexpected continuation") }
            .streamRound(listOf(definition), emptyList()).toList()
        assertEquals(JsonArray(listOf(JsonPrimitive(fingerprint(original)))), checkpoint?.get("pending_calls"))

        val authorized = original.copy(name = definition.name, originalInvocationFingerprint = fingerprint(original))
        val batch = ToolBatchCheckpoint(null, "authorized-alias")
        batch.enqueue(listOf(authorized))
        batch.complete(authorized, AgentToolResult(authorized.callId, ToolResultContent.Text("Outcome unknown"), true, errorCode = "TOOL_OUTCOME_UNKNOWN"))
        val compacted = authorized.copy(arguments = JsonObject(emptyMap()))
        val stale = AgentToolExchange(listOf(compacted), listOf(AgentToolResult(compacted.callId, ToolResultContent.Text("Stale success"), false)))
        var modelRequests = 0
        val selected = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                modelRequests++
                emit(ProviderEvent.TextDelta("Must remain blocked"))
                emit(ProviderEvent.Completed)
            }
        }
        val resumed = GitHubOutputContinuationSession(
            selected,
            durableToolReceipts = batch::handoffReceipts,
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> selected }.streamRound(listOf(definition), listOf(stale)).toList()
        assertEquals(0, modelRequests)
        assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", (resumed.single() as ProviderEvent.Failed).errorCode)
        assertEquals(JsonPrimitive("TOOL_HANDOFF"), checkpoint?.get("state"))
        assertEquals(JsonArray(listOf(JsonPrimitive(fingerprint(original)))), checkpoint?.get("pending_calls"))
    }

    @Test fun authorizedRecoveryRecipeRetainsItsOriginalInvocationAcrossRunnerRounds() = runBlocking {
        val recipe = buildJsonObject {
            put("tool", "github_search")
            put("arguments", buildJsonObject { put("view_cursor", "issued:4") })
        }
        val first = ProviderEvent.ToolCall("prior", "github_read", JsonObject(emptyMap()))
        val recover = ProviderEvent.ToolCall("recover", "github_read", buildJsonObject { put("correction_call", recipe) })
        var reads = 0
        var searches = 0
        val read = tool("github_read") { id, _ ->
            reads++
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.arguments.v1")
                        put("correction_call", recipe)
                    }
                ),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        }
        val search = tool("github_search") { id, arguments ->
            searches++
            assertEquals(recipe["arguments"], arguments)
            AgentToolResult(id, ToolResultContent.Text("Confirmed recovered read"), false)
        }
        var rounds = 0
        var reopens = 0
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
                if (rounds++ == 0) flowOf(first, ProviderEvent.Completed) else flowOf(ProviderEvent.TextDelta("Saved findings."), limited)
        }
        val continuation = GitHubOutputContinuationSession(source, maxContinuations = 1) { _, exchanges, _ ->
            if (++reopens == 1) {
                session(recover, ProviderEvent.Completed)
            } else {
                val delivered = exchanges.flatMap { it.calls }.single { it.callId == recover.callId }
                assertEquals("github_search", delivered.name)
                assertEquals(recipe["arguments"], delivered.arguments)
                assertEquals(fingerprint(recover), delivered.originalInvocationFingerprint)
                session(ProviderEvent.TextDelta(" Finished from the receipt."), ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(continuation, listOf(read, search)).toList()
        assertEquals(1, reads)
        assertEquals(1, searches)
        assertEquals(2, reopens)
        assertTrue(events.none { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertEquals("Saved findings. Finished from the receipt.", events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { (it.event as? ProviderEvent.TextDelta)?.text }.joinToString(""))
    }
}
