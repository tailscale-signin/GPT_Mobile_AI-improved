package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchCheckpoint
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sanitized facts from the captured empty-PR and selected-revision CI sessions, through the real runner. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GitHubReceiptCompletionPipelineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun completeEmptyPullQueryFinishesBeforeThe53TokenReplayAllowanceWithoutAnyModel() = runTest {
        val task = "List open PRs"
        var calls = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_pr__fixture", "Read pull requests", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls++
                assertEquals(JsonPrimitive("open"), arguments["state"])
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(Json.parseToJsonElement("""{"pull_requests":[],"page":1,"per_page":20,"has_more":false,"upstream_has_more":false,"complete":true,"pagination_progress":{"remote_page":1,"terminal_page":1,"upstream_rows_returned":0,"unique_records_observed":0,"contiguous_from_first_page":true,"upstream_changed_during_traversal":false,"complete":true}}""")),
                    false,
                    dispatched = true
                )
            }
        }
        val book = GitHubEvidenceBook("empty-pr", "owner/repo", "main", setOf(tool.definition.name), now = { 1000L })
        book.retainTaskContract(task, 1, listOf(tool.definition), 24)
        val noProvider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("A complete exact lookup needs no provider round")
        }
        var report: JsonObject? = null
        val session = GitHubVerifiedSession(
            noProvider, book, task, { 53 }, { error("A complete lookup needs no claim extractor") }, {}, {},
            now = { 1000L }, computationDispatcher = StandardTestDispatcher(testScheduler), authorizedTools = listOf(tool.definition),
            remainingToolCalls = { 24 - calls }, executionCallLimit = 24, onPublication = { report = it }
        )
        val file = File(temporaryFolder.root, "empty-pr-journal.json")
        val journal = ToolBatchCheckpoint(file, "empty-pr-grant")
        val runEvents = AgentRunner(AgentRunLimits(githubTask = true, githubTaskRequest = task, maxReplayTokens = 53, maxReplayResultTokens = 53), checkpoint = journal)
            .run(session, listOf(tool)).toList()
        val events = runEvents.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(1, calls)
        assertEquals(1, runEvents.filterIsInstance<AgentRunEvent.ToolStarted>().size)
        assertEquals(1, runEvents.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertEquals(ProviderEvent.Completed, events.last())
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("No open pull requests"))
        assertEquals(JsonPrimitive(true), report?.get("completion_verified"))
        assertEquals(JsonPrimitive("REQUESTED_LOOKUP"), report?.get("completion_scope"))
        val publication = requireNotNull(report?.get(GitHubAcceptancePublicationEvidence.KEY)) as JsonObject
        assertEquals(JsonPrimitive("STATUS"), publication["acceptance_publication_source"])
        assertEquals(JsonPrimitive("STATUS_SNAPSHOT"), publication["acceptance_publication_entry"])
        val budget = requireNotNull(report?.get("stage_budget")) as JsonObject
        assertEquals(JsonPrimitive(1), budget["used_calls"])
        assertEquals(JsonPrimitive(23), budget["remaining_calls"])
        val restored = ToolBatchCheckpoint(file, "empty-pr-grant").recoverySnapshot()
        assertTrue(restored.pending.isEmpty())
        assertEquals(1, restored.completedCount)
        assertEquals(1, restored.dispatchedCount)
        assertEquals(0, restored.unknownCount)
        assertFalse(events.any { it is ProviderEvent.Failed })
    }

    @Test fun completeSelectedRevisionFailureQueryFinishesBeforeHistoricalFallbackOrAnotherProvider() = runTest {
        val task = GitHubWorkflowPreset.selected("diagnose_ci").prompt()
        val head = "0e625e250117475446b8f216df2501ddbef168f2"
        val requested = mutableListOf<ProviderEvent.ToolCall>()
        val tools = listOf("github_context_inspect__fixture", "github_ci__fixture").map { name ->
            object : AgentTool {
                override val definition = AgentToolDefinition(name, "Read captured CI observations", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    requested += ProviderEvent.ToolCall(callId, name, arguments)
                    val value = when (arguments["action"]) {
                        JsonPrimitive("resolve") -> """{"commit_sha":"$head","ref":"main"}"""
                        JsonPrimitive("runs") -> """{"total_count":0,"page":1,"per_page":100,"returned":0,"workflow_runs":[],"has_more":false,"upstream_has_more":false,"history_truncated":false,"traversal_complete":true,"history_complete":true,"complete":true,"pagination_progress":{"remote_page":1,"terminal_page":1,"upstream_rows_returned":0,"unique_records_observed":0,"reported_total":0,"contiguous_from_first_page":true,"upstream_changed_during_traversal":false,"complete":true}}"""
                        else -> error("No historical run/job/log read is needed: $arguments")
                    }
                    return AgentToolResult(callId, ToolResultContent.Json(Json.parseToJsonElement(value)), false, dispatched = true)
                }
            }
        }
        val book = GitHubEvidenceBook("clean-ci", "owner/repo", "main", tools.mapTo(mutableSetOf()) { it.definition.name }, now = { 1000L })
        book.retainTaskContract(task, 2, tools.map { it.definition }, 48, acceptedHeadSha = head)
        var rounds = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds == 1) { "The exact absence query already answers this diagnosis" }
                emit(
                    ProviderEvent.ToolCall(
                        "resolve",
                        "github_context_inspect__fixture",
                        buildJsonObject {
                            put("action", "resolve")
                            put("owner", "owner")
                            put("repo", "repo")
                        }
                    )
                )
                emit(
                    ProviderEvent.ToolCall(
                        "failures",
                        "github_ci__fixture",
                        buildJsonObject {
                            put("action", "runs")
                            put("head_sha", head)
                            put("status", "failure")
                            put("per_page", 100)
                        }
                    )
                )
                emit(ProviderEvent.Completed)
            }
        }
        var report: JsonObject? = null
        val session = GitHubVerifiedSession(
            provider, book, task, { 8192 }, { error("A bounded absence proof needs no claim extractor") }, {}, {},
            now = { 1000L }, computationDispatcher = StandardTestDispatcher(testScheduler), authorizedTools = tools.map { it.definition },
            remainingToolCalls = { 48 - requested.size }, executionCallLimit = 48, workspaceHead = head, onPublication = { report = it }
        )
        val events = AgentRunner(AgentRunLimits(githubTask = true, githubTaskRequest = task, maxConcurrentTools = 1))
            .run(session, tools).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(1, rounds)
        assertEquals(2, requested.size)
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(JsonPrimitive(head), report?.get("head_sha"))
        assertEquals(JsonPrimitive(true), report?.get("failure_query_complete"))
        assertEquals(JsonPrimitive(false), report?.get("build_health_verified"))
        assertEquals(JsonPrimitive(false), report?.get("new_test_execution_verified"))
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("No new tests were run"))
    }

    @Test fun savedUsefulReportSurvivesALaterGenericInterruptionWithItsOriginalFailure() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("saved", "owner/repo", "main", emptySet(), load = { rows[it] }, save = { id, row -> rows[id] = row })
        val verdict = Json.parseToJsonElement("""{"task_outcome":"PARTIAL","completion_verified":false,"claims":[{"status":"VERIFIED"}]}""").jsonObject
        assertTrue(book.retainAnswerIfCurrent("Current revision has no failed workflow in its captured query. Historical APK size failure is separate.", verdict, 0, emptySet()))
        val session = GitHubVerifiedSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("No provider")
            },
            book,
            "Investigate CI evidence",
            { 8192 },
            { error("No verifier") },
            {},
            {}
        )
        val failure = ProviderEvent.Failed(
            "LOCAL_SERVICE_UNREACHABLE: connection interrupted",
            errorCode = "LOCAL_SERVICE_UNREACHABLE",
            failureDetails = buildJsonObject {
                put(
                    "original_interruption",
                    buildJsonObject {
                        put("code", "429")
                        put("retry_after_ms", 5000)
                    }
                )
            }
        )
        val events = session.retainedFailureResponse(failure).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Historical APK size failure is separate"))
        assertEquals(failure.failureDetails, (events.last() as ProviderEvent.Failed).failureDetails)
    }

    @Test fun repeatedInterruptionsKeepOneCanonicalFindingsBody() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("canonical", "owner/repo", "main", emptySet(), load = { rows[it] }, save = { id, row -> rows[id] = row })
        val original = "The current revision's captured failure query is empty. No new tests were executed."
        val verified = Json.parseToJsonElement("""{"task_outcome":"PARTIAL","answer_verified":true,"evidence_receipt_ids":["source-receipt"]}""").jsonObject
        assertTrue(book.retainAnswerIfCurrent(original, verified, 0, emptySet()))
        repeat(3) { attempt ->
            assertEquals(original, book.retainedFindings())
            val checkpoint = JsonObject(verified + ("retained_findings_delivered" to JsonPrimitive(true)))
            assertTrue(book.retainAnswerIfCurrent("## Retained findings\n\n$original\n\n## Current checkpoint\n\nInterruption $attempt", checkpoint, 0, emptySet()))
            assertEquals(original, book.retainedFindings())
        }
    }

    @Test fun receiptFallbacksCannotBecomeEarlierFindingsAndFullReportShowsOriginalOnlyOnce() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("review", "o/r", "main", emptySet(), load = { rows[it] }, save = { id, row -> rows[id] = row })
        val original = "A verified finding on line 12"
        val verified = Json.parseToJsonElement("""{"answer_verified":true,"evidence_receipt_ids":["r"]}""").jsonObject
        val fallback = JsonObject(verified + ("fallback" to JsonPrimitive("receipt_only")))
        repeat(2) {
            book.retainAnswerIfCurrent("Receipt-only checkpoint $it", fallback, 0, emptySet())
            assertEquals(null, book.retainedFindings())
        }
        book.retainAnswerIfCurrent(original, verified, 0, emptySet())
        val delivered = JsonObject(fallback + mapOf("retained_findings_delivered" to JsonPrimitive(true), "retained_findings_sha256" to JsonPrimitive(GitHubEvidenceBook.digest(original))))
        val combined = "$original\n\nCurrent checkpoint"
        book.retainAnswerIfCurrent(combined, delivered, 0, emptySet())
        assertEquals(original, book.retainedFindings())
        assertEquals(combined, book.retainedAnswer(includeEarlierFindings = true)?.first)
    }

    @Test fun aRepeatedCallCannotReplaceNativeProofOrRefreshItsCaptureTime() = runTest {
        var clock = 1000L
        val call = ProviderEvent.ToolCall(
            "empty",
            "github_pr__fixture",
            buildJsonObject {
                put("action", "list")
                put("state", "open")
            }
        )
        val book = GitHubEvidenceBook("immutable", "owner/repo", "main", setOf(call.name), now = { clock })
        val native = AgentToolResult(call.callId, ToolResultContent.Json(Json.parseToJsonElement("""{"pull_requests":[],"page":1,"returned":0,"has_more":false,"complete":true}""")), false, dispatched = true)
        book.observe(call, native)
        val captured = book.snapshot().first
        clock += 121_000
        book.observe(call, native.copy(content = ToolResultContent.Text("Projected output plus a final response instruction")))
        assertEquals(captured, book.snapshot().first)
        assertEquals(null, GitHubLookupObservation.render("List open PRs", book.snapshot().first, "owner/repo", "main", clock, 0))
    }
}
