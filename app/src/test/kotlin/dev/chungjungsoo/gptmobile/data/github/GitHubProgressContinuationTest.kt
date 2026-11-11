package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.flow.flowOf
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

class GitHubProgressContinuationTest {
    private fun receipt(path: String = "app.kt", success: Boolean = true) = buildJsonObject {
        put("tool", "github_read")
        put("arguments", buildJsonObject { put("path", path) })
        put("success", success)
        put("dispatched", true)
        put("trusted", true)
    }

    private fun report(recovery: String = "RESUME_UNFINISHED_WORK") = buildJsonObject {
        put("completion_verified", false)
        put("task_outcome", "PARTIAL")
        put(
            "execution_ledger",
            buildJsonObject {
                put("recovery", recovery)
                put("required_criteria", 3)
                put("verified_criteria", 1)
                put("remaining_criteria", 2)
            }
        )
    }

    @Test fun onlyChangedSuccessfulOperationsCountAsProgress() {
        val original = receipt()
        val progress = GitHubExecutionContinuation.progressKeys(mapOf("one" to original))
        assertEquals(progress, GitHubExecutionContinuation.progressKeys(mapOf("other-id" to original)))
        assertEquals(2, GitHubExecutionContinuation.progressKeys(mapOf("one" to original, "two" to receipt("other.kt"))).size)
        assertTrue(GitHubExecutionContinuation.progressKeys(mapOf("failed" to receipt(success = false))).isEmpty())
        assertTrue(GitHubExecutionContinuation.progressKeys(mapOf("cache" to JsonObject(original + ("shared_result" to JsonPrimitive(true))))).isEmpty())
    }

    @Test fun budgetApprovalAndUncertainOutcomesCannotAutomaticallyContinue() {
        val receipts = mapOf("one" to receipt())
        assertTrue(GitHubExecutionContinuation.canResume(report(), receipts, 10, 1000))
        assertFalse(GitHubExecutionContinuation.canResume(report(), receipts, 0, 1000))
        assertFalse(GitHubExecutionContinuation.canResume(report(), receipts, 10, 0))
        for (recovery in listOf("WAITING_FOR_APPROVAL", "USER_INPUT_REQUIRED", "RECONCILE_REMOTE_OUTCOME", "RESTORE_AUTHORIZED_CONFIGURATION", "RESUME_VERIFICATION")) {
            assertFalse(recovery, GitHubExecutionContinuation.canResume(report(recovery), receipts, 10, 1000))
        }
        assertFalse(GitHubExecutionContinuation.canResume(report(), receipts + ("failed" to receipt(success = false)), 10, 1000))
        assertFalse(GitHubExecutionContinuation.canResume(JsonObject(report() + ("completion_verified" to JsonPrimitive(true))), receipts, 10, 1000))
        assertTrue(GitHubExecutionContinuation.incompleteMessage(report(), receipts).contains("1 of 3 acceptance criteria"))
    }

    @Test fun progressAfterFirstReadResumesUnfinishedWorkAndDoesNotPublishPartialError() = runBlocking {
        val tool = AgentToolDefinition("github_read", "Read repository source", JsonObject(emptyMap()))
        val first = ProviderEvent.ToolCall("first", tool.name, buildJsonObject { put("path", "app.kt") })
        val next = ProviderEvent.ToolCall("next", tool.name, buildJsonObject { put("path", "test.kt") })
        var round = 0
        var corrections = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = if (round++ == 0) {
                flowOf(first, ProviderEvent.Completed)
            } else {
                flowOf(ProviderEvent.TextDelta("I have read the first file. The fix remains to be implemented."), ProviderEvent.Completed)
            }
        }
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(tool.name))
        val session = GitHubVerifiedSession(provider, book, "Fix app.kt and test the change", { 8192 }, {
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("""{"claims":[{"kind":"not_measured","subject":"implementation and tests"}]}"""), ProviderEvent.Completed)
            }
        }, {}, {}, remainingToolCalls = { 20 }, openExecutionContinuation = { prompt ->
            corrections++
            assertTrue(prompt.contains("Resume only unverified criteria"))
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(next, ProviderEvent.Completed)
            }
        })
        assertEquals(first, session.streamRound(listOf(tool), emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single())
        book.observe(
            first,
            AgentToolResult(
                first.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("content", "source")
                        put("path", "app.kt")
                    }
                ),
                false
            )
        )
        val events = session.streamRound(listOf(tool), emptyList()).toList()
        assertEquals("events=$events checkpoint=${book.diagnostic()}", 1, corrections)
        assertEquals(next, events.filterIsInstance<ProviderEvent.ToolCall>().single())
        assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.TextDelta })
        assertEquals(ProviderEvent.Completed, events.last())
        // Without another observed operation, another prose stop cannot spend another correction.
        session.streamRound(listOf(tool), emptyList()).toList()
        assertEquals("events=$events checkpoint=${book.diagnostic()}", 1, corrections)
    }
}
