package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerifierOnlyRecoveryTest {
    @Test fun explicitRecoveryRechecksSavedReceiptsOnceWithoutOpeningGenerationOrTools() = runBlocking {
        val saved = mutableMapOf<String, JsonObject>()
        val report = buildJsonObject {
            put("binding", "binding")
            put("failure_code", "GITHUB_VERIFICATION_INTERNAL_ERROR")
            put("task_outcome", "PARTIAL")
            put("evidence_retention_complete", true)
            put("resume_allowed", false)
            put("retryable_without_new_input", false)
        }
        val evidence = GitHubEvidenceBook(
            "binding",
            "owner/repo",
            "main",
            setOf("github_context"),
            now = { 1000L },
            load = { saved[it] },
            save = { key, value -> saved[key] = value },
            latestVerdict = { report }
        )
        evidence.observe(
            ProviderEvent.ToolCall("saved-read", "github_context", buildJsonObject { put("action", "status") }),
            AgentToolResult("saved-read", ToolResultContent.Json(buildJsonObject { put("branch", "main") }), false)
        )
        var publications = 0
        var validations = 0
        val session = GitHubVerifiedSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Verifier-only recovery cannot generate or dispatch tools")
            },
            evidence, "Explain the observed repository branch", { 8192 },
            { error("A missing draft can only recheck saved receipts") },
            { validations++ }, {}, now = { 1000L },
            openExecutionContinuation = { error("Verifier-only recovery cannot reopen execution") },
            onPublication = { publications++ }, verificationOnlyRequested = true
        )
        val mutation = AgentToolDefinition("github_write", "Write repository", JsonObject(emptyMap()))
        val before = evidence.snapshot().first
        val events = session.streamRound(listOf(mutation), emptyList()).toList()
        assertEquals(1, publications)
        assertTrue(validations > 0)
        assertTrue(events.any { it is ProviderEvent.TextDelta })
        assertFalse(events.any { it is ProviderEvent.ToolCall })
        assertEquals(before, evidence.snapshot().first)
        val again = session.streamRound(listOf(mutation), emptyList()).toList()
        assertEquals(1, publications)
        assertTrue(again.last() is ProviderEvent.Failed)
        assertEquals(JsonPrimitive(false), (again.last() as ProviderEvent.Failed).failureDetails?.get("request_replay_allowed"))
        assertFalse(again.any { it is ProviderEvent.ToolCall })
    }

    @Test fun changedAuthorityOrUncertainWriteCannotFallThroughToGeneration() = runBlocking {
        for (blocker in listOf("task_outcome" to JsonPrimitive("CONFIGURATION_INVALIDATED"), "requires_reconciliation" to JsonPrimitive(true))) {
            val evidence = GitHubEvidenceBook(
                "binding",
                "owner/repo",
                "main",
                emptySet(),
                latestVerdict = {
                    buildJsonObject {
                        put("binding", "binding")
                        put("failure_code", "GITHUB_VERIFICATION_INTERNAL_ERROR")
                        put("evidence_retention_complete", true)
                        put(blocker.first, blocker.second)
                    }
                }
            )
            val session = GitHubVerifiedSession(
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The saved blocker must stop recovery")
                },
                evidence, "Fix repository tests", { error("No verifier request is authorized") },
                { error("No verifier request is authorized") }, {}, {},
                onPublication = { error("A blocked action cannot run another verification pass") },
                verificationOnlyRequested = true
            )
            val events = session.streamRound(emptyList(), emptyList()).toList()
            assertEquals(1, events.size)
            assertTrue(events.single() is ProviderEvent.Failed)
            assertEquals(JsonPrimitive(false), (events.single() as ProviderEvent.Failed).failureDetails?.get("dispatched"))
        }
    }
}
