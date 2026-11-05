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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskLifecycleTest {
    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }

    @Test fun everyStageKeepsItsConfirmedReceiptWhenItsProcessRestarts() = runBlocking {
        for (stage in listOf("reading", "editing", "testing", "pushing", "ci_monitoring")) {
            val rows = linkedMapOf<String, JsonObject>()
            fun book() = GitHubEvidenceBook(
                "task-$stage",
                "owner/repo",
                "main",
                setOf("github_context"),
                save = { key, value -> rows[key] = value },
                load = { rows[it] },
                retainedIds = { rows.keys.filter { it.startsWith("ev_") } },
                latestVerdict = { rows.values.lastOrNull() }
            )
            val original = book()
            original.retainProgress("Implement the task", emptyList())
            val plan = rows.values.single { it["task_state"] == JsonPrimitive("PLANNED") }
            assertEquals(JsonPrimitive(false), plan["completion_verified"])
            assertEquals(JsonPrimitive(false), plan["response_prepared"])
            assertEquals(JsonPrimitive(false), plan["response_delivered"])
            assertEquals(JsonPrimitive("RUNNING"), rows.values.last()["task_state"])
            val call = ProviderEvent.ToolCall("confirmed-$stage", "github_context", JsonObject(emptyMap()))
            original.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(JsonObject(mapOf("confirmed" to JsonPrimitive(stage)))), false))
            original.retainProgress("Implement the task", emptyList(), "NETWORK_ERROR", ProviderEvent.Failed("Connection reset", errorCode = "NETWORK_ERROR", failureDetails = buildJsonObject { put("failed_stage", stage) }))
            val restored = book()
            restored.restore()
            assertEquals(1, restored.snapshot().first.size)
            assertEquals(JsonPrimitive("PARTIAL"), restored.diagnostic()?.get("task_state"))
            assertEquals(JsonPrimitive(stage), restored.diagnostic()?.get("failed_stage"))
            assertFalse(restored.diagnostic()!!.containsKey("failed_stage_dispatched"))
            restored.observe(call, AgentToolResult(call.callId, ToolResultContent.Text("Duplicate response"), false))
            assertEquals(1, restored.snapshot().first.size)
        }
    }

    @Test fun reviewPrerequisitesBlockBeforeDispatchAndRetainDraft() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("", { rows[it] }, { key, value -> rows[key] = value })
        val output = GitHubReviewedSession(answer("Saved draft"), GitHubReviewEvidence(), "", 8192, { _, _ -> error("Missing task must block before reviewer dispatch") }, {}, checkpoint = checkpoint)
            .streamRound(emptyList(), emptyList()).toList()
        val failure = output.last() as ProviderEvent.Failed
        assertEquals("BLOCKED", failure.taskOutcome)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonArray(listOf(JsonPrimitive("ORIGINAL_TASK"))), failure.failureDetails?.get("missing_requirements"))
        assertEquals("Saved draft", checkpoint.load()?.draft)
        assertEquals(JsonPrimitive("answer_review"), rows["active"]?.get("failed_stage"))
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    @Test fun changedReviewerResumesSameDraftButUnchangedReviewStaysBlocked() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        checkpoint().save("Retained candidate", emptyList(), 8192, "old-config")
        checkpoint().failed("GITHUB_REVIEW_REQUIRED")
        val noGeneration = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Saved work must not regenerate")
        }
        var reviews = 0
        fun gate(revision: String) = GitHubReviewedSession(noGeneration, GitHubReviewEvidence(), "task", 8192, { _, _ ->
            reviews++
            answer("""{"approved":true,"reviewed_ids":[],"issues":[],"verified_claims":[]}""")
        }, {}, checkpoint = checkpoint(), reviewerRevision = revision)
        val blocked = gate("old-config").streamRound(emptyList(), emptyList()).toList()
        assertEquals("BLOCKED", (blocked.last() as ProviderEvent.Failed).taskOutcome)
        assertEquals(0, reviews)
        val resumed = gate("new-config").streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, resumed.last())
        assertEquals("Retained candidate", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, reviews)
    }

    @Test fun modelCompletionCannotBecomeTaskCompletionBeforeAcceptanceAndDelivery() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task", "o/r", "main", emptySet(), save = { key, value -> saved[key] = value })
        val complete = buildJsonObject {
            put("task_outcome", "COMPLETED")
            put("completion_verified", true)
            put("model_round_finished", true)
            put("requirements", JsonArray(listOf(requirement("PASSED"))))
        }
        book.retainVerdict(complete)
        assertEquals(JsonPrimitive("PARTIAL"), saved.values.last()["task_state"])
        book.retainVerdict(JsonObject(complete + ("response_delivered" to JsonPrimitive(true))))
        assertEquals(JsonPrimitive("COMPLETED"), saved.values.last()["task_state"])
        book.retainVerdict(JsonObject(complete + mapOf("response_delivered" to JsonPrimitive(true), "completion_verified" to JsonPrimitive(false))))
        assertEquals(JsonPrimitive("PARTIAL"), saved.values.last()["task_state"])
    }

    @Test fun missingEmptyFailedOrExcludedChecklistCannotEstablishCompletion() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task", "o/r", "main", emptySet(), save = { key, value -> saved[key] = value })
        for (rows in listOf(null, emptyList(), listOf(requirement("FAILED")), listOf(requirement("BLOCKED")), listOf(requirement("UNTESTED")), listOf(requirement("PASSED", required = false)), listOf(requirement("PASSED"), requirement("FAILED")))) {
            book.retainVerdict(
                buildJsonObject {
                    put("task_outcome", "COMPLETED")
                    put("completion_verified", true)
                    put("response_delivered", true)
                    rows?.let { put("requirements", JsonArray(it)) }
                    // A saved stale projection cannot override the current checklist.
                    put("acceptance_ledger", buildJsonObject { put("completion_verified", true) })
                }
            )
            val retained = saved.values.last()
            assertEquals(JsonPrimitive("PARTIAL"), retained["task_state"])
            assertEquals(JsonPrimitive("PARTIAL"), retained["task_outcome"])
            assertEquals(JsonPrimitive(false), retained["completion_verified"])
            assertEquals(JsonPrimitive(false), (retained["acceptance_ledger"] as JsonObject)["completion_verified"])
        }
    }

    @Test fun verifiedInformationalChecklistCanCompleteWithoutImplementationReceipts() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task", "o/r", "main", emptySet(), save = { key, value -> saved[key] = value })
        val assessment = GitHubRequestCompletion.evaluate("Explain how branches work", listOf(buildJsonObject { put("status", "VERIFIED") }), JsonObject(emptyMap()), emptyMap())
        book.retainVerdict(JsonObject(assessment + ("response_delivered" to JsonPrimitive(true))))
        assertEquals(JsonPrimitive("COMPLETED"), saved.values.last()["task_state"])
        assertEquals(JsonPrimitive(true), (saved.values.last()["acceptance_ledger"] as JsonObject)["completion_verified"])
    }

    private fun requirement(status: String, required: Boolean = true) = buildJsonObject {
        put("id", status)
        put("requirement", "Requested acceptance criterion")
        put("required", required)
        put("status", status)
    }

    @Test fun evidenceRetentionLimitDoesNotMakeBlockedReviewAutomaticallyResumable() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task", "o/r", "main", setOf("github_context"), save = { key, value -> saved[key] = value }, retentionLimitRecords = 1)
        for (id in listOf("one", "two")) {
            book.observe(ProviderEvent.ToolCall(id, "github_context", JsonObject(emptyMap())), AgentToolResult(id, ToolResultContent.Text("Confirmed evidence"), false))
        }
        book.retainVerdict(
            buildJsonObject {
                put("task_outcome", "BLOCKED")
                put("completion_verified", false)
                put("failure_code", "GITHUB_REVIEW_BLOCKED")
            }
        )
        val verdict = saved.values.last()
        assertEquals(JsonPrimitive(false), verdict["evidence_retention_complete"])
        assertEquals(JsonPrimitive("BLOCKED"), verdict["task_outcome"])
        assertEquals(JsonPrimitive("BLOCKED"), verdict["task_state"])
    }
}
