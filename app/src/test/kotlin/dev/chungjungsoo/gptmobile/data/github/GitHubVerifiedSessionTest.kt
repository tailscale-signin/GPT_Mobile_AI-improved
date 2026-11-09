package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerifiedSessionTest {
    @Test fun apparentlyCompleteReportRemainsPartialAtTheLiveBoundaryWhenWriteOutcomeIsUnknown() = runBlocking {
        val saved = mutableListOf<JsonObject>()
        var published: JsonObject? = null
        val sha = "a".repeat(40)
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf("github_context", "github_pr", "github_write"), now = { 1000L }, save = { id, value -> if (id.startsWith("verification_current_")) saved += value })
        book.observe(
            ProviderEvent.ToolCall("lost-write", "github_write", Json.parseToJsonElement("""{"action":"commit","branch":"feature"}""").jsonObject),
            AgentToolResult("lost-write", ToolResultContent.Text("Response lost"), true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN", dispatched = true)
        )
        val sources = listOf(
            Triple("github_context", "status", """{"repository":{"full_name":"o/r"},"branch":"feature","commit_sha":"$sha","latest_workflow":{"head_sha":"$sha","head_branch":"feature","status":"completed","conclusion":"success"}}"""),
            Triple("github_context", "checks", """{"commit_sha":"$sha","checks":0,"statuses":0,"check_details":{"check_runs":[]}}"""),
            Triple("github_pr", "list", """{"pull_requests":[]}"""),
            Triple("github_context", "commits", """{"commit_sha":"$sha","items":[{"sha":"$sha"}]}""")
        )
        for ((tool, action, body) in sources) {
            book.observe(
                ProviderEvent.ToolCall(action, tool, JsonObject(mapOf("action" to JsonPrimitive(action)))),
                AgentToolResult(action, ToolResultContent.Json(JsonObject(mapOf("data" to Json.parseToJsonElement(body)))), false)
            )
        }
        val (receipts, epoch) = book.snapshot()
        assertEquals(JsonPrimitive("COMPLETED"), requireNotNull(GitHubEvidencePolicy.status(receipts, 1000, epoch, "status")).second["task_outcome"])
        val session = GitHubVerifiedSession(answer("unused"), book, "status", { 8192 }, { error("Status is built from receipts") }, {}, {}, now = { 1000L }, onPublication = { published = it })
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.Completed })
        val failure = events.last() as ProviderEvent.Failed
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("remote outcome unconfirmed"))
        assertEquals(JsonPrimitive("PARTIAL"), published?.get("task_outcome"))
        assertEquals(JsonPrimitive(false), published?.get("completion_verified"))
        assertEquals(JsonPrimitive(true), published?.get("response_delivered"))
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(events.filterIsInstance<ProviderEvent.TextDelta>().single().text)), published?.get("response_sha256"))
        assertEquals(JsonPrimitive("PARTIAL"), saved.last()["task_state"])
        assertEquals(JsonPrimitive(true), saved.last()["response_delivered"])
    }

    @Test fun checklistPlanningYieldsToTheComputationDispatcher() = runTest {
        val tools = listOf(AgentToolDefinition("github_context", "Status", JsonObject(emptyMap())))
        val gate = GitHubVerifiedSession(
            answer("unused"), GitHubEvidenceBook("b", "o/r", "main", setOf("github_context")),
            "test all tools", { 8192 }, { error("No model for checklist planning") }, {}, {},
            computationDispatcher = StandardTestDispatcher(testScheduler), authorizedTools = tools
        )
        val result = async(start = CoroutineStart.UNDISPATCHED) { gate.streamRound(tools, emptyList()).toList() }
        assertFalse(result.isCompleted)
        testScheduler.runCurrent()
        assertEquals(JsonPrimitive("status"), result.await().filterIsInstance<ProviderEvent.ToolCall>().single().arguments["action"])
    }

    @Test fun invalidReviewerCanRecoverOnlyReceiptBackedClaimsFromPrivateDraft() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000L })
        val call = ProviderEvent.ToolCall("read", "github_context", Json.parseToJsonElement("""{"action":"checks"}""").jsonObject)
        book.observe(call, AgentToolResult("read", ToolResultContent.Text("""{"checks":3}"""), false))
        val id = book.snapshot().first.keys.single()
        val failure = ProviderEvent.Failed("Reviewer returned invalid JSON", errorCode = "GITHUB_REVIEW_INVALID_VERDICT")
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(failure)
        }
        var extractions = 0
        val session = GitHubVerifiedSession(
            source, book, "Explain the integration issues", { 8192 },
            {
                extractions++
                answer("""{"claims":[{"kind":"fact","receipt_id":"$id","pointer":"/checks","value":3}]}""")
            }, {}, {}, now = { 1000L }, privateReviewCandidate = { "UNVERIFIED: everything is fixed" }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("3"))
        assertFalse(text.contains("everything is fixed"))
        assertEquals(1, extractions)
        assertEquals("PARTIAL", (events.last() as ProviderEvent.Failed).taskOutcome)
        val noEvidence = GitHubVerifiedSession(source, GitHubEvidenceBook("empty", "o/r", "main", emptySet()), "Explain", { 8192 }, { error("No receipts") }, {}, {})
        val noEvidenceEvents = noEvidence.streamRound(emptyList(), emptyList()).toList()
        assertEquals(failure, noEvidenceEvents.last())
        assertTrue(noEvidenceEvents.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("draft remains unverified"))
        assertTrue(noEvidenceEvents.none { it == ProviderEvent.Completed })
    }

    @Test fun assistantFailureQuestionUsesOnlyHistoricalDiagnostics() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet(), latestVerdict = {
            Json.parseToJsonElement("""{"binding":"b","verification_failure":"GITHUB_TASK_INCOMPLETE"}""").jsonObject
        })
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("No unrelated CI investigation")
        }
        for (task in listOf("why are you failing?", "GitHub workspace: o/r\nBranch: main\n\nWhy is your response empty?")) {
            val gate = GitHubVerifiedSession(source, book, task, { 8192 }, { error("No extraction model") }, {}, {})
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("GITHUB_TASK_INCOMPLETE"))
            assertEquals(ProviderEvent.Completed, events.last())
        }
    }

    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }

    @Test fun greetingsUseNoProviderToolsOrVerification() = runBlocking {
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("No generation for a plain greeting")
        }
        for (task in listOf("hi", "Hello!", " hey ", "GitHub workspace: o/r\nBranch: main\n\nhello")) {
            val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), task, { 8000 }, { error("No extraction") }, { error("No repository claims") }, {})
            val events = gate.streamRound(listOf(AgentToolDefinition("github_context", "Status", JsonObject(emptyMap()))), emptyList()).toList()
            assertEquals(listOf(ProviderEvent.TextDelta("Hello! How can I help?", OutputLineage(OutputOrigin.APPLICATION_REPORT)), ProviderEvent.Completed), events)
        }
    }

    @Test fun statusAliasesCollectTheSamePinnedFourReads() {
        val tools = listOf("github_context", "github_pr").map { AgentToolDefinition(it, "Read", JsonObject(emptyMap())) }
        for (task in listOf("status", "status of repo", "github status", "Status of the repository?")) {
            assertTrue(GitHubStatusRequest.matches(task))
            val receipts = linkedMapOf<String, JsonObject>()
            val actions = mutableListOf<String>()
            repeat(4) { index ->
                val call = requireNotNull(GitHubStatusRequest.nextCall(tools, receipts, 1000L))
                actions += call.arguments.getValue("action").toString()
                if (index == 1 || index == 3) assertEquals(JsonPrimitive("a".repeat(40)), call.arguments["commit_sha"])
                receipts[index.toString()] = JsonObject(mapOf("tool" to JsonPrimitive(call.name), "arguments" to call.arguments, "observed_at_ms" to JsonPrimitive(1000L), "success" to JsonPrimitive(true), "result" to JsonObject(mapOf("commit_sha" to JsonPrimitive("a".repeat(40))))))
            }
            assertEquals(listOf("\"status\"", "\"checks\"", "\"list\"", "\"commits\""), actions)
            assertEquals(null, GitHubStatusRequest.nextCall(tools, receipts, 1000L))
        }
    }

    @Test fun failedContinuationRetainsOriginalCauseAndTaskWithPartialDelivery() = runBlocking {
        val reports = mutableListOf<JsonObject>()
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), save = { id, value -> if (id.startsWith("verification_current_")) reports += value })
        book.observe(
            ProviderEvent.ToolCall("checks", "github_context", JsonObject(mapOf("action" to JsonPrimitive("checks")))),
            AgentToolResult("checks", ToolResultContent.Json(JsonObject(mapOf("checks" to JsonPrimitive(1)))), false)
        )
        val gate = GitHubVerifiedSession(answer("unused"), book, "inspect checks and recent changes", { 8192 }, { error("No extraction after failure") }, {}, {})
        val failure = ProviderEvent.Failed("Gateway could not continue after tool result; job=job-7", errorCode = "client_tool_results_mismatch")
        val events = gate.retainedFailureResponse(failure).toList()
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals(failure.message, terminal.message)
        assertEquals(failure.errorCode, terminal.errorCode)
        assertEquals("PARTIAL", terminal.taskOutcome)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains(failure.message))
        val report = reports.last()
        assertEquals(JsonPrimitive(true), report["response_delivered"])
        assertTrue(report.toString().contains("inspect checks and recent changes"))
        assertTrue(report.toString().contains("original_failure"))
    }

    @Test fun absentIntegrationCatalogStopsBeforeProviderDenialCanBeDispatched() = runTest {
        val task = "Test GitHub tools and report."
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet())
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("No provider request can repair an absent tool catalog")
        }
        val gate = GitHubVerifiedSession(provider, book, task, { 8192 }, { error("No verifier without operation evidence") }, {}, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList()
        val visible = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val terminal = visible.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("BLOCKED", terminal.taskOutcome)
        assertEquals("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", terminal.errorCode)
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("retryable"))
        assertTrue(book.snapshot().first.isEmpty())
        assertTrue(visible.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Recorded tool receipts: 0; dispatched tool calls: 0"))
        assertFalse(visible.any { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
    }

    @Test fun providerDenialRetainsPendingAcceptanceWithoutClaimingPartialVerification() = runTest {
        for (task in listOf("Inspect the repository", "Explain the repository issues")) {
            var rounds = 0
            val savedCheckpoints = mutableListOf<JsonObject>()
            val failure = ProviderEvent.Failed(
                "HTTP 402: provider budget restriction",
                errorCode = "PAYMENT_REQUIRED",
                failureDetails = JsonObject(mapOf("status" to JsonPrimitive(402)))
            )
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    emit(failure)
                }
            }
            val gate = GitHubVerifiedSession(
                provider,
                GitHubEvidenceBook("b", "o/r", "main", emptySet(), save = { id, checkpoint -> if (id.startsWith("verification_current_")) savedCheckpoints += checkpoint }),
                task,
                { 8192 },
                { error("A provider denial must not open a verifier") },
                {},
                {}
            )
            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList()
            val terminal = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals(failure, terminal)
            assertEquals(1, rounds)
            assertTrue(savedCheckpoints.isNotEmpty())
            assertTrue(savedCheckpoints.all { it["completion_verified"] == JsonPrimitive(false) && it["response_delivered"] == JsonPrimitive(false) })
            assertEquals(JsonPrimitive("INTERRUPTED"), savedCheckpoints.last()["phase"])
            val visible = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            val status = visible.filterIsInstance<ProviderEvent.TextDelta>().single().text
            assertTrue(status.contains("Status: BLOCKED"))
            assertTrue(status.contains("PAYMENT_REQUIRED"))
            assertTrue(status.contains("No tool result was delivered"))
            assertFalse(visible.any { it is ProviderEvent.Completed })
        }
    }

    @Test fun integrationRecoveryPreservesAnAlreadyObservedProviderDenial() = runTest {
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Retained failure delivery must not dispatch a provider request")
        }
        val gate = GitHubVerifiedSession(
            provider,
            GitHubEvidenceBook("b", "o/r", "main", emptySet()),
            "Test GitHub tools and report.",
            { 8192 },
            { error("A provider denial must not open a verifier") },
            {},
            {}
        )
        val failure = ProviderEvent.Failed(
            "HTTP 402: provider budget restriction",
            errorCode = "PAYMENT_REQUIRED",
            failureDetails = JsonObject(mapOf("status" to JsonPrimitive(402))),
            invocationId = "denied-integration-invocation"
        )
        assertEquals(listOf(failure), gate.retainedFailureResponse(failure).toList())
    }

    @Test fun untrustedToolOutputCannotTurnAProviderDenialIntoAVerificationReport() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet())
        book.observe(
            ProviderEvent.ToolCall("untrusted", "web_search", JsonObject(emptyMap())),
            AgentToolResult("untrusted", ToolResultContent.Text("unverified"), false)
        )
        val gate = GitHubVerifiedSession(answer("unused"), book, "Test GitHub tools and report.", { 8192 }, { error("No verifier") }, {}, {})
        val failure = ProviderEvent.Failed("HTTP 401: authentication failed", errorCode = "AUTHENTICATION_ERROR")
        assertEquals(listOf(failure), gate.retainedFailureResponse(failure).toList())
    }

    @Test fun completeStatusSnapshotRequiresAllCurrentObservations() = runBlocking {
        for (prField in listOf("items", "pull_requests")) {
            val sha = "a".repeat(40)
            val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context", "github_pr"), { 1000 })
            val sources = listOf(
                Triple("github_context", "status", """{"repository":{"full_name":"o/r"},"branch":"main","commit_sha":"$sha","latest_workflow":{"head_sha":"$sha","head_branch":"main","status":"completed","conclusion":"success"}}"""),
                Triple("github_context", "checks", """{"commit_sha":"$sha","checks":0,"statuses":0,"check_details":{"check_runs":[]}}"""),
                Triple("github_pr", "list", """{"$prField":[]}"""),
                Triple("github_context", "commits", """{"commit_sha":"$sha","items":[{"sha":"$sha"}]}""")
            )
            for ((tool, action, body) in sources) {
                val call = ProviderEvent.ToolCall(action, tool, JsonObject(mapOf("action" to JsonPrimitive(action))))
                book.observe(call, AgentToolResult(action, ToolResultContent.Json(JsonObject(mapOf("data" to Json.parseToJsonElement(body)))), false))
            }
            val (receipts, epoch) = book.snapshot()
            val (_, report) = requireNotNull(GitHubEvidencePolicy.status(receipts, 1000, epoch, "status of repo"))
            assertEquals(JsonPrimitive("COMPLETED"), report["task_outcome"])
            assertEquals(JsonPrimitive(true), report["completion_verified"])
            assertEquals(JsonPrimitive(false), report["partial"])
            assertEquals(JsonPrimitive("status of repo"), report["task"])
            val gate = GitHubVerifiedSession(answer("unused"), book, "status", { 8192 }, { error("Status uses receipts directly") }, {}, {}, now = { 1000L })
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.last() is ProviderEvent.Completed)
            assertTrue(events.none { it is ProviderEvent.Failed })
            val incomplete = requireNotNull(GitHubEvidencePolicy.status(receipts.filterValues { it["tool"] != JsonPrimitive("github_pr") }, 1000, epoch))
            assertEquals(JsonPrimitive("PARTIAL"), incomplete.second["task_outcome"])
            assertTrue(incomplete.first.contains("Unavailable observations"))
        }
    }

    @Test fun isolatedCheckReceiptDoesNotStopRemainingStatusCollection() = runBlocking {
        val book = GitHubEvidenceBook("binding", "o/r", "main", setOf("github_context"), now = { 1000L })
        val call = ProviderEvent.ToolCall("checks", "github_context", Json.parseToJsonElement("""{"action":"checks"}""") as JsonObject)
        book.observe(call, AgentToolResult("checks", ToolResultContent.Text("""{"state":"PASSED","checks":19}"""), false))
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.ToolCall("prs", "github_pr", Json.parseToJsonElement("""{"action":"list"}""") as JsonObject),
                ProviderEvent.Completed
            )
        }
        val gate = GitHubVerifiedSession(provider, book, "status", { 8000 }, { error("No final answer yet") }, {}, {}, now = { 1000L })
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCall>().size)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
    }

    @Test fun testingReportsKeepSavedReceiptsWhileBlockingMissingExecutableTools() = runBlocking {
        val book = GitHubEvidenceBook("binding", "o/r", "main", setOf("github_read"), now = { 1000L })
        val call = ProviderEvent.ToolCall("read", "github_read", Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}","ranges":[{"path":"Test.kt","start_line":1,"end_line":5}]}""") as JsonObject)
        book.observe(call, AgentToolResult("read", ToolResultContent.Text("test source"), false))
        val session = GitHubVerifiedSession(
            answer("all tests passed"),
            book,
            "test all functionality",
            { 8000 },
            { error("Deterministic coverage must not call the extraction model") },
            {},
            {},
            now = { 1000L }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("partial acceptance"))
        assertTrue(text.contains("github_read / source read"))
        assertFalse(text.contains("all tests passed"))
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals("BLOCKED", terminal.taskOutcome)
        assertEquals("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", terminal.errorCode)
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("dispatched"))
        assertEquals(1, book.snapshot().first.size)
    }

    @Test fun offlineProviderKeepsItsConnectionErrorInsteadOfBecomingAVerificationError() = kotlinx.coroutines.runBlocking {
        val provider = object : dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession {
            override fun streamRound(tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, exchanges: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange>) = kotlinx.coroutines.flow.flowOf(dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Completed)
        }
        val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("must not verify an offline model") }, {}, {})
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT")
        val events = gate.retainedFailureResponse(failure).toList()
        val terminal = events.single() as dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed
        assertEquals("LOCAL_SERVICE_UNREACHABLE", terminal.errorCode)
        assertEquals(failure.message, terminal.message)
    }

    @Test fun statusCollectsChecksWithoutModelPlanningOrVerifierRound() = runTest {
        var rounds = 0
        var executions = 0
        var publicationChecks = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_context", "Status", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Json(Json.parseToJsonElement("""{"schema":"github.operation.v1","data":{"repository":{"full_name":"o/r","default_branch":"main"},"open_pull_request_count":0,"latest_workflow":{"name":"Build","status":"completed","conclusion":"success"}}}""")), false)
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds == 1) { "Status must not drift into source inspection" }
                emit(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""") as JsonObject))
                emit(ProviderEvent.Completed)
            }
        }
        val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), { 1000 }), "status", { 8192 }, { error("Receipt-based status needs no model formatter") }, { publicationChecks++ }, {}, { 1000 }, StandardTestDispatcher(testScheduler))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(read)).toList()
        val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val text = published.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertEquals(0, rounds)
        assertEquals(3, executions)
        assertEquals(2, publicationChecks)
        assertTrue(text.startsWith("# Repository status"))
        assertTrue(text.contains("| Open pull requests | 0 |"))
        assertTrue(text.contains("| Workflow result | success |"))
        assertFalse(text.contains("/data/") || text.contains("ev_") || text.contains("clean state"))
        assertEquals("PARTIAL", (published.last() as ProviderEvent.Failed).taskOutcome)
    }

    @Test fun runnerRetriesTransientFailuresBeforePublishingWithoutRepeatingCompletedTools() = runTest {
        for (persistent in listOf(false, true)) {
            var rounds = 0
            var dispatches = 0
            var extractions = 0
            val read = object : AgentTool {
                override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatches++
                    return AgentToolResult(callId, ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(3)))), false)
                }
            }
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    when {
                        rounds == 1 -> {
                            emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
                            emit(ProviderEvent.Completed)
                        }
                        persistent || rounds == 2 -> emit(ProviderEvent.Failed("Connection reset"))
                        else -> {
                            assertTrue(exchanges.isNotEmpty())
                            emit(ProviderEvent.TextDelta("private candidate"))
                            emit(ProviderEvent.Completed)
                        }
                    }
                }
            }
            val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 }), "fix", { 8192 }, {
                extractions++
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {}, { 1000 }, StandardTestDispatcher(testScheduler))
            val events = AgentRunner(AgentRunLimits(githubTask = true, maxRounds = 2)).run(gate, listOf(read)).toList()
            assertEquals(1, dispatches)
            assertEquals(if (persistent) GitHubTaskRecovery.MAX_RECOVERIES + 2 else 3, rounds)
            assertEquals(if (persistent) 0 else 1, extractions)
            val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            assertEquals("PARTIAL", (published.last() as ProviderEvent.Failed).taskOutcome)
            val text = published.filterIsInstance<ProviderEvent.TextDelta>().single().text
            assertEquals(persistent, text.contains("Task interrupted"))
            assertFalse(text.contains("| Count | 3 |"))
            assertFalse(text.contains("private candidate"))
        }
    }

    @Test fun exhaustedToolFrameRepairPublishesRetainedFactsWithoutRepeatingTheRead() = runTest {
        var rounds = 0
        var dispatches = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(3)))), false)
            }
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (++rounds == 1) {
                    emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
                    emit(ProviderEvent.Completed)
                } else {
                    emit(ProviderEvent.Failed("Unparsed tool-call markup", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
                }
            }
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 }), "inspect", { 8192 }, { error("No formatter after exhausted repair") }, {}, {}, { 1000 }, StandardTestDispatcher(testScheduler))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(read)).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(4, rounds)
        assertEquals(1, dispatches)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Task interrupted"))
        assertFalse(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("| Count | 3 |"))
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun terminalFallbackCannotDisclosePrivateDraftAfterRevocation() = runTest {
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("PRIVATE_DRAFT_FROM_REVOKED_SOURCE"), ProviderEvent.Failed("403 forbidden"))
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("no formatter after failure") }, { error("access revoked") }, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertTrue(events.last() is ProviderEvent.Failed)
        val status = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(status.contains("Status: BLOCKED"))
        assertFalse(status.contains("PRIVATE_DRAFT_FROM_REVOKED_SOURCE"))
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test fun unfinishedToolCallCannotBecomePartialCompletionOrDispatch() = runTest {
        var dispatches = 0
        val write = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("unexpected"), false)
            }
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.ToolCall("pending", "github_write", JsonObject(emptyMap())),
                ProviderEvent.Failed("403 forbidden")
            )
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("must not extract") }, { error("must not finalize pending calls") }, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(write)).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(0, dispatches)
        assertTrue(events.last() is ProviderEvent.Failed)
        val status = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(status.contains("Status: BLOCKED"))
        assertTrue(status.contains("No tool result was delivered"))
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test fun oversizedTaskOrDraftUsesRetainedEvidenceWithoutOpeningFormatter() = runBlocking {
        for (largeTask in listOf(false, true)) {
            val gate = GitHubVerifiedSession(answer(if (largeTask) "private" else "private".repeat(1000)), GitHubEvidenceBook("b", "o/r", "main", emptySet()), if (largeTask) "task".repeat(1000) else "task", { 512 }, { error("oversized request must not be dispatched") }, {}, {})
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.single() is ProviderEvent.Failed)
        }
    }

    @Test fun newestReceiptPrefixFitsBudgetAndShrinkingCapacityPreventsDispatch() = runBlocking {
        for (shrink in listOf(false, true)) {
            var limits = 0
            var extractions = 0
            val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 })
            repeat(20) { index ->
                book.observe(ProviderEvent.ToolCall("r$index", "github_read", JsonObject(emptyMap())), AgentToolResult("r$index", ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(index)))), false))
            }
            val gate = GitHubVerifiedSession(answer("private"), book, "task", { if (shrink && ++limits > 1) 1 else 700 }, { prompt ->
                extractions++
                assertTrue(TextTokenEstimate.estimate(prompt) <= 700)
                val catalog = Json.parseToJsonElement(prompt).jsonObject.getValue("catalog").jsonArray
                assertTrue(catalog.size in 1 until 20)
                assertEquals(JsonPrimitive(19), catalog.first().jsonObject.getValue("facts").jsonObject["/count"])
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {}, { 1000 })
            assertEquals("PARTIAL", (gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed).taskOutcome)
            assertEquals(if (shrink) 0 else 1, extractions)
        }
    }

    @Test fun downstreamCollectorFailurePropagatesWithoutAnAlternativePublication() = runBlocking {
        val gate = GitHubVerifiedSession(answer("private"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { answer("invalid JSON") }, {}, {})
        val failure = IllegalStateException("collector stopped")
        val result = runCatching { gate.streamRound(emptyList(), emptyList()).collect { throw failure } }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun stalledChecklistRendersConcreteBlockersWithoutAnotherModelDraft() = runTest {
        var verdict: JsonObject? = null
        val book = GitHubEvidenceBook("task", "o/r", "main", setOf("github_context", "github_ci"), now = { 1000L }, save = { id, row -> if (id.startsWith("verification_current_")) verdict = row }, latestVerdict = { verdict })
        book.observe(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""").jsonObject), AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}"}""")), false))
        val tools = listOf(AgentToolDefinition("github_ci", "logs", Json.parseToJsonElement("""{"properties":{"action":{"const":"logs"}}}""").jsonObject))
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("A coverage gap cannot be repaired by a model draft")
        }
        val session = GitHubVerifiedSession(delegate, book, "test all tools", { 8192 }, { error("No rewrite") }, {}, {}, now = { 1000L }, authorizedTools = tools)
        val first = session.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        val second = session.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(first != second)
        assertTrue(second.contains("No new test evidence"))
        assertTrue(second.contains("completed job"))
        assertEquals(JsonPrimitive("BLOCKED_UNCHANGED"), verdict?.get("progress_state"))
    }

    @Test fun undeliveredChecklistSurvivesRestartAndIsPublishedBeforeStallSuppression() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        var verdict: JsonObject? = null
        fun book() = GitHubEvidenceBook(
            "task", "o/r", "main", setOf("github_context", "github_ci"), now = { 1000L },
            load = { saved[it] },
            save = { id, row ->
                saved[id] = row
                if (id.startsWith("verification_current_")) verdict = row
            },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } }, latestVerdict = { verdict }
        )
        val tools = listOf(AgentToolDefinition("github_ci", "logs", Json.parseToJsonElement("""{"properties":{"action":{"const":"logs"}}}""").jsonObject))
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("A saved checklist does not need another model draft")
        }
        fun session(evidence: GitHubEvidenceBook) = GitHubVerifiedSession(delegate, evidence, "test all tools", { 8192 }, { error("No rewrite") }, {}, {}, now = { 1000L }, authorizedTools = tools)
        val original = book()
        original.observe(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""").jsonObject), AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}"}""")), false))
        val interrupted = IllegalStateException("Delivery interrupted before acceptance")
        var preparedText: String? = null
        val failed = runCatching {
            session(original).streamRound(tools, emptyList()).collect { event ->
                if (event is ProviderEvent.TextDelta) {
                    preparedText = event.text
                    throw interrupted
                }
            }
        }
        assertSame(interrupted, failed.exceptionOrNull())
        assertEquals(JsonPrimitive(false), verdict?.get("response_delivered"))
        val restored = book()
        restored.restore()
        val resumed = session(restored).streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertEquals(preparedText, resumed)
        assertFalse(resumed.contains("No new test evidence"))
        assertEquals(JsonPrimitive(true), verdict?.get("response_delivered"))
        val deliveredAgain = session(restored).streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(deliveredAgain.contains("No new test evidence"))
        assertEquals(JsonPrimitive("BLOCKED_UNCHANGED"), verdict?.get("progress_state"))
    }
}
