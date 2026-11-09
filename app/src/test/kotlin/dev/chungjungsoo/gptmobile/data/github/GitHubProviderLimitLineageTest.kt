package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubProviderLimitLineageTest {
    private val limit = ProviderLimitDecision("OPENROUTER", "saved-profile", "selected-model", 100_000, "scope", "PROVIDER_RATE_LIMITED", true, httpStatus = 429)
    private val failure = ProviderEvent.Failed("HTTP 429", errorCode = "RATE_LIMITED", providerLimit = limit, invocationId = "actual-invocation")

    @Test fun runnerRetainsTypedLimitAndPhysicalInvocationWithoutAnotherModelRound() = runTest {
        var requests = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                requests++
                emit(failure)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, emptyList()).toList()
            .filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(1, requests)
        assertEquals(failure, events.filterIsInstance<ProviderEvent.Failed>().single())
        assertFalse(events.any { it is ProviderEvent.TextDelta })
    }

    @Test fun retainedEvidenceReportPreservesLimitMetadataWithoutCallingAnotherModel() = runTest {
        val gate = gate(unknownWrite = false)
        val events = gate.retainedFailureResponse(failure).toList()
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals(limit, terminal.providerLimit)
        assertEquals("actual-invocation", terminal.invocationId)
        assertEquals(OutputOrigin.APPLICATION_REPORT, events.filterIsInstance<ProviderEvent.TextDelta>().single().lineage.origin)
    }

    @Test fun uncertainPriorWriteOverridesAutomaticProviderWait() = runTest {
        val events = gate(unknownWrite = true).retainedFailureResponse(failure).toList()
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals("RECONCILIATION_REQUIRED", terminal.errorCode)
        assertNull(terminal.providerLimit)
        assertEquals("actual-invocation", terminal.invocationId)
    }

    private suspend fun gate(unknownWrite: Boolean): GitHubVerifiedSession {
        val book = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context", "github_write"), now = { 1000L })
        book.observe(
            ProviderEvent.ToolCall("saved-read", "github_context", buildJsonObject { put("action", "checks") }),
            AgentToolResult("saved-read", ToolResultContent.Json(buildJsonObject { put("checks", 3) }), false)
        )
        if (unknownWrite) {
            book.observe(
                ProviderEvent.ToolCall(
                    "unknown-write",
                    "github_write",
                    buildJsonObject {
                        put("action", "commit")
                        put("branch", "main")
                    }
                ),
                AgentToolResult("unknown-write", ToolResultContent.Text("Response lost"), true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN", dispatched = true)
            )
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf<ProviderEvent>(failure)
        }
        return GitHubVerifiedSession(source, book, "Explain the retained repository observations", { 8192 }, { error("A provider wait cannot open another model") }, {}, {}, now = { 1000L })
    }
}
