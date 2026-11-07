package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPolicyInvalidationTest {
    @Test fun policyInvalidationWhileCheckpointingStopsBeforeProviderOrToolPlanning() = runBlocking {
        val policy = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("profile.disableAllTools"))
        val retained = linkedMapOf<String, JsonObject>()
        var changed = false
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), save = { key, value ->
            if (changed) throw policy
            retained[key] = value
        })
        val call = ProviderEvent.ToolCall("earlier", "github_context", JsonObject(mapOf("action" to JsonPrimitive("status"))))
        book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(JsonObject(mapOf("ok" to JsonPrimitive(true)))), false))
        val earlier = retained.toMap()
        changed = true
        val noProvider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("Invalidated policy cannot generate another model round")
        }
        val gate = GitHubVerifiedSession(noProvider, book, "test all tools", { 8192 }, { error("No verifier after policy invalidation") }, {}, {})
        val events = gate.streamRound(listOf(AgentToolDefinition("github_context", "Status", JsonObject(emptyMap()))), emptyList()).toList()
        val failed = events.single() as ProviderEvent.Failed
        assertEquals("DISPATCH_POLICY_CHANGED", failed.errorCode)
        assertEquals("CONFIGURATION_INVALIDATED", failed.taskOutcome)
        assertEquals(JsonPrimitive(false), failed.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("provider_admission"), failed.failureDetails?.get("failed_stage"))
        assertEquals(Json.parseToJsonElement("""["profile.disableAllTools"]"""), failed.failureDetails?.get("changed_policy_fields"))
        assertEquals(earlier, retained)
        assertEquals(1, book.snapshot().first.size)
        assertEquals(GitHubTaskLifecycle.State.BLOCKED, GitHubTaskLifecycle.progress(failed, 1))
    }

    @Test fun publicationPolicyInvalidationPreservesTheBlockerWithoutClaimingAcceptance() = runBlocking {
        val policy = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("engineering"))
        var publicationCalls = 0
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet())
        val gate = GitHubVerifiedSession(answer("private draft"), book, "Explain the repository", { 8192 }, { error("No verifier after policy invalidation") }, { throw policy }, {}, onPublication = { publicationCalls++ })
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(0, publicationCalls)
        assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
        val failed = events.single() as ProviderEvent.Failed
        assertEquals("DISPATCH_POLICY_CHANGED", failed.errorCode)
        assertEquals("CONFIGURATION_INVALIDATED", failed.taskOutcome)
        assertTrue(failed.message.contains("engineering"))
        assertEquals(JsonPrimitive(false), GitHubTaskLifecycle.failureMetadata(failed)["retryable_without_new_input"])
    }

    @Test fun downstreamPolicyExceptionStillPropagatesWithoutAlternativeDelivery() = runBlocking {
        val gate = GitHubVerifiedSession(answer("private"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { answer("invalid JSON") }, {}, {})
        val policy = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("engineering"))
        val result = runCatching { gate.streamRound(emptyList(), emptyList()).collect { throw policy } }
        assertSame(policy, result.exceptionOrNull())
    }

    @Test fun wrappedPolicyInvalidationRetainsItsTypedOutcome() = runBlocking {
        val policy = dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("engineering"))
        val gate = GitHubVerifiedSession(answer("private"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "Explain the repository", { 8192 }, { error("No verifier after policy invalidation") }, { throw IllegalStateException("wrapped", policy) }, {})
        val failed = gate.streamRound(emptyList(), emptyList()).toList().single() as ProviderEvent.Failed
        assertEquals("DISPATCH_POLICY_CHANGED", failed.errorCode)
        assertEquals("CONFIGURATION_INVALIDATED", failed.taskOutcome)
        assertTrue(failed.message.contains("engineering"))
    }

    @Test fun cyclicAndDeepCauseChainsTerminateWithoutAlternativeExecution() = runBlocking {
        class LinkedFailure : IllegalStateException("failure chain") {
            var next: Throwable? = null
            var reads = 0
            override val cause: Throwable? get() {
                check(++reads <= 16) { "Cause traversal must be bounded" }
                return next
            }
        }
        for (shape in listOf("self", "cycle", "deep")) {
            val chain = List(if (shape == "deep") 40 else 2) { LinkedFailure() }
            chain.zipWithNext().forEach { (a, b) -> a.next = b }
            if (shape == "self") chain.first().next = chain.first()
            if (shape == "cycle") chain.last().next = chain.first()
            val failure = chain.first()
            val delegate = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = throw failure
            }
            val gate = GitHubVerifiedSession(delegate, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "Explain the repository", { 8192 }, { error("No repair model") }, {}, {})
            val result = runCatching { gate.streamRound(emptyList(), emptyList()).toList() }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(chain.sumOf { it.reads } <= 16)
        }
    }

    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }
}
