package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchCheckpoint
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskAdmissionTest {
    private val task = "Review the branch. Budget: 1 minute."

    @Test fun typedExpiredAdmissionRunsBeforeQueuedCallsRestoreOrProviderInference() = runBlocking {
        var clock = 1_000_000L
        val book = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), now = { clock }, requireAcceptedContract = true)
        book.retainTaskContract(task, 10, emptyList(), 9)
        clock += 60_000
        val queued = ProviderEvent.ToolCall("queued", "github_context", buildJsonObject { put("action", "status") })
        val checkpoint = ToolBatchCheckpoint(null, "task")
        checkpoint.enqueue(listOf(queued))
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Expired admission must not invoke the provider")
            override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flowOf(failure)
        }
        val runner = AgentRunner(AgentRunLimits(githubTask = true), checkpoint = checkpoint, remainingTaskMillis = book::remainingTaskMillis, taskAdmissionFailure = book::taskAdmissionFailure)
        val events = runner.run(session, emptyList()).toList()
        val failure = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(JsonPrimitive("ADMISSION"), failure.failureDetails?.get("task_deadline_check"))
        assertEquals(JsonPrimitive("AUTHORIZE_NEW_TIME_WINDOW"), failure.failureDetails?.get("recovery_action"))
        assertEquals(listOf(queued), checkpoint.pending())
        assertEquals(0, checkpoint.dispatchedCount())
    }

    @Test fun missingContractAndMalformedDeadlineCannotCreateAnUnboundedExecutionWindow() = runBlocking {
        val absent = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), requireAcceptedContract = true)
        assertEquals("GITHUB_TASK_IDENTITY_CHANGED", absent.taskAdmissionFailure()?.errorCode)
        val invalid = buildJsonObject {
            put("accepted_at_ms", 1000)
            put("deadline_at_ms", "unreadable")
        }
        val decision = GitHubExecutionLimits.admission(invalid, 2000)
        assertEquals("TIMING_UNAVAILABLE", decision.observation)
        assertEquals(0L, decision.remainingMillis)
        assertEquals("GITHUB_TASK_IDENTITY_CHANGED", decision.failure()?.errorCode)
        assertNull(GitHubExecutionLimits.admission(buildJsonObject { put("accepted_at_ms", 1000) }, 2000).failure())
    }

    @Test fun renewalCannotBypassVerificationOnlyOrChangedOwnership() = runBlocking {
        for (executionAllowed in listOf(false, true)) {
            var clock = 1_000_000L
            val saved = mutableMapOf<String, JsonObject>()
            val book = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), now = { clock }, load = { saved[it] }, save = { k, v -> saved[k] = v }, authorizeTaskContinuation = { false }, requireAcceptedContract = true)
            book.retainTaskContract(task, 10, emptyList(), 9)
            val original = saved.toMap()
            clock += 60_000
            book.authorizeTimeWindow("Continue for 10 minutes", 11, clock / 1000, executionAllowed)
            assertEquals(original, saved)
            assertEquals(0L, book.remainingTaskMillis())
            if (executionAllowed) assertEquals("GITHUB_TASK_IDENTITY_CHANGED", book.taskAdmissionFailure()?.errorCode)
        }
    }

    @Test fun renewalPreservesPendingApprovalAndUnknownWriteInsteadOfGrantingExecution() = runBlocking {
        for (unknown in listOf(false, true)) {
            var clock = 1_000_000L
            val saved = mutableMapOf<String, JsonObject>()
            val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_write"), now = { clock }, load = { saved[it] }, save = { k, v -> saved[k] = v }, requireAcceptedContract = true)
            book.retainTaskContract(task, 10, emptyList(), 9)
            val call = ProviderEvent.ToolCall(
                "saved-write",
                "github_write",
                buildJsonObject {
                    put("action", "commit")
                    put("branch", "feature")
                    put("stage_id", "saved-stage")
                }
            )
            val result = buildJsonObject {
                put("engineering_task_id", "approval-42")
                put("status", if (unknown) "UNKNOWN_OUTCOME" else "AWAITING_APPROVAL")
                put("committed", false)
            }
            book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(result), unknown, dispatched = unknown, errorCode = if (unknown) "TOOL_OUTCOME_UNKNOWN" else null))
            val original = book.snapshot().first
            clock += 60_000
            book.authorizeTimeWindow("Continue for 10 minutes", 11, clock / 1000)
            assertEquals(if (unknown) "TOOL_OUTCOME_UNKNOWN" else "WAITING_FOR_APPROVAL", book.taskAdmissionFailure()?.errorCode)
            assertFalse(saved.containsKey("deadline_grant_11"))
            assertEquals(original, book.snapshot().first)
            assertEquals(0L, book.remainingTaskMillis())
        }
    }

    @Test fun restoredGrantMustMatchTheTaskAndOriginalBoundedUserAuthorization() = runBlocking {
        var clock = 1_000_000L
        val saved = mutableMapOf<String, JsonObject>()
        fun open(grant: JsonObject? = saved["deadline_grant_11"]) = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), now = { clock }, load = { saved[it] }, save = { k, v -> saved[k] = v }, latestDeadlineGrant = { grant }, requireAcceptedContract = true)
        val first = open()
        first.retainTaskContract(task, 10, emptyList(), 9)
        clock += 60_000
        first.authorizeTimeWindow("Continue for 10 minutes", 11, clock / 1000)
        val valid = saved.getValue("deadline_grant_11")
        for ((field, bad) in mapOf(
            "binding" to JsonPrimitive("another-account"),
            "source_message_id" to JsonPrimitive(9),
            "authorization_source" to JsonPrimitive("AUTOMATIC_RETRY"),
            "deadline_at_ms" to JsonPrimitive(Long.MAX_VALUE),
            "authorized_duration_ms" to JsonPrimitive(86_400_001L)
        )) {
            val restored = open(JsonObject(valid + (field to bad)))
            restored.retainTaskContract(task, 10, emptyList(), 9)
            assertEquals(field, 0L, restored.remainingTaskMillis())
            assertTrue(restored.taskAdmissionFailure() != null)
        }
        val restored = open(valid)
        restored.retainTaskContract(task, 10, emptyList(), 9)
        assertEquals(600_000L, restored.remainingTaskMillis())
        assertNull(restored.taskAdmissionFailure())
    }
}
