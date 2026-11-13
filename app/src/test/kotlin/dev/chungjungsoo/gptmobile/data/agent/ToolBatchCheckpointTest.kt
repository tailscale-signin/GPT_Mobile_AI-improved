package dev.chungjungsoo.gptmobile.data.agent

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ToolBatchCheckpointTest {
    @Test fun compactedResultRestoresItsOriginalHashEvenWithoutAnExecutionEnvelope() = runBlocking {
        val file = File.createTempFile("source-identity", ".json").apply { delete() }
        try {
            val call = ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap()))
            val original = AgentToolResult(call.callId, ToolResultContent.Text("long source receipt ".repeat(2000)), false)
            val journal = ToolBatchCheckpoint(file, "same-grant")
            journal.enqueue(listOf(call))
            journal.execute(call) { original }
            val restored = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot().completedExchanges.single().results.single()
            assertEquals(ToolHandoffReceipt.sourceHash(original), ToolHandoffReceipt.sourceHash(restored, originalAvailable = false))
            assertTrue(ToolHandoffReceipt.payload(restored.content).length < ToolHandoffReceipt.payload(original.content).length)
            assertTrue(restored.sharedResult)
        } finally {
            file.delete()
        }
    }

    @Test fun measuredMutationTimeoutRemainsUnknownAcrossAProcessIndependentCheckpoint() = runBlocking {
        val file = File.createTempFile("measured-mutation", ".json").apply { delete() }
        try {
            var executions = 0
            val call = ProviderEvent.ToolCall("write", "github_write", buildJsonObject { put("action", "create_issue") })
            val delegate = object : AgentTool {
                override val definition = AgentToolDefinition(call.name, "Write a repository issue", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executions++
                    throw java.net.SocketTimeoutException("Response timed out after dispatch")
                }
            }
            val measured = dev.chungjungsoo.gptmobile.data.agent.tool.MeasuredAgentTool(delegate)
            val checkpoint = ToolBatchCheckpoint(file, "same-grant")
            checkpoint.enqueue(listOf(call))
            val failure = checkpoint.execute(call) { measured.execute(call.callId, call.arguments) }
            assertEquals("DISPATCH_OUTCOME_UNKNOWN", failure.errorCode)
            val restored = ToolBatchCheckpoint(file, "same-grant").execute(call) { measured.execute(call.callId, call.arguments) }
            assertEquals(1, executions)
            assertTrue(restored.sharedResult)
            assertEquals("DISPATCH_OUTCOME_UNKNOWN", restored.errorCode)
            assertEquals(JsonPrimitive("NETWORK_TIMEOUT"), restored.executionReceipt?.get("cause_code"))
            assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(restored))
        } finally {
            file.delete()
        }
    }

    @Test fun originalInvocationIdentitySurvivesQueuedAndCompletedCheckpointRestoration() = runBlocking {
        val file = File.createTempFile("original-invocation", ".json").apply { delete() }
        try {
            val call = ProviderEvent.ToolCall("read", "github_read__fixture", buildJsonObject { put("path", "canonical.kt") }, originalInvocationFingerprint = "a".repeat(64))
            ToolBatchCheckpoint(file, "same-grant").enqueue(listOf(call))
            val restored = ToolBatchCheckpoint(file, "same-grant")
            assertEquals(call, restored.recoverySnapshot().pending.single())
            restored.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Confirmed receipt"), false) }
            val completed = ToolBatchCheckpoint(file, "same-grant")
            assertEquals(call, completed.recoverySnapshot().completedExchanges.single().calls.single())
            assertTrue(completed.execute(call) { error("Completed action must not replay") }.sharedResult)
            assertTrue(runCatching { completed.execute(call.copy(originalInvocationFingerprint = "b".repeat(64))) { error("Changed invocation identity must not dispatch") } }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun legacyCheckpointWithoutInvocationFingerprintStillRestores() = runBlocking {
        val file = File.createTempFile("legacy-invocation", ".json")
        try {
            file.writeText("""{"binding":"legacy","pending":["read"],"entries":{"read":{"call":{"id":"read","name":"github_read","arguments":{}}}}}""")
            val call = ToolBatchCheckpoint(file, "legacy").recoverySnapshot().pending.single()
            assertEquals(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())), call)
        } finally {
            file.delete()
        }
    }

    @Test fun resumeRestoresEverySettledBatchWithoutDispatchingAgain() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "multi-batch")
        for (id in listOf("status", "checks", "prs", "commits")) {
            val call = ProviderEvent.ToolCall(id, "github_context", buildJsonObject { put("action", id) })
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(id, ToolResultContent.Text("retained $id"), false) }
        }
        journal.markConsumed(setOf("status", "checks", "missing"))
        val snapshot = journal.recoverySnapshot()
        assertEquals(setOf("status", "checks"), snapshot.consumedCallIds)
        assertEquals(listOf("status", "checks", "prs", "commits"), snapshot.completedExchanges.map { it.calls.single().callId })
        val observed = mutableListOf<String>()
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_context", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Completed work must not dispatch")
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.isEmpty())
                assertEquals(listOf("status", "checks", "prs", "commits"), exchanges.flatMap { it.calls }.map { it.callId })
                emit(ProviderEvent.TextDelta("Retained status"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, maxToolCalls = 4), journal, onToolResult = { call, _ -> observed += call.callId }).run(provider, listOf(tool)).toList()
        assertEquals(listOf("status", "checks", "prs", "commits"), observed)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
    }

    private fun binding(calls: List<ProviderEvent.ToolCall>): dev.chungjungsoo.gptmobile.data.network.gateway.GatewayContinuationBinding {
        val source = buildJsonObject {
            put("job_id", "saved")
            put("status", "waiting_for_client_tool")
            put(
                "job",
                buildJsonObject {
                    put("job_id", "saved")
                    put("model", "selected")
                    put("pending_client_tool_call_ids", JsonArray(calls.map { JsonPrimitive(it.callId) }))
                }
            )
            put(
                "result",
                buildJsonObject {
                    put(
                        "choices",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("finish_reason", "tool_calls")
                                    put(
                                        "message",
                                        buildJsonObject {
                                            put("role", "assistant")
                                            put(
                                                "tool_calls",
                                                JsonArray(
                                                    calls.map { call ->
                                                        buildJsonObject {
                                                            put("id", call.callId)
                                                            put("type", "function")
                                                            put(
                                                                "function",
                                                                buildJsonObject {
                                                                    put("name", call.name)
                                                                    put("arguments", call.arguments.toString())
                                                                }
                                                            )
                                                        }
                                                    }
                                                )
                                            )
                                        }
                                    )
                                }
                            )
                        )
                    )
                }
            )
        }
        val saved = Json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobResult.serializer(), source)
        return dev.chungjungsoo.gptmobile.data.network.gateway.GatewayContinuationBinding.verified("profile", "https://gateway.test/v1", "selected", "saved", saved)
    }

    @Test fun processRestartRestoresTheOriginalTwoCallBatchAndNeverRedispatchesCompletedMembers() = runBlocking {
        for (github in listOf(false, true)) {
            for (partial in listOf(false, true)) {
                val file = File.createTempFile("grouped-handoff", ".json").apply { delete() }
                try {
                    val calls = listOf("first", "second").map { ProviderEvent.ToolCall(it, "read_fixture", buildJsonObject { put("path", "$it.txt") }) }
                    val original = ToolBatchCheckpoint(file, "same-task")
                    original.enqueue(calls)
                    val executions = mutableListOf<String>()
                    suspend fun execute(call: ProviderEvent.ToolCall): AgentToolResult {
                        executions += call.callId
                        return AgentToolResult(call.callId, ToolResultContent.Text("retained ${call.callId}"), false)
                    }
                    original.execute(calls.first()) { execute(calls.first()) }
                    if (!partial) original.execute(calls.last()) { execute(calls.last()) }
                    val waiting = binding(calls)
                    val restored = ToolBatchCheckpoint(file, "same-task")
                    val snapshot = restored.recoverySnapshot()
                    if (partial) {
                        assertEquals(calls, snapshot.pending)
                        assertTrue(snapshot.completedExchanges.isEmpty())
                        assertEquals(setOf("first"), snapshot.pendingRetainedIds)
                    } else {
                        assertTrue(snapshot.pending.isEmpty())
                        assertEquals(calls, snapshot.completedExchanges.single().calls)
                        waiting.requireResults(snapshot.completedExchanges)
                    }
                    val tool = object : AgentTool {
                        override val definition = AgentToolDefinition("read_fixture", "Read fixture", JsonObject(emptyMap()))
                        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                            assertEquals("Only the uncompleted member may execute", "second", callId)
                            return execute(calls.last())
                        }
                    }
                    var providerRounds = 0
                    val provider = object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            providerRounds++
                            waiting.requireResults(exchanges)
                            assertEquals(calls, exchanges.last().calls)
                            assertEquals(calls.map { it.callId }, exchanges.last().results.map { it.callId })
                            emit(ProviderEvent.TextDelta("Saved handoff received"))
                            emit(ProviderEvent.Completed)
                        }
                    }
                    repeat(2) {
                        val events = AgentRunner(AgentRunLimits(githubTask = github, maxToolCalls = 2), ToolBatchCheckpoint(file, "same-task")).run(provider, listOf(tool)).toList()
                        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
                    }
                    assertEquals(listOf("first", "second"), executions)
                    assertEquals(2, providerRounds)
                    assertEquals(calls, ToolBatchCheckpoint(file, "same-task").recoverySnapshot().completedExchanges.single().calls)
                } finally {
                    file.delete()
                }
            }
        }
    }

    @Test fun legacyJournalGroupsOnlyItsProvenPendingBatchAndDoesNotMergeHistoricalCalls() = runBlocking {
        val file = File.createTempFile("legacy-grouping", ".json").apply { delete() }
        try {
            val journal = ToolBatchCheckpoint(file, "legacy-groups")
            val historical = ProviderEvent.ToolCall("historical", "read_fixture", JsonObject(emptyMap()))
            val current = listOf("first", "second").map { ProviderEvent.ToolCall(it, "read_fixture", JsonObject(emptyMap())) }
            for (batch in listOf(listOf(historical), current)) {
                journal.enqueue(batch)
                batch.forEach { call -> journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("saved"), false) } }
            }
            file.writeText(JsonObject(Json.parseToJsonElement(file.readText()).jsonObject - "batches").toString())
            val grouped = ToolBatchCheckpoint(file, "legacy-groups").recoverySnapshot().completedExchanges
            assertEquals(listOf(listOf(historical), current), grouped.map { it.calls })
            binding(current).requireResults(grouped)
            val withoutPending = JsonObject(Json.parseToJsonElement(file.readText()).jsonObject + ("pending" to JsonArray(emptyList())))
            file.writeText(withoutPending.toString())
            val ungrouped = ToolBatchCheckpoint(file, "legacy-groups").recoverySnapshot().completedExchanges
            assertEquals(listOf(historical) + current, ungrouped.map { it.calls.single() })
            assertTrue(runCatching { binding(current).requireResults(ungrouped) }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun partialGitHubBatchRestoresCompletedMembersWithoutPublishingNewToolActivity() = runBlocking {
        val file = File.createTempFile("partial-restored-events", ".json").apply { delete() }
        try {
            val calls = listOf("committed", "queued").map {
                ProviderEvent.ToolCall(it, "write_fixture", buildJsonObject { put("target", it) })
            }
            val executions = mutableListOf<String>()
            val original = ToolBatchCheckpoint(file, "same-task")
            original.enqueue(calls)
            original.execute(calls.first()) {
                executions += calls.first().callId
                AgentToolResult(calls.first().callId, ToolResultContent.Text("Original committed receipt"), false)
            }
            val restored = mutableListOf<List<AgentToolExchange>>()
            val observed = mutableListOf<String>()
            val committed = mutableListOf<String>()
            val waiting = binding(calls)
            var providerRounds = 0
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition("write_fixture", "Write fixture", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executions += callId
                    assertEquals("queued", callId)
                    return AgentToolResult(callId, ToolResultContent.Text("New committed receipt"), false)
                }
            }
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    providerRounds++
                    waiting.requireResults(exchanges)
                    assertEquals(calls, exchanges.single().calls)
                    assertEquals(calls.map { it.callId }, exchanges.single().results.map { it.callId })
                    assertEquals(ToolResultContent.Text("Original committed receipt"), exchanges.single().results.first().content)
                    emit(ProviderEvent.TextDelta("Both saved outcomes received"))
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner(
                AgentRunLimits(githubTask = true, maxToolCalls = 2),
                ToolBatchCheckpoint(file, "same-task"),
                onToolResult = { call, _ -> observed += call.callId },
                onToolResultCommitted = { call, _ -> committed += call.callId },
                onToolResultsRestored = { restored += it }
            ).run(provider, listOf(tool)).toList()

            assertEquals(listOf("committed", "queued"), executions)
            assertEquals(1, providerRounds)
            assertEquals(listOf(calls.first()), restored.single().single().calls)
            assertTrue(restored.single().single().results.single().sharedResult)
            assertTrue(observed.isNotEmpty() && observed.all { it == "queued" })
            assertEquals(listOf("queued"), committed)
            assertEquals(listOf("queued"), events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.ToolCall>().map { it.callId })
            assertEquals(listOf("queued"), events.filterIsInstance<AgentRunEvent.ToolStarted>().map { it.call.callId })
            assertEquals(listOf("queued"), events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.call.callId })
            assertEquals(listOf(1), events.filterIsInstance<AgentRunEvent.ToolResultsRestored>().map { it.count })
            assertEquals(calls, ToolBatchCheckpoint(file, "same-task").recoverySnapshot().completedExchanges.single().calls)
        } finally {
            file.delete()
        }
    }

    @Test fun partialBatchRestoresAnsweredDecisionAndDefersActionsUntilTheProviderReplans() = runBlocking {
        for (githubTask in listOf(false, true)) {
            val file = File.createTempFile("partial-decision", ".json").apply { delete() }
            try {
                val question = ProviderEvent.ToolCall("decision", "question_fixture", JsonObject(emptyMap()))
                val action = ProviderEvent.ToolCall("action", "action_fixture", JsonObject(emptyMap()))
                val calls = listOf(question, action)
                val journal = ToolBatchCheckpoint(file, "same-task")
                journal.enqueue(calls)
                val answer = AgentToolResult(question.callId, ToolResultContent.Text("Saved answer"), false)
                journal.complete(question, answer)
                val tools = calls.map { call ->
                    object : AgentTool {
                        override val definition = AgentToolDefinition(
                            call.name,
                            "Fixture",
                            JsonObject(emptyMap()),
                            capabilities = if (call == question) setOf(AgentToolCapability.USER_DECISION) else emptySet()
                        )

                        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult =
                            error("Recovered batch must not dispatch $callId")
                    }
                }
                var providerRounds = 0
                val provider = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        providerRounds++
                        val batch = exchanges.single()
                        assertEquals(calls, batch.calls)
                        assertEquals(answer.content, batch.results.first().content)
                        assertTrue(batch.results.first().sharedResult)
                        assertEquals(action.callId, batch.results.last().callId)
                        assertFalse(batch.results.last().dispatched)
                        emit(ProviderEvent.TextDelta("The saved decision is available."))
                        emit(ProviderEvent.Completed)
                    }
                }

                val events = AgentRunner(
                    AgentRunLimits(githubTask = githubTask),
                    ToolBatchCheckpoint(file, "same-task")
                ).run(provider, tools).toList()

                assertEquals(1, providerRounds)
                assertTrue(events.none { it is AgentRunEvent.ToolStarted })
                assertEquals(calls, ToolBatchCheckpoint(file, "same-task").recoverySnapshot().completedExchanges.single().calls)
            } finally {
                file.delete()
            }
        }
    }

    @Test fun acceptedJobAndVerifiedReceiptSurviveRestartWithoutReplayingPolicyRejections() = runBlocking {
        val file = File.createTempFile("engineering-context", ".json").apply { delete() }
        try {
            val job = ToolBatchJobContext("Repair the selected repository", "GITHUB_ENGINEERING", "owner/repo", "feature", "a".repeat(40))
            val journal = ToolBatchCheckpoint(file, "authorized-job", job)
            val observed = ProviderEvent.ToolCall("verified-pr", "github_pr", JsonObject(emptyMap()))
            val blocked = ProviderEvent.ToolCall("policy", "github_write", JsonObject(emptyMap()))
            journal.enqueue(listOf(observed, blocked))
            journal.execute(observed) {
                AgentToolResult(
                    observed.callId,
                    ToolResultContent.Json(buildJsonObject { put("number", 22) }),
                    false,
                    executionReceipt = buildJsonObject {
                        put("phase", "OBSERVED")
                        put("read_requests", 1)
                    }
                )
            }
            journal.execute(blocked) { ToolResultEnvelope.error(blocked.callId, "PERSONAL_DATA_LOCAL_ONLY", "Local-only source cannot be shared") }
            val restored = ToolBatchCheckpoint(file, "authorized-job")
            restored.prepareExplicitBudgetRetry()
            val snapshot = restored.recoverySnapshot()
            assertEquals(job.objective, snapshot.jobContext?.objective)
            assertEquals(job.branch, snapshot.jobContext?.branch)
            assertEquals(job.commitSha, snapshot.jobContext?.commitSha)
            assertEquals(22, snapshot.jobContext?.pullRequest)
            assertEquals(JsonPrimitive(observed.callId), snapshot.jobContext?.lastVerifiedReceipt?.get("callId"))
            assertEquals("NOT_CONFIGURED", snapshot.objectiveStatus)
            assertEquals(1, snapshot.rejectedCount)
            assertEquals(0, snapshot.failedCount)
            val result = restored.execute(blocked) { error("A saved policy rejection must never dispatch again") }
            assertEquals("PERSONAL_DATA_LOCAL_ONLY", result.errorCode)
            assertTrue(!result.dispatched)
        } finally {
            file.delete()
        }
    }

    @Test fun verifiedBranchSelectionSurvivesRestartButUnrelatedDestinationDoesNot() = runBlocking {
        val file = File.createTempFile("branch-transition", ".json").apply { delete() }
        try {
            val original = ToolBatchJobContext("Full GitHub test", "GITHUB_ENGINEERING", "owner/repo", "main", "a".repeat(40))
            val journal = ToolBatchCheckpoint(file, "same-turn", original)
            val branch = ProviderEvent.ToolCall(
                "created-branch",
                "github_commit__github_api",
                buildJsonObject {
                    put("action", "branch")
                    put("branch", "test")
                }
            )
            journal.enqueue(listOf(branch))
            journal.execute(branch) {
                AgentToolResult(
                    branch.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put(
                                "data",
                                buildJsonObject {
                                    put("receipt_verified", true)
                                    put("head_sha", "a".repeat(40))
                                    put(
                                        "context",
                                        buildJsonObject {
                                            put("owner", "owner")
                                            put("repo", "repo")
                                            put("ref", "test")
                                            put("commit_sha", "a".repeat(40))
                                        }
                                    )
                                }
                            )
                        }
                    ),
                    false
                )
            }
            val restored = ToolBatchCheckpoint(file, "same-turn", original.copy(branch = "test"))
            assertEquals(1, restored.recoverySnapshot().completedCount)
            assertTrue(!restored.execute(branch) { error("Already created; never dispatch again") }.isError)
            for (invalid in listOf(original.copy(branch = "unrelated"), original.copy(repository = "other/repo"), original.copy(objective = "Different task"))) {
                assertTrue(runCatching { ToolBatchCheckpoint(file, "same-turn", invalid).recoverySnapshot() }.isFailure)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun explicitBudgetRetryReopensOnlyUndispatchedBudgetFailuresAndKeepsReceipts() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "retry")
        val calls = listOf("budget", "completed", "uncertain", "invalid", "dispatched-budget").map { ProviderEvent.ToolCall(it, "write", JsonObject(emptyMap())) }
        journal.enqueue(calls)
        val receipts = listOf(
            AgentToolResult("budget", ToolResultContent.Text("limit"), true, errorCode = "BUDGET_EXHAUSTED", dispatched = false, toolCallBudgetExhausted = true),
            AgentToolResult("completed", ToolResultContent.Text("commit receipt"), false, toolCallBudgetExhausted = true),
            AgentToolResult("uncertain", ToolResultContent.Text("reconcile"), true, errorCode = "TOOL_OUTCOME_UNKNOWN"),
            AgentToolResult("invalid", ToolResultContent.Text("fix arguments"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false),
            AgentToolResult("dispatched-budget", ToolResultContent.Text("saved partial output"), true, errorCode = "BUDGET_EXHAUSTED", outputBudgetExhausted = true)
        )
        calls.zip(receipts).forEach { (call, result) -> journal.execute(call) { result } }
        val before = journal.dispatchedCount()
        assertEquals("BUDGET_EXHAUSTED", journal.execute(calls.first()) { error("Implicit resume must not retry") }.errorCode)
        journal.prepareExplicitBudgetRetry()
        val snapshot = journal.recoverySnapshot()
        assertEquals(1, snapshot.queuedCount)
        assertEquals(before, snapshot.dispatchedCount)
        var newDispatches = 0
        val restored = calls.map { call ->
            journal.execute(call) {
                newDispatches++
                assertEquals("budget", call.callId)
                AgentToolResult(call.callId, ToolResultContent.Text("newly admitted"), false)
            }
        }
        assertEquals(1, newDispatches)
        assertEquals("commit receipt", (restored[1].content as ToolResultContent.Text).text)
        assertEquals("TOOL_OUTCOME_UNKNOWN", restored[2].errorCode)
        assertEquals("INVALID_ARGUMENTS", restored[3].errorCode)
        assertEquals("BUDGET_EXHAUSTED", restored[4].errorCode)
        assertTrue(restored.none { it.toolCallBudgetExhausted || it.outputBudgetExhausted })
        journal.prepareExplicitBudgetRetry()
        calls.forEach { journal.execute(it) { error("Completed or uncertain work must never replay") } }
    }

    @Test fun retainedUnknownReceiptCannotBeRestoredAsSuccess() = runBlocking {
        val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
        val journal = ToolBatchCheckpoint(null, "grant")
        journal.enqueue(listOf(call))
        journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("contradictory legacy receipt"), false, errorCode = "DISPATCH_OUTCOME_UNKNOWN") }
        val restored = journal.execute(call) { error("must not dispatch") }
        assertTrue(restored.isError)
        assertTrue(restored.sharedResult)
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", restored.errorCode)
        val metrics = journal.recoverySnapshot()
        assertEquals(1, metrics.unknownCount)
        assertEquals(0, metrics.completedCount)
    }

    @Test fun backupOnlyCheckpointRestoresClaimsInsteadOfDispatchingThemAgain() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-backup").toFile()
        val file = File(directory, "batch.json")
        try {
            val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
            val journal = ToolBatchCheckpoint(file, "grant")
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("saved"), false) }
            assertTrue(file.renameTo(File(file.path + ".bak")))
            val restored = ToolBatchCheckpoint(file, "grant")
            assertEquals(listOf(call), restored.pending())
            assertEquals(ToolResultContent.Text("saved"), restored.execute(call) { error("must not dispatch") }.content)
            assertEquals(1, restored.recoverySnapshot().completedCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun concurrentInstancesKeepOneDispatchPerIdentityAndConsistentRecoveryCounts() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-concurrent").toFile()
        try {
            val file = File(directory, "batch.json")
            val writers = List(2) { ToolBatchCheckpoint(file, "grant") }
            val calls = (1..64).map { ProviderEvent.ToolCall("call-$it", "write", JsonObject(emptyMap())) }
            writers.first().enqueue(calls)
            val counts = ConcurrentHashMap<String, Int>()
            writers.flatMap { writer ->
                calls.map { call ->
                    async {
                        writer.execute(call) {
                            counts.compute(call.callId) { _, previous -> (previous ?: 0) + 1 }
                            AgentToolResult(call.callId, ToolResultContent.Text("saved"), false)
                        }
                    }
                }
            }.awaitAll()
            assertEquals(64, counts.size)
            assertTrue(counts.values.all { it == 1 })
            val snapshot = ToolBatchCheckpoint(file, "grant").recoverySnapshot()
            assertEquals(64, snapshot.completedCount)
            assertEquals(0, snapshot.unknownCount)
            assertEquals(0, snapshot.queuedCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectedCallsDoNotConsumeRestoredBudgetButUncertainClaimsRemainUnknown() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-dispatch-accounting").toFile()
        val file = File(directory, "batch.json")
        try {
            val rejected = ProviderEvent.ToolCall("rejected", "write", JsonObject(emptyMap()))
            val uncertain = ProviderEvent.ToolCall("uncertain", "write", JsonObject(emptyMap()))
            val journal = ToolBatchCheckpoint(file, "grant")
            journal.enqueue(listOf(rejected, uncertain))
            journal.execute(rejected) { AgentToolResult(rejected.callId, ToolResultContent.Text("Invalid"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false) }
            try {
                journal.execute(uncertain) { throw CancellationException("lost response") }
            } catch (_: CancellationException) { }
            val restored = ToolBatchCheckpoint(file, "grant")
            assertEquals(1, restored.dispatchedCount())
            assertEquals(setOf("uncertain"), restored.pendingDispatchedIds())
            val snapshot = restored.recoverySnapshot(CheckpointRestoreObjective(60_000))
            assertEquals(listOf(rejected, uncertain), snapshot.pending)
            assertTrue(snapshot.completedExchanges.isEmpty())
            assertEquals(1, snapshot.dispatchedCount)
            assertEquals(setOf("uncertain"), snapshot.pendingDispatchedIds)
            assertEquals(setOf("rejected", "uncertain"), snapshot.pendingRetainedIds)
            assertEquals("MET", snapshot.objectiveStatus)
            assertEquals("NOT_CONFIGURED", restored.recoverySnapshot().objectiveStatus)
            assertEquals(1, snapshot.unknownCount)
            assertEquals(1, snapshot.rejectedCount)
            assertEquals(0, snapshot.completedCount)
            val unknown = restored.execute(uncertain) { error("must reconcile") }
            assertEquals("TOOL_OUTCOME_UNKNOWN", unknown.errorCode)
            val encoded = Json.parseToJsonElement(ToolResultEnvelope.encode("write", unknown)).jsonObject
            assertEquals(JsonPrimitive(false), encoded["execution"]!!.jsonObject["dispatched"])
            assertEquals(JsonPrimitive("unknown"), encoded["execution"]!!.jsonObject["outcome"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun checkpointRestoreObjectiveRejectsInvalidTargetsAndEvaluatesMeasuredDuration() {
        assertTrue(runCatching { CheckpointRestoreObjective(0) }.isFailure)
        val objective = CheckpointRestoreObjective(100)
        assertEquals("MET", objective.assess(100))
        assertEquals("EXCEEDED", objective.assess(101))
    }

    @Test fun settlingOneCallAllowsTheFullUnresolvedRemainderButNeverDropsQueuedWork() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "grant")
        val calls = (1..3).map { ProviderEvent.ToolCall("call-$it", "write", JsonObject(emptyMap())) }
        journal.enqueue(calls)
        journal.execute(calls.first()) { AgentToolResult(calls.first().callId, ToolResultContent.Text("saved"), false) }
        assertTrue(runCatching { journal.enqueue(listOf(calls.last())) }.isFailure)
        val remaining = journal.recoverySnapshot().pending
        assertEquals(calls, remaining)
        journal.enqueue(remaining)
        assertEquals(remaining, journal.pending())
        val receipt = journal.execute(calls.first()) { error("Settled work must not dispatch again") }
        assertTrue(receipt.sharedResult)
        assertEquals(ToolResultContent.Text("saved"), receipt.content)
    }

    @Test fun restoredRejectionsAndUnknownClaimsDoNotDisplaceQueuedCallsFromTheRemainingBudget() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "grant")
        val rejected = ProviderEvent.ToolCall("rejected", "write", JsonObject(emptyMap()))
        val uncertain = ProviderEvent.ToolCall("uncertain", "write", JsonObject(emptyMap()))
        val queued = ProviderEvent.ToolCall("queued", "write", buildJsonObject { put("path", "different-target") })
        journal.enqueue(listOf(rejected, uncertain, queued))
        journal.execute(rejected) { AgentToolResult(rejected.callId, ToolResultContent.Text("Invalid"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false) }
        try {
            journal.execute(uncertain) { throw CancellationException("lost response") }
        } catch (_: CancellationException) { }
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                assertEquals("queued", callId)
                return AgentToolResult(callId, ToolResultContent.Text("completed"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                val results = exchanges.flatMap { it.results }.associateBy { it.callId }
                assertEquals("INVALID_ARGUMENTS", results.getValue("rejected").errorCode)
                assertEquals("TOOL_OUTCOME_UNKNOWN", results.getValue("uncertain").errorCode)
                assertTrue(results.getValue("queued").content.toString().contains("completed"))
                emit(ProviderEvent.TextDelta("One action requires reconciliation."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(maxToolCalls = 2), journal).run(session, listOf(tool)).toList()
        assertEquals(1, dispatched)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun mismatchedToolReceiptIsAnUncertainErrorAndIsNotRedispatched() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "grant")
        val call = ProviderEvent.ToolCall("expected", "write", JsonObject(emptyMap()))
        var count = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                count++
                return AgentToolResult("other", ToolResultContent.Text("untrusted receipt"), false)
            }
        }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) emit(call) else emit(ProviderEvent.TextDelta("done"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(checkpoint = checkpoint).run(session, listOf(tool)).toList()
        val result = events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result
        assertEquals("expected", result.callId)
        assertEquals("TOOL_RESULT_ID_MISMATCH", result.errorCode)
        assertTrue(result.isError)
        val checkpointedCall = events.filterIsInstance<AgentRunEvent.ToolStarted>().single().call
        assertEquals("TOOL_RESULT_ID_MISMATCH", checkpoint.execute(checkpointedCall) { error("must not repeat") }.errorCode)
        assertEquals(1, count)
    }

    @Test fun interruptedCiReadDoesNotMaskAnApprovalRequiredAfterReplay() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "same-github-task")
        val read = ProviderEvent.ToolCall(
            "ci-read",
            "github_ci__github_api",
            buildJsonObject {
                put("action", "run")
                put("run_id", 42)
            }
        )
        journal.enqueue(listOf(read))
        try {
            journal.execute(read) { throw CancellationException("Interrupted after the CI read started") }
        } catch (_: CancellationException) { }
        assertEquals(1, journal.recoverySnapshot().unknownCount)

        var reads = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(read.name, "Read workflow run", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                reads++
                return AgentToolResult(callId, ToolResultContent.Text("Workflow run observed"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(exchanges.flatMap { it.results }.any { it.callId == read.callId && !it.isError })
                emit(ProviderEvent.Failed("Approve the staged change.", errorCode = "WAITING_FOR_APPROVAL", taskOutcome = "WAITING_FOR_APPROVAL"))
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true), journal).run(session, listOf(tool)).toList()
        val failure = events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { it.event as? ProviderEvent.Failed }.single()
        assertEquals("WAITING_FOR_APPROVAL", failure.errorCode)
        assertEquals(1, reads)
        assertEquals(0, journal.recoverySnapshot().unknownCount)
    }

    @Test fun deferredReplayCannotReplaceAnInFlightWriteReceipt() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "grant")
        val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
        checkpoint.enqueue(listOf(call))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            checkpoint.execute(call) {
                entered.complete(Unit)
                release.await()
                AgentToolResult(call.callId, ToolResultContent.Text("published"), false)
            }
        }
        entered.await()
        checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("deferred"), true, dispatched = false))
        assertEquals("TOOL_OUTCOME_UNKNOWN", checkpoint.execute(call) { error("must not dispatch") }.errorCode)
        release.complete(Unit)
        owner.await()
        assertEquals(ToolResultContent.Text("published"), checkpoint.execute(call) { error("must not dispatch") }.content)
    }

    @Test fun checkpointVersionRejectsUnknownVersionsAndReadsLegacyState() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-version").toFile()
        val file = File(directory, "batch.json")
        try {
            file.writeText("""{"binding":"grant","pending":[],"entries":{}}""")
            assertTrue(ToolBatchCheckpoint(file, "grant").pending().isEmpty())
            file.writeText("""{"binding":"grant","schemaVersion":3,"pending":[],"entries":{}}""")
            assertTrue(runCatching { ToolBatchCheckpoint(file, "grant").pending() }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun sixtySevenCallsRecoverWithoutRepeatingOrLosingQueuedWork() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-67").toFile()
        val file = File(directory, "batch.json")
        val calls = (1..67).map { ProviderEvent.ToolCall("call-$it", "operation", JsonObject(emptyMap())) }
        try {
            val original = ToolBatchCheckpoint(file, "grant")
            original.enqueue(calls)
            var dispatched = 0
            for (call in calls.take(33)) {
                original.execute(call) {
                    dispatched++
                    AgentToolResult(call.callId, ToolResultContent.Text("verified"), false)
                }
            }
            try {
                original.execute(calls[33]) {
                    dispatched++
                    throw CancellationException("lost response")
                }
            } catch (_: CancellationException) { }
            assertTrue(runCatching { original.enqueue(listOf(ProviderEvent.ToolCall("other", "operation", JsonObject(emptyMap())))) }.isFailure)
            assertTrue(runCatching { original.finish() }.isFailure)
            val restored = ToolBatchCheckpoint(file, "grant")
            val results = restored.pending().map { call ->
                restored.execute(call) {
                    dispatched++
                    AgentToolResult(call.callId, ToolResultContent.Text("verified"), false)
                }
            }
            assertEquals(67, results.size)
            assertEquals("TOOL_OUTCOME_UNKNOWN", results[33].errorCode)
            assertTrue(results.filterIndexed { index, _ -> index != 33 }.none { it.isError })
            assertEquals(67, dispatched)
            restored.finish()
            assertTrue(restored.pending().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun savedReceiptCannotBeReplacedByADeferredOrMismatchedResult() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "grant")
        val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
        checkpoint.enqueue(listOf(call))
        checkpoint.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("published"), false) }
        checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("deferred"), true, dispatched = false))
        assertEquals(ToolResultContent.Text("published"), checkpoint.execute(call) { error("must not dispatch") }.content)
        assertTrue(runCatching { checkpoint.complete(call, AgentToolResult("other", ToolResultContent.Text("wrong"), false)) }.isFailure)
        assertTrue(runCatching { checkpoint.enqueue(listOf(call, call)) }.isFailure)
    }

    @Test fun interruptedThirtyThreeCallBatchRestoresCommittedResultsAndOnlyDispatchesQueuedCalls() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-recovery").toFile()
        val file = File(directory, "batch.json")
        val counts = ConcurrentHashMap<String, Int>()
        val calls = (1..33).map { ProviderEvent.ToolCall("call-$it", "read_url", buildJsonObject { put("url", "https://example.org/$it") }) }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("read_url", "Read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                counts.compute(callId) { _, old -> (old ?: 0) + 1 }
                if (callId == "call-32") throw CancellationException("Simulated process interruption after dispatch")
                return AgentToolResult(callId, ToolResultContent.Text("Saved evidence for $callId"), false)
            }
        }
        val first = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                calls.forEach { emit(it) }
                emit(ProviderEvent.Completed)
            }
        }
        try {
            try {
                AgentRunner(AgentRunLimits(maxConcurrentTools = 1), ToolBatchCheckpoint(file, "accepted-turn-policy")).run(first, listOf(tool)).toList()
                error("Must interrupt")
            } catch (_: CancellationException) { }
            assertEquals(32, counts.size)
            val restored = ToolBatchCheckpoint(file, "accepted-turn-policy")
            assertEquals(33, restored.pending().size)
            val resumed = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertEquals(33, exchanges.flatMap { it.results }.size)
                    assertTrue(exchanges.flatMap { it.results }.single { it.callId == "call-32" }.isError)
                    assertTrue(exchanges.flatMap { it.results }.filter { it.callId != "call-32" }.none { it.isError })
                    emit(ProviderEvent.TextDelta("Retained partial report; one call has an unknown outcome."))
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner(AgentRunLimits(maxConcurrentTools = 1), restored).run(resumed, listOf(tool)).toList()
            assertEquals(33, counts.size)
            assertTrue(counts.values.all { it == 1 })
            assertEquals(33, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
            assertTrue(ToolBatchCheckpoint(file, "accepted-turn-policy").pending().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun isolatedPersonalContextRejectsSavedArgumentsWithoutErasingDispatchEvidence() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-isolation").toFile()
        val file = File(directory, "batch.json")
        val call = ProviderEvent.ToolCall("id", "github_write", buildJsonObject { put("body", "derived personal context") })
        try {
            val journal = ToolBatchCheckpoint(file, "original-grant")
            journal.requireFreshPersonalContext()
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("written"), false) }
            journal.finish()
            val before = file.readBytes()
            val restored = ToolBatchCheckpoint(file, "original-grant")
            assertTrue(runCatching { restored.requireFreshPersonalContext() }.isFailure)
            assertTrue(before.contentEquals(file.readBytes()))
            assertEquals(1, restored.recoverySnapshot().completedCount)
            ToolBatchCheckpoint(File(directory, "new-turn.json"), "new-turn").requireFreshPersonalContext()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun changedCallIdentityAndChangedAuthorizationCannotReuseTheJournal() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-policy").toFile()
        val file = File(directory, "batch.json")
        val call = ProviderEvent.ToolCall("id", "write", buildJsonObject { put("value", 1) })
        try {
            val journal = ToolBatchCheckpoint(file, "original-grant")
            journal.enqueue(listOf(call))
            val changed = runCatching { journal.enqueue(listOf(call.copy(arguments = buildJsonObject { put("value", 2) }))) }
            assertTrue(changed.isFailure)
            assertTrue(runCatching { ToolBatchCheckpoint(file, "different-grant").pending() }.isFailure)
            var executions = 0
            repeat(2) {
                journal.execute(call) {
                    executions++
                    AgentToolResult(call.callId, ToolResultContent.Text("written"), false)
                }
            }
            assertEquals(1, executions)
        } finally {
            directory.deleteRecursively()
        }
    }
}
