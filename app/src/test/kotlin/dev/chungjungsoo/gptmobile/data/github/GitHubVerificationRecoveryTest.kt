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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerificationRecoveryTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }
    private fun receipt(result: String, action: String = "checks", epoch: Int = 0) = json("""{"trusted":true,"success":true,"observed_at_ms":1000,"epoch":$epoch,"tool":"github_context","arguments":{"action":"$action"},"result":$result}""")

    @Test fun stressRequestWithoutExecutionsCannotPublishInventedMetrics() = runBlocking {
        val book = GitHubEvidenceBook("binding", "o/r", "main", emptySet(), { 1000 })
        val session = GitHubVerifiedSession(answer("35 operations, 97% success, 12 seconds"), book, "Do a stress test of all tools", { 8192 }, { error("No model formatter for test coverage") }, {}, {}, { 1000 })
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("Task outcome: partial acceptance"))
        assertTrue(text.contains("Recorded tool receipts: 0; dispatched tool calls: 0"))
        assertFalse(text.contains("97%") || text.contains("35 operations") || text.contains("12 seconds"))
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals("BLOCKED", terminal.taskOutcome)
        assertEquals("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", terminal.errorCode)
        assertEquals(JsonPrimitive("tool_admission"), terminal.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("retryable"))
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun supportedPartialAnswerDeliversPresentationWhilePreservingTheIncompleteTask() = runBlocking {
        val task = "Inspect the workflow failure"
        val source = receipt("""{"state":"RUNNING","checks":1}""")
        val (text, report) = GitHubEvidencePolicy.partial(mapOf("r" to source), 1000, 0, task, "GITHUB_REVIEW_INVALID_VERDICT")
        assertTrue(text.contains("RUNNING"))
        assertEquals(JsonPrimitive(task), report["task"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertEquals(JsonPrimitive(false), report["resume_required"])
        assertEquals(JsonPrimitive(true), report["answer_available"])
        assertEquals(JsonPrimitive("GITHUB_REVIEW_INVALID_VERDICT"), report["failure_code"])
        val book = GitHubEvidenceBook("binding", "o/r", "main", emptySet(), { 1000 })
        val gate = GitHubVerifiedSession(answer("private"), book, task, { 8192 }, { answer("bad JSON") }, {}, {}, { 1000 })
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals("PARTIAL", terminal.taskOutcome)
        assertEquals("GITHUB_EXTRACTION_INVALID_RESPONSE", terminal.errorCode)
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun invalidReviewRepairsFormatAndAllowsOnlyOneSavedRetryBeforeBlocking() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        val prompts = mutableListOf<String>()
        val gate = GitHubReviewedSession(answer("private draft"), GitHubReviewEvidence(), "task", 8192, { prompt, _ ->
            prompts += prompt
            answer("not JSON")
        }, {}, checkpoint = checkpoint())
        val first = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, prompts.size)
        assertFalse(prompts[0] == prompts[1])
        assertTrue(prompts[1].contains("format_repair"))
        assertEquals("GITHUB_REVIEW_INVALID_VERDICT", first.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(JsonPrimitive("BLOCKED"), rows.getValue("active")["state"])
        val noRegeneration = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Saved draft must not regenerate or repeat tools")
        }
        val resumed = GitHubReviewedSession(noRegeneration, GitHubReviewEvidence(), "task", 8192, { prompt, _ ->
            prompts += prompt
            answer("still not JSON")
        }, {}, checkpoint = checkpoint())
        val second = resumed.streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_INVALID_VERDICT", second.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(4, prompts.size)
        assertTrue(prompts[2].contains("private draft"))
        assertTrue(prompts[3].contains("format_repair"))
        assertEquals(JsonPrimitive(1), rows.getValue("active")["format_retries"])
        assertEquals(JsonPrimitive("BLOCKED"), rows.getValue("active")["state"])
        assertTrue(second.none { it is ProviderEvent.TextDelta })
        val exhausted = GitHubReviewedSession(noRegeneration, GitHubReviewEvidence(), "task", 8192, { _, _ -> error("Exhausted review must not repeat") }, {}, checkpoint = checkpoint())
        val third = exhausted.streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", third.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(third.none { it is ProviderEvent.TextDelta })
        assertEquals(4, prompts.size)
    }

    @Test fun malformedVerdictCanRecoverOnceWithoutRerunningTools() = runBlocking {
        var reviews = 0
        val gate = GitHubReviewedSession(answer("checked answer"), GitHubReviewEvidence(), "task", 8192, { _, _ ->
            answer(if (++reviews == 1) "bad JSON" else """{"schema_version":1,"verdict":"PASS","reason":"Retained evidence checked.","reviewed_ids":[],"issues":[],"verified_claims":[]}""")
        }, {})
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, reviews)
        assertEquals("checked answer", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun statusUsesFourBoundedReadsAndPinsChecksAndCommitsToObservedCommit() {
        val tools = listOf("github_context", "github_pr").map { AgentToolDefinition(it, it, JsonObject(emptyMap())) }
        val sha = "a".repeat(40)
        assertEquals(JsonPrimitive("status"), GitHubStatusRequest.nextCall(tools, emptyMap(), 1000L)!!.arguments["action"])
        val receipts = linkedMapOf("s" to receipt("""{"commit_sha":"$sha"}""", "status"))
        val checks = GitHubStatusRequest.nextCall(tools, receipts, 1000L)!!
        assertEquals(JsonPrimitive("checks"), checks.arguments["action"])
        assertEquals(JsonPrimitive(sha), checks.arguments["commit_sha"])
        receipts["c"] = JsonObject(receipt("{}") + ("arguments" to checks.arguments))
        assertEquals(JsonPrimitive(5), GitHubStatusRequest.nextCall(tools, receipts, 1000L)!!.arguments["per_page"])
        receipts["p"] = JsonObject(receipt("{}", "list") + ("tool" to JsonPrimitive("github_pr")))
        val commits = requireNotNull(GitHubStatusRequest.nextCall(tools, receipts, 1000L))
        assertEquals(JsonPrimitive("commits"), commits.arguments["action"])
        assertEquals(JsonPrimitive(sha), commits.arguments["commit_sha"])
        assertEquals(JsonPrimitive(5), commits.arguments["per_page"])
        receipts["m"] = JsonObject(receipt("{}", "commits") + ("arguments" to commits.arguments))
        assertEquals(null, GitHubStatusRequest.nextCall(tools, receipts, 1000L))
    }

    @Test fun statusSeparatesSkippedChecksAndRejectsAnotherRevision() {
        val current = "a".repeat(40)
        val old = "b".repeat(40)
        val status = receipt("""{"data":{"repository":{"full_name":"o/r"},"commit_sha":"$current"}}""", "status")
        val checks = receipt("""{"commit_sha":"$current","state":"PASSED","checks":2,"check_details":{"check_runs":[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"skipped"}]}}""")
        val text = GitHubEvidencePolicy.status(linkedMapOf("s" to status, "c" to checks), 1000, 0)!!.first
        assertTrue(text.contains("1 skipped") && text.contains("1 success"))
        assertTrue(text.contains("Skipped checks are not executed tests"))
        val stale = receipt("""{"commit_sha":"$old","state":"FAILED","checks":1,"check_details":{"check_runs":[{"status":"completed","conclusion":"failure"}]}}""")
        val corrected = GitHubEvidencePolicy.status(linkedMapOf("s" to status, "c" to stale), 1000, 0)!!.first
        assertFalse(corrected.contains("FAILED") || corrected.contains("1 failure") || corrected.contains(old))
    }

    @Test fun anonymousReceiptsAreDistinctAndRepeatedCallIdsAreCountedOnce() {
        val first = receipt("{}")
        val identified = JsonObject(first + ("call_id" to JsonPrimitive("one-call")))
        val rows = GitHubTaskCoverage.rows(mapOf("a" to first, "b" to first, "c" to identified, "d" to identified))
        assertEquals(JsonPrimitive(3), rows.single()["successful_observations"])
    }

    @Test fun matchingNumberInWrongFieldCannotBecomeAPullRequestCount() {
        val result = receipt("""{"checks":3}""")
        val candidate = json("""{"claims":[{"kind":"inferred","text":"There are 3 open pull requests","sources":[{"receipt_id":"r","pointer":"/checks","value":3}]}]}""")
        val text = GitHubEvidencePolicy.render(candidate, mapOf("r" to result), 1000, 0).first
        assertFalse(text.contains("3 open pull requests"))
        assertTrue(text.contains("Unverified interpretation"))
    }

    @Test fun historicalWorkflowReadDoesNotMoveBranchHeadButFreshStatusInvalidatesOldChecks() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context", "github_ci"), { 1000 })
        suspend fun observe(id: String, tool: String, args: String, body: String) {
            book.observe(ProviderEvent.ToolCall(id, tool, json(args)), AgentToolResult(id, ToolResultContent.Json(json(body)), false))
        }
        val first = "a".repeat(40)
        val old = "b".repeat(40)
        val next = "c".repeat(40)
        observe("status", "github_context", """{"action":"status"}""", """{"repository":{"head":{"sha":"$first"}}}""")
        observe("history", "github_ci", """{"action":"run","run_id":1}""", """{"head_sha":"$old"}""")
        assertEquals(0L, book.snapshot().second)
        observe("checks", "github_context", """{"action":"checks","commit_sha":"$first"}""", """{"commit_sha":"$first","checks":2}""")
        observe("fresh", "github_context", """{"action":"status"}""", """{"repository":{"head":{"sha":"$next"}}}""")
        val (receipts, epoch) = book.snapshot()
        assertEquals(1L, epoch)
        val id = receipts.entries.single { it.value["call_id"] == JsonPrimitive("checks") }.key
        val claim = json("""{"receipt_id":"$id","pointer":"/checks","value":2}""")
        assertEquals(JsonPrimitive("STALE"), GitHubEvidencePolicy.verify(claim, receipts, 1000, epoch)["status"])
    }
}
