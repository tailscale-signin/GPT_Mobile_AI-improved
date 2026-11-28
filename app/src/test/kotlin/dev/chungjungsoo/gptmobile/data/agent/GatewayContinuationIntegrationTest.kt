package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.agent.provider.toChatMessages
import dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayContinuationBinding
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayContinuationIntegrationTest {
    private val arguments = buildJsonObject { put("path", "README.md") }
    private val call = ProviderEvent.ToolCall("original-call", "github_read", arguments).captureProviderInvocation()
    private val tool = AgentToolDefinition("github_read", "Read source", JsonObject(emptyMap()))
    private val saved = Json { ignoreUnknownKeys = true }.decodeFromString<GatewayJobResult>(
        """{"job_id":"saved","status":"waiting_for_client_tool","job":{"job_id":"saved","model":"selected","pending_client_tool_call_ids":["original-call"]},"result":{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"original-call","type":"function","function":{"name":"github_read","arguments":"{\"path\":\"README.md\"}"}}]},"finish_reason":"tool_calls"}]}}"""
    )
    private fun binding() = GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun reopenedOutputHandoffDeliversOriginalCallAndErrorResultAsStructuredSuffix() = runBlocking {
        var checkpoint: JsonObject? = null
        var reopens = 0
        var submitted = false
        val narrowed = tool.copy(description = "Read the next required source")
        val output = GitHubOutputContinuationSession(
            session(ProviderEvent.TextDelta("Investigation underway."), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed),
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { _, retained, _ ->
            reopens++
            if (reopens == 1) {
                session(call, ProviderEvent.Completed)
            } else {
                val verified = binding()
                val prepared = verified.pendingReplay(retained)
                val constraints = RequestConstraints(restoredGatewayJob = verified, gatewayConversationKey = "conversation", requireGatewayConversation = true)
                    .forRole(GitHubStageBudget.CONTINUATION_ROLE)
                val wire = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                        assertEquals(listOf(narrowed), tools)
                        assertSame(verified, constraints.restoredGatewayJob)
                        assertEquals("saved", constraints.restoredGatewayJob?.jobId)
                        verified.requireResults(exchanges)
                        val messages = exchanges.flatMap { it.toChatMessages() }
                        val original = messages.first().toolCalls!!.single()
                        assertEquals("original-call", original.id)
                        assertEquals("github_read", original.function.name)
                        assertEquals(arguments.toString(), original.function.arguments)
                        assertEquals("original-call", messages.last().toolCallId)
                        assertTrue(exchanges.single().results.single().isError)
                        submitted = true
                        return flowOf(ProviderEvent.TextDelta("The missing path is the blocker."), ProviderEvent.Completed)
                    }
                }
                PreparedContinuationSession(wire, listOf(narrowed), prepared)
            }
        }
        assertEquals(listOf(call), output.streamRound(listOf(tool), emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>())
        val result = AgentToolResult(call.callId, ToolResultContent.Text("The path was not found."), true, errorCode = "GITHUB_NOT_FOUND_OR_INACCESSIBLE")
        output.observeToolResult(call, result)
        assertEquals(ProviderEvent.Completed, output.streamRound(listOf(tool), listOf(AgentToolExchange(listOf(call), listOf(result)))).toList().last())
        assertTrue(submitted)
        assertEquals(2, reopens)
    }

    @Test fun taskStagesRetainGatewayIdentityWhileHelpersCannotAttachToTheTask() = runBlocking {
        var prepared = 0
        val verified = binding()
        val constraints = RequestConstraints(
            restoredGatewayJob = verified,
            gatewayConversationKey = "conversation",
            gatewayConversationId = "remote",
            onGatewayCommandPrepared = { prepared++ },
            requireGatewayConversation = true
        )
        for (role in listOf("primary", "github-task-resume", "github-execution-continuation", GitHubStageBudget.CONTINUATION_ROLE, GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE)) {
            val stage = constraints.forRole(role)
            assertSame(verified, stage.restoredGatewayJob)
            assertEquals("conversation", stage.gatewayConversationKey)
            assertTrue(stage.requireGatewayConversation)
            requireNotNull(stage.onGatewayCommandPrepared)("command")
        }
        assertEquals(5, prepared)
        for (role in listOf("reviewer", "github-review-preflight", "github-claim-extraction", "github-progress-summary", "answer_contract_rewrite")) {
            val helper = constraints.forRole(role)
            assertNull(helper.restoredGatewayJob)
            assertNull(helper.gatewayConversationKey)
            assertNull(helper.onGatewayCommandPrepared)
            assertFalse(helper.requireGatewayConversation)
        }
    }

    @Test fun unchangedCapacityFailureDoesNotReopenAfterProcessRestart() = runBlocking {
        var checkpoint: JsonObject? = null
        var reopens = 0
        var primaryRequests = 0
        fun continuation(revision: String) = GitHubOutputContinuationSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                    primaryRequests++
                    check(primaryRequests == 1) { "A saved draft must reopen from its checkpoint, not repeat the primary request." }
                    return flowOf(ProviderEvent.TextDelta("Retained findings."), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
                }
            },
            continuationRevision = revision,
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { draft, _, _ ->
            assertEquals("Retained findings.", draft)
            reopens++
            require(false) { "CONTINUATION_CONTEXT_CAPACITY: Mandatory protocol does not fit." }
            session()
        }
        assertTrue(continuation("capacity-5632").streamRound(listOf(tool), emptyList()).toList().last() is ProviderEvent.Failed)
        assertEquals(JsonPrimitive("BLOCKED_CONTEXT_CAPACITY"), checkpoint?.get("state"))
        assertEquals(JsonPrimitive(false), checkpoint?.get("resume_allowed"))
        assertTrue(continuation("capacity-5632").streamRound(listOf(tool), emptyList()).toList().last() is ProviderEvent.Failed)
        assertEquals(1, reopens)
        continuation("capacity-8192").streamRound(listOf(tool), emptyList()).toList()
        assertEquals(2, reopens)
        assertEquals(1, primaryRequests)
        assertEquals("Retained findings.", (checkpoint?.get("draft") as? JsonPrimitive)?.content)
        assertEquals(JsonPrimitive(0), checkpoint?.get("continuations"))
    }

    @Test fun changedEvidenceReplansCapacityWithoutRepeatingThePrimaryRequestOrSpendingAnInferenceAttempt() = runBlocking {
        var checkpoint: JsonObject? = null
        var reopens = 0
        var primaryRequests = 0
        fun continuation() = GitHubOutputContinuationSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                    primaryRequests++
                    check(primaryRequests == 1) { "The blocked draft must retain its original text when evidence changes." }
                    return flowOf(ProviderEvent.TextDelta("Retained findings."), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
                }
            },
            continuationRevision = "same-route-and-context",
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { draft, _, _ ->
            assertEquals("Retained findings.", draft)
            reopens++
            if (reopens < 4) require(false) { "CONTINUATION_CONTEXT_CAPACITY: Mandatory protocol does not fit." }
            session(ProviderEvent.TextDelta(" The remaining result."), ProviderEvent.Completed)
        }
        continuation().streamRound(listOf(tool), emptyList()).toList()
        repeat(2) { index ->
            val result = AgentToolResult(call.callId, ToolResultContent.Text("Changed retained evidence $index"), false)
            continuation().streamRound(listOf(tool), listOf(AgentToolExchange(listOf(call), listOf(result)))).toList()
            assertEquals(JsonPrimitive("BLOCKED_CONTEXT_CAPACITY"), checkpoint?.get("state"))
            assertEquals(JsonPrimitive(0), checkpoint?.get("continuations"))
        }
        val finalResult = AgentToolResult(call.callId, ToolResultContent.Text("Bounded retained evidence"), false)
        val output = continuation().streamRound(listOf(tool), listOf(AgentToolExchange(listOf(call), listOf(finalResult)))).toList()
        assertEquals(4, reopens)
        assertEquals(1, primaryRequests)
        assertEquals("Retained findings. The remaining result.", output.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(JsonPrimitive(1), checkpoint?.get("continuations"))
    }
}
