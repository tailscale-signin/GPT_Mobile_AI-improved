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
import dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskTrustTest {
    private val read = AgentToolDefinition("github_read", "Read the accepted repository", JsonObject(emptyMap()))
    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
            flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }

    @Test fun acceptedTaskAndDeadlineSurviveRestartAndCannotChangeSourceIdentity() = runBlocking {
        val saved = mutableMapOf<String, JsonObject>()
        var now = 1_000L
        fun open() = GitHubEvidenceBook("binding", "owner/repo", "feature", setOf(read.name), { now }, load = { saved[it] }, save = { key, value -> saved[key] = value })
        val task = "Fix the repository bug.\nBudget: at most 12 tool calls and 60 minutes."
        open().retainTaskContract(task, 7, listOf(read), 12)
        val original = saved.getValue("task_contract")
        assertEquals(JsonPrimitive("owner/repo"), original["repository"])
        assertEquals(JsonPrimitive(12), original["tool_call_limit"])
        now += 30_000L
        val restored = open()
        restored.retainTaskContract(task, 7, listOf(read), 99)
        assertEquals(original, saved.getValue("task_contract"))
        assertEquals(3_570_000L, restored.remainingTaskMillis())
        assertEquals(task, restored.acceptedTaskRequest())
        assertTrue(runCatching { restored.retainTaskContract(task, 8, listOf(read), 12) }.isFailure)
        now += 3_600_000L
        assertEquals(0L, restored.remainingTaskMillis())
    }

    @Test fun durationReadsExplicitBudgetAndDoesNotInferDeadlinesFromNarrative() {
        assertEquals(3_600_000L, GitHubExecutionLimits.requestedDurationMillis("Budget: at most 256 tool calls and 60 minutes."))
        assertEquals(30_000L, GitHubExecutionLimits.requestedDurationMillis("Complete within 30 seconds"))
        assertNull(GitHubExecutionLimits.requestedDurationMillis("The previous export took 35 seconds. Fix the repository."))
        assertNull(GitHubExecutionLimits.requestedDurationMillis("At most 50 tool calls. Inspect failures from the last 30 minutes."))
        assertNull(GitHubExecutionLimits.requestedDurationMillis("Work within src and inspect events from the last 2 hours."))
        assertEquals(60_000L, GitHubExecutionLimits.requestedDurationMillis("Budget: 1 minutes. Inspect events from the last 20 hours."))
        assertEquals(600_000L, GitHubExecutionLimits.requestedDurationMillis("Test all authorized GitHub tools. Do not merge. Budget: 10 minutes and 17 tool calls."))
        assertEquals(30_000L, GitHubExecutionLimits.requestedDurationMillis("Budget: 10 minutes. Time limit: 30 seconds. Inspect the previous 2 hours."))
        assertEquals(20_000L, GitHubExecutionLimits.requestedDurationMillis("Budget: 10 minutes. Finish within 20 seconds."))
        assertNull(GitHubExecutionLimits.requestedDurationMillis("The previous budget: 5 minutes. Inspect the failure."))
        assertFalse(GitHubTaskContinuity.reuse("proceed", ""))
        assertTrue(GitHubTaskContinuity.reportOnly("Please show the full report!"))
    }

    @Test fun temporaryTaskStillEnforcesAcceptedDeadlineWithoutPersistentStorage() = runBlocking {
        var now = 1_000L
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(read.name), now = { now })
        val task = "Fix the repository within 30 seconds"
        book.retainTaskContract(task, 1, listOf(read), 10)
        assertEquals(task, book.acceptedTaskRequest())
        assertEquals(30_000L, book.remainingTaskMillis())
        now += 40_000L
        book.retainTaskContract(task, 1, listOf(read), 99)
        assertEquals(0L, book.remainingTaskMillis())
        assertTrue(runCatching { book.retainTaskContract(task, 2, listOf(read), 10) }.isFailure)
    }

    @Test fun durationFirstBudgetRetainsTheToolCeilingWithoutCountingHistoricalCalls() {
        val preset = GitHubWorkflowPreset.selected(GitHubWorkflowPreset.DEFAULT_ID)
        val task = "Test all authorized GitHub tools. Budget: 10 minutes and 17 tool calls."
        val limit = GitHubExecutionLimits.callLimit(task, 256, preset)
        assertEquals(17, limit.effective)
        assertEquals(17, limit.requested)
        assertTrue("request ceiling" in limit.sources)
        assertEquals(9, GitHubExecutionLimits.toolCalls(task, 9, preset))
        assertEquals(5, GitHubExecutionLimits.toolCalls("$task At most 5 tool calls.", 256, preset))
        val historical = GitHubExecutionLimits.callLimit("Inspect the previous 17 tool calls. Budget: 10 minutes.", 256, preset)
        assertNull(historical.requested)
    }

    @Test fun everyRecoveryAliasRetainsTheSameAcceptedTask() {
        val task = "Fix the repository and validate the changes within 60 minutes"
        val controls = listOf("continue research", "retry full", "proceed primary-only", "proceed primary only", "continue primary-only", "continue primary only", "primary-only", "retry writing", "resume writing")
        for (control in controls) {
            assertTrue(control, dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.fromText(control) != null)
            assertTrue(control, GitHubTaskContinuity.reuse(control, task))
            assertTrue(control, GitHubTaskContinuity.reuse("Please $control!", task))
        }
    }

    @Test fun bareIntegrationRetestsKeepRestrictionsWhileChangedScopeNeverRestoresOldPermission() {
        val previous = "Test all authorized GitHub integration tools.\nBudget: at most 12 tool calls and 60 minutes."
        for (current in listOf("test all", "Please test all authorized GitHub integration tools!", "test GitHub integration again", previous)) {
            assertTrue(current, GitHubTaskContinuity.reuse(current, previous))
        }
        for (current in listOf("Test all GitHub tools, read-only; do not create fixtures", "Test all GitHub tools within 5 minutes", "Test all GitHub tools in owner/another-repo", "Test all GitHub tools, including a release publication")) {
            assertFalse(current, GitHubTaskContinuity.reuse(current, previous))
        }
        assertTrue(GitHubTaskContinuity.reuse("test all GitHub tools", "Test all GitHub tools, read-only; do not create fixtures"))
        assertFalse(GitHubTaskContinuity.reuse("Test all GitHub tools for Foo.kt", "Test all GitHub tools for foo.kt"))
        assertFalse(GitHubTaskContinuity.reuse("Test all GitHub tools on branch Feature", "Test all GitHub tools on branch feature"))
    }

    @Test fun newReadOnlyTaskCannotScheduleAnOlderTasksFixtureWrite() = runBlocking {
        val previous = "Test all GitHub tools"
        val current = "Test all GitHub tools, read-only; do not create fixtures"
        val history = listOf(MessageV2(id = 1, chatId = 7, content = previous, platformType = null), MessageV2(id = 2, chatId = 7, content = current, platformType = null))
        val effectiveTask = resolveTaskRecovery(history)?.originalRequest ?: current
        fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
        val commit = AgentToolDefinition("github_commit", "fixture lifecycle", json("""{"type":"object","properties":{"action":{"enum":["branch","delete_branch"]},"branch":{"type":"string"},"from_branch":{"type":"string"},"expected_head_sha":{"type":"string"},"select_created_branch":{"type":"boolean"}}}"""))
        val evidence = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_context", "github_commit"))
        val status = ProviderEvent.ToolCall("status", "github_context", json("""{"action":"status"}"""))
        evidence.observe(status, AgentToolResult("status", ToolResultContent.Json(json("""{"branch":"main","commit_sha":"${"a".repeat(40)}","repository":{"default_branch":"main"}}""")), false, dispatched = true))
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The checklist owns scheduling")
        }
        val session = GitHubVerifiedSession(provider, evidence, effectiveTask, { 8192 }, { error("No extraction") }, {}, {}, authorizedTools = listOf(commit), remainingToolCalls = { 20 }, currentRequest = current)
        val events = session.streamRound(listOf(commit), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall })
        assertEquals(current, effectiveTask)
    }

    @Test fun diagnosticFollowUpExplainsSavedTaskWithoutRestartingItsOperations() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(read.name), latestVerdict = {
            buildJsonObject {
                put("binding", "b")
                put("verification_failure", "GITHUB_TASK_INCOMPLETE")
            }
        })
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("A diagnostic question cannot restart the task")
        }
        val gate = GitHubVerifiedSession(provider, book, "Fix the repository and publish", { 8192 }, { error("No formatter") }, {}, {}, currentRequest = "Why did the previous response stop?", openExecutionContinuation = { error("No corrective execution") })
        val events = gate.streamRound(listOf(read), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall })
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("GITHUB_TASK_INCOMPLETE"))
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun repositoryInterruptionQuestionsStartNewTasksAndKeepTheirOwnRecoveryIdentity() {
        for (current in listOf("Why did CI stop on PR #813?", "Can you explain why workflow 42416901069 stopped?", "Why was the previous workflow run interrupted?")) {
            val history = listOf(
                MessageV2(id = 1, chatId = 7, content = "Test all GitHub tools", platformType = null),
                MessageV2(id = 2, chatId = 7, content = current, platformType = null)
            )
            assertNull(current, resolveTaskRecovery(history))
            val resumed = requireNotNull(resolveTaskRecovery(history + MessageV2(id = 3, chatId = 7, content = "continue", platformType = null)))
            assertEquals(current, resumed.originalRequest)
            assertEquals(2, resumed.sourceMessageId)
        }
    }

    @Test fun repositoryInterruptionQuestionsReachLiveInvestigationInsteadOfHistoricalExplanation() = runBlocking {
        val ci = AgentToolDefinition("github_ci", "Read the requested workflow", JsonObject(emptyMap()))
        val call = ProviderEvent.ToolCall("current-workflow", ci.name, buildJsonObject { put("action", "runs") })
        for (current in listOf("Why did CI stop on PR #813?", "Can you explain why workflow 42416901069 stopped?", "Why was the previous workflow run interrupted?")) {
            var providerRounds = 0
            val book = GitHubEvidenceBook("current", "o/r", "feature", setOf(ci.name), latestVerdict = {
                buildJsonObject {
                    put("binding", "current")
                    put("verification_failure", "GITHUB_TASK_INCOMPLETE")
                }
            })
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(call, ProviderEvent.Completed).also {
                    providerRounds++
                    assertEquals(listOf(ci), tools)
                }
            }
            val gate = GitHubVerifiedSession(provider, book, current, { 8192 }, { error("A tool round does not need answer extraction") }, {}, {})
            val events = gate.streamRound(listOf(ci), emptyList()).toList()
            assertEquals(current, 1, providerRounds)
            assertEquals(listOf(call), events.filterIsInstance<ProviderEvent.ToolCall>())
            assertTrue(events.none { it is ProviderEvent.TextDelta })
            assertEquals(ProviderEvent.Completed, events.last())
        }
    }

    @Test fun writingRetryRestoresSavedAnswerWithoutReplayingAcceptedOperations() = runBlocking {
        val saved = mutableMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook("b", "o/r", "feature", setOf(read.name), load = { saved[it] }, save = { key, value -> saved[key] = value })
        val evidence = book()
        val verdict = buildJsonObject {
            put("task_outcome", "PARTIAL")
            put("completion_verified", false)
        }
        val (receipts, epoch) = evidence.snapshot()
        assertTrue(
            evidence.retainAnswerIfCurrent(
                "Verified file reading finished; implementation remains blocked.",
                verdict,
                epoch,
                receipts.keys
            )
        )
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Writing retry must not invoke model operations")
        }
        val gate = GitHubVerifiedSession(provider, book(), "Fix the repository and publish", { 8192 }, { error("No extraction") }, {}, {}, currentRequest = "retry writing")
        val events = gate.streamRound(listOf(read), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall })
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Verified file reading finished; implementation remains blocked."))
        assertEquals("PARTIAL", (events.last() as ProviderEvent.Failed).taskOutcome)
        val key = saved.keys.single { it.startsWith("answer_current_") }
        saved[key] = JsonObject(saved.getValue(key) + ("text" to JsonPrimitive("corrupted")))
        assertNull(book().retainedAnswer())
    }

    @Test fun planWithoutCallsGetsOneToolCapableCorrectionAndNeverPublishesThePlanAsProof() = runBlocking {
        var corrections = 0
        var correctionPrompt = ""
        val call = ProviderEvent.ToolCall("read-once", read.name, buildJsonObject { put("path", "app.kt") })
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(read.name))
        val session = GitHubVerifiedSession(
            answer("I will read the file and fix everything."), book, "Fix the bug in app.kt", { 8192 }, { answer("{\"claims\":[]}") }, {}, {},
            currentRequest = "do the remaining work",
            openExecutionContinuation = { prompt ->
                corrections++
                correctionPrompt = prompt
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(call, ProviderEvent.Completed).also { assertEquals(listOf(read), tools) }
                }
            }
        )
        val first = session.streamRound(listOf(read), emptyList()).toList()
        assertEquals(listOf(call), first.filterIsInstance<ProviderEvent.ToolCall>())
        assertTrue(first.none { it is ProviderEvent.TextDelta })
        assertTrue(correctionPrompt.contains("Newest user instruction:\ndo the remaining work"))
        val second = session.streamRound(listOf(read), emptyList()).toList()
        assertEquals(1, corrections)
        assertTrue(second.last() is ProviderEvent.Failed)
        assertTrue(second.none { it is ProviderEvent.Completed })
        assertFalse(second.filterIsInstance<ProviderEvent.TextDelta>().joinToString { it.text }.contains("fix everything"))
    }

    @Test fun reportOnlyAndExpiredTaskNeverEnterProviderOrDispatchOperations() = runBlocking {
        val saved = mutableMapOf<String, JsonObject>()
        var now = 0L
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(read.name), { now }, load = { saved[it] }, save = { key, value -> saved[key] = value })
        book.retainTaskContract("Test all GitHub tools.\nBudget: 1 seconds", 1, listOf(read), 10)
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("No provider or tool execution is authorized by report delivery or an expired task")
        }
        for (current in listOf("show the full report", "proceed")) {
            if (current == "proceed") now = 2_000L
            val session = GitHubVerifiedSession(provider, book, "Test all GitHub tools", { 8192 }, { error("No extraction") }, {}, {}, currentRequest = current)
            val events = session.streamRound(listOf(read), emptyList()).toList()
            assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.Completed })
            assertTrue(events.any { it is ProviderEvent.TextDelta && it.text.isNotBlank() })
            assertEquals(if (current == "proceed") "BLOCKED" else "PARTIAL", (events.last() as ProviderEvent.Failed).taskOutcome)
        }
    }

    @Test fun expiredTaskKeepsItsDeadlineCauseWhenAnOlderApprovalIsStillUnresolved() = runBlocking {
        val write = AgentToolDefinition("github_write", "Stage a patch", JsonObject(emptyMap()))
        val book = GitHubEvidenceBook("expired", "o/r", "feature", setOf(write.name))
        val call = ProviderEvent.ToolCall(
            "staged",
            write.name,
            buildJsonObject {
                put("action", "commit")
                put("branch", "feature")
                put("expected_head_sha", "a".repeat(40))
                put("stage_id", "saved-stage")
            }
        )
        book.observe(
            call,
            AgentToolResult(
                call.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("engineering_task_id", "approval-42")
                        put("status", "AWAITING_APPROVAL")
                        put("committed", false)
                    }
                ),
                false,
                dispatched = false
            )
        )
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("Report delivery cannot dispatch work")
        }
        var publication: JsonObject? = null
        val session = GitHubVerifiedSession(provider, book, "Test all GitHub tools", { 8192 }, { error("No extraction") }, {}, {}, authorizedTools = listOf(write), onPublication = { publication = it })
        val events = session.retainedFailureResponse(GitHubExecutionLimits.expiredTaskFailure()).toList()
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("GITHUB_TASK_INCOMPLETE", failure.errorCode)
        assertEquals(JsonPrimitive("task_budget"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED"), failure.failureDetails?.get("stop_reason"))
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("Time limit reached"))
        assertTrue(text.contains("saved approval"))
        assertFalse(text.contains("The commit has not been published"))
        assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.Completed })
        val saved = requireNotNull(publication)
        assertEquals(JsonPrimitive("BLOCKED"), saved["task_state"])
        assertEquals(JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED"), saved["blocking_condition"])
        assertEquals(GitHubTaskLifecycle.State.BLOCKED, GitHubTaskLifecycle.terminal(saved))
        assertEquals(JsonPrimitive("NEW_TASK_REQUIRED"), (saved["execution_ledger"] as JsonObject)["recovery"])
        assertTrue((saved["pending_approvals"] as JsonArray).isNotEmpty())
        assertTrue(saved["stop_reason"] is JsonObject)
        val runAcceptance = GitHubRunAcceptance.capture(saved, "expired-run", 1, 2, 3)
        assertEquals(JsonPrimitive("BLOCKED"), runAcceptance["task_state"])
        assertEquals(JsonPrimitive("BLOCKED"), runAcceptance["task_outcome"])
        assertEquals(JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED"), runAcceptance["stop_code"])
    }

    @Test fun expiredRunnerStillDeliversExplicitSavedReportsAndInterruptionExplanations() = runBlocking {
        val saved = mutableMapOf<String, JsonObject>()
        val verdict = buildJsonObject {
            put("binding", "expired")
            put("task_outcome", "PARTIAL")
            put("completion_verified", false)
            put("verification_failure", "GITHUB_TASK_INCOMPLETE")
        }
        val book = GitHubEvidenceBook("expired", "o/r", "feature", setOf(read.name), load = { saved[it] }, save = { key, value -> saved[key] = value }, latestVerdict = { verdict })
        val (receipts, epoch) = book.snapshot()
        assertTrue(
            book.retainAnswerIfCurrent(
                "The saved report contains the verified source observations.",
                verdict,
                epoch,
                receipts.keys
            )
        )
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("Retained delivery cannot invoke the provider")
        }
        for (current in listOf("show the full report", "Why did the previous response stop?")) {
            val session = GitHubVerifiedSession(provider, book, "Fix the repository", { 8192 }, { error("No extraction") }, {}, {}, currentRequest = current)
            val events = AgentRunner(AgentRunLimits(githubTask = true, runTimeoutMillis = 0)).run(session, emptyList()).toList()
            val providerEvents = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            val text = providerEvents.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(if (current.startsWith("show")) text.contains("The saved report contains the verified source observations.") else text.contains("The previous response ended with **GITHUB_TASK_INCOMPLETE**"))
            assertFalse(text.contains("Time limit reached"))
            assertTrue(providerEvents.none { it is ProviderEvent.ToolCall })
        }
    }

    @Test fun correctiveProseCannotBypassReviewOrBecomeExecutionEvidence() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(read.name))
        val session = GitHubVerifiedSession(
            answer("I will implement this next."),
            book,
            "Fix the bug in app.kt",
            { 8192 },
            { error("Unreviewed correction must not enter claim extraction") },
            {},
            {},
            openExecutionContinuation = { answer("Everything is fixed and tested; 87 successful calls.") }
        )
        val events = session.streamRound(listOf(read), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.Completed })
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.filterIsInstance<ProviderEvent.TextDelta>().joinToString { it.text }.contains("87 successful"))
    }

    @Test fun correctiveContextRetainsScopeAndMeasuredCountsWithoutAccumulatedPlans() {
        val receipts = (1..200).associate {
            "r$it" to buildJsonObject {
                put("tool", "github_read")
                put("success", true)
                put("private_plan", "repeat me")
            }
        }
        val prompt = GitHubExecutionContinuation.prompt("Fix owner/repo at feature; do not merge", "finish the remaining work", receipts, null)
        assertTrue(prompt.contains("do not merge"))
        assertTrue(prompt.contains("\"retained_receipt_count\":200"))
        assertTrue(prompt.contains("\"included_receipt_count\":64"))
        assertFalse(prompt.contains("repeat me"))
        assertTrue(prompt.length < 12_000)
    }

    @Test fun resumedContextPreservesExactActionSelectorsWithoutCopyingBodiesOrOldPlans() {
        val receipt = buildJsonObject {
            put("tool", "github_issue")
            put(
                "arguments",
                buildJsonObject {
                    put("action", "update")
                    put("issue_number", 42)
                    put("body", "old plan and private content")
                }
            )
        }
        val prompt = GitHubExecutionContinuation.prompt("Fix the selected repository; do not merge", "proceed primary-only", mapOf("receipt-1" to receipt), null, corrective = false)
        assertTrue(prompt.contains("\"issue_number\":42"))
        assertTrue(prompt.contains("proceed primary-only"))
        assertTrue(prompt.contains("do not merge"))
        assertFalse(prompt.contains("old plan and private content"))
        assertFalse(prompt.contains("previous answer proposed"))
        assertFalse(prompt.contains("single corrective attempt"))
    }
}
