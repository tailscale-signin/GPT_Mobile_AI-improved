package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubVerifiedSession
import dev.chungjungsoo.gptmobile.data.network.RequestByteBudgetScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerVerificationOnlyTest {
    @Test fun queuedReadAndMutationCannotRunBeforeTheActualVerificationAuthorityGuard() = runBlocking {
        for (name in listOf("github_read", "github_write")) {
            val order = mutableListOf<String>()
            val call = ProviderEvent.ToolCall("queued", name, JsonObject(emptyMap()))
            val checkpoint = ToolBatchCheckpoint(null, "same-task-$name")
            checkpoint.enqueue(listOf(call))
            val before = checkpoint.journal()
            val evidence = evidence("CONFIGURATION_INVALIDATED")
            val session = verifiedSession(evidence, validate = { order += "AUTHORITY_GUARD" })
            val tool = tool(name) { order += "DISPATCH" }

            val events = runner(checkpoint).run(session, listOf(tool)).toList()

            assertEquals(listOf("AUTHORITY_GUARD"), order)
            assertEquals("GITHUB_TASK_INCOMPLETE", events.failures().single().errorCode)
            assertFalse(events.failures().single().failureDetails?.get("requires_reconciliation") == JsonPrimitive(true))
            assertEquals(before, checkpoint.journal())
            assertFalse(events.any { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
        }
    }

    @Test fun restoredReceiptsReachTheActualVerifierWithoutExecutingThePendingMutation() = runBlocking {
        val read = ProviderEvent.ToolCall("saved-read", "github_context", buildJsonObject { put("action", "status") })
        val queued = ProviderEvent.ToolCall("queued-write", "github_write", JsonObject(emptyMap()))
        val checkpoint = ToolBatchCheckpoint(null, "saved-and-pending")
        checkpoint.enqueue(listOf(read, queued))
        checkpoint.complete(read, AgentToolResult(read.callId, ToolResultContent.Json(buildJsonObject { put("branch", "main") }), false))
        val before = checkpoint.journal()
        val evidence = evidence("PARTIAL", setOf("github_context"))
        var publications = 0
        val session = verifiedSession(evidence, published = { publications++ })
        val restored = mutableListOf<String>()
        val events = runner(checkpoint, restore = { exchanges ->
            for (exchange in exchanges) {
                val results = exchange.results.associateBy { it.callId }
                for (call in exchange.calls) {
                    restored += call.callId
                    evidence.observe(call, results.getValue(call.callId))
                }
            }
        }).run(session, listOf(tool(read.name), tool(queued.name))).toList()

        assertEquals(listOf(read.callId), restored)
        assertEquals(1, publications)
        assertEquals(1, evidence.snapshot().first.size)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.TextDelta })
        assertEquals(before, checkpoint.journal())
    }

    @Test fun successfulVerificationPreservesQueuedRejectedAndSettledMembersOfTheOriginalBatch() = runBlocking {
        val calls = listOf("saved", "queued", "rejected").map {
            ProviderEvent.ToolCall(it, "github_write", buildJsonObject { put("operation", it) })
        }
        val checkpoint = ToolBatchCheckpoint(null, "partial-batch")
        checkpoint.enqueue(calls)
        checkpoint.complete(calls[0], AgentToolResult("saved", ToolResultContent.Text("Saved result"), false))
        checkpoint.complete(calls[2], AgentToolResult("rejected", ToolResultContent.Text("No allowance"), true, dispatched = false, errorCode = "BUDGET_EXHAUSTED"))
        val before = checkpoint.journal()
        var observed = emptyList<String>()
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                assertTrue(tools.isEmpty())
                assertTrue(exchanges.isEmpty())
                assertEquals(listOf("saved", "rejected"), observed)
                assertNotNull(currentCoroutineContext()[RequestByteBudgetScope])
                assertNotNull(currentCoroutineContext()[ToolArgumentRepairScope])
                emit(ProviderEvent.TextDelta("Saved findings were checked. Queued operations remain unfinished."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true),
            checkpoint = checkpoint,
            retryUndispatchedBudgetFailures = true,
            resumeToolBudget = true,
            onToolResultsRestored = { observed = it.flatMap { exchange -> exchange.calls }.map { it.callId } },
            onToolResultCommitted = { _, _ -> error("Verification cannot commit a new operation receipt") },
            verificationOnlyRequested = true
        ).run(session, listOf(tool("github_write")), dynamicTools = { error("Verification cannot discover execution tools") }).toList()

        assertEquals(1, rounds)
        assertEquals(before, checkpoint.journal())
        assertEquals(listOf(2), events.filterIsInstance<AgentRunEvent.ToolResultsRestored>().map { it.count })
        assertFalse(events.any { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
    }

    @Test fun anUnsettledCheckpointCannotBeHiddenFromVerificationByAMissingEvidenceReceipt() = runBlocking {
        val call = ProviderEvent.ToolCall("uncertain", "github_write", JsonObject(emptyMap()))
        val checkpoint = ToolBatchCheckpoint(null, "uncertain-operation")
        checkpoint.enqueue(listOf(call))
        try {
            checkpoint.execute(call) { throw CancellationException("Simulated interruption before receipt persistence") }
        } catch (_: CancellationException) {
            // Retain the actual dispatched-without-receipt checkpoint state.
        }
        val before = checkpoint.journal()
        assertEquals(1, before.unknownCount)
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("An unresolved operation must block completion verification")
        }
        val events = runner(checkpoint).run(session, listOf(tool(call.name))).toList()

        assertEquals(JsonPrimitive(true), events.failures().single().failureDetails?.get("requires_reconciliation"))
        assertEquals(before, checkpoint.journal())
    }

    @Test fun sessionToolCallsAndUnsolicitedReceiptsAreBlockedWithoutChangingTheCheckpoint() = runBlocking {
        val call = ProviderEvent.ToolCall("unexpected", "github_write", JsonObject(emptyMap()))
        val forgedResult = ProviderEvent.ToolResult(call, AgentToolResult(call.callId, ToolResultContent.Text("Unobserved write"), false))
        for (event in listOf(call, forgedResult)) {
            for (internalTools in listOf(false, true)) {
                val checkpoint = ToolBatchCheckpoint(null, "provider-tool-$internalTools-${event.javaClass.simpleName}")
                val before = checkpoint.journal()
                var rounds = 0
                val session = object : AgentProviderSession {
                    override val handlesToolsInternally = internalTools
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        rounds++
                        assertTrue(tools.isEmpty())
                        emit(event)
                        error("The runner must stop at the first unauthorized tool event")
                    }
                }
                val events = AgentRunner(
                    AgentRunLimits(githubTask = true),
                    checkpoint = checkpoint,
                    onToolResult = { _, _ -> error("A verifier response cannot manufacture a tool receipt") },
                    verificationOnlyRequested = true
                ).run(session, listOf(tool(call.name))).toList()

                assertEquals(if (internalTools) 0 else 1, rounds)
                val failure = events.failures().single()
                assertEquals("GITHUB_TASK_INCOMPLETE", failure.errorCode)
                assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
                assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
                assertEquals(before, checkpoint.journal())
                assertFalse(events.any { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
            }
        }
    }

    @Test fun executableSavedAndRemoteContinuationHintsCannotStartAutomaticRecovery() = runBlocking {
        val read = ProviderEvent.ToolCall("saved", "github_read", buildJsonObject { put("path", "A.kt") })
        val workflows = ProviderEvent.ToolCall(
            "workflows",
            "github_ci",
            buildJsonObject {
                put("action", "workflows")
                put("page", 1)
            }
        )
        val saved = AgentToolExchange(
            listOf(read),
            listOf(
                AgentToolResult(
                    read.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.repeat.v1")
                            put("replay_compacted", true)
                            put("recovery_required", true)
                            put(
                                "continuation_call",
                                buildJsonObject {
                                    put("tool", read.name)
                                    put("arguments", buildJsonObject { put("view_cursor", "saved:0:1536") })
                                }
                            )
                        }
                    ),
                    false
                )
            )
        )
        val remote = AgentToolExchange(
            listOf(workflows),
            listOf(
                AgentToolResult(
                    workflows.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.operation.v1")
                            put(
                                "continuation_call",
                                buildJsonObject {
                                    put("tool", workflows.name)
                                    put(
                                        "arguments",
                                        buildJsonObject {
                                            put("action", "workflows")
                                            put("page", 2)
                                        }
                                    )
                                }
                            )
                        }
                    ),
                    false
                )
            )
        )
        val definitions = listOf(GitHubOperationTool.definition(read.name), GitHubOperationTool.definition(workflows.name))
        assertNotNull(GitHubReplayRecovery.nextSavedRead(listOf(saved), definitions))
        assertNotNull(GitHubReplayRecovery.nextRemoteRead(listOf(remote), definitions))
        val checkpoint = ToolBatchCheckpoint(null, "issued-recovery-cursors")
        for (exchange in listOf(saved, remote)) {
            checkpoint.enqueue(exchange.calls)
            checkpoint.complete(exchange.calls.single(), exchange.results.single())
        }
        val before = checkpoint.journal()
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                assertTrue(tools.isEmpty())
                assertEquals(listOf("saved", "workflows"), exchanges.flatMap { it.calls }.map { it.callId })
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, githubTaskRequest = "Investigate failed CI and inspect the source"),
            checkpoint = checkpoint,
            verificationOnlyRequested = true
        ).run(session, definitions.map { definition -> tool(definition.name, definition) }).toList()

        assertEquals(1, rounds)
        assertEquals("GITHUB_TASK_INCOMPLETE", events.failures().single().errorCode)
        assertEquals(before, checkpoint.journal())
    }

    @Test fun restorationStillHonorsCancellationAndAuthorityRefusalBeforeTheVerifierStarts() = runBlocking {
        for (refusal in listOf(CancellationException("Cancelled"), IllegalStateException("DISPATCH_POLICY_CHANGED: changed authority"))) {
            val call = ProviderEvent.ToolCall("saved", "github_read", buildJsonObject { put("path", "A.kt") })
            val checkpoint = ToolBatchCheckpoint(null, "refusal-${refusal.javaClass.simpleName}")
            checkpoint.enqueue(listOf(call))
            checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Retained evidence"), false))
            val before = checkpoint.journal()
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Restoration authority must be checked first")
            }
            try {
                val events = runner(checkpoint, restore = { throw refusal }).run(session, listOf(tool(call.name))).toList()
                assertFalse(refusal is CancellationException)
                assertEquals("DISPATCH_POLICY_CHANGED", events.failures().single().errorCode)
            } catch (cancelled: CancellationException) {
                assertSame(refusal, cancelled)
            }
            assertEquals(before, checkpoint.journal())
        }
    }

    @Test fun timeoutDeliveryCannotFinishTheSavedExecutionCheckpoint() = runTest {
        val read = ProviderEvent.ToolCall("saved", "github_read", buildJsonObject { put("path", "A.kt") })
        val queued = ProviderEvent.ToolCall("queued", "github_write", JsonObject(emptyMap()))
        val checkpoint = ToolBatchCheckpoint(null, "verification-timeout")
        checkpoint.enqueue(listOf(read))
        checkpoint.complete(read, AgentToolResult(read.callId, ToolResultContent.Text("Saved source"), false))
        checkpoint.enqueue(listOf(queued))
        val before = checkpoint.journal()
        var retainedDeliveries = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                delay(60_000L)
                emit(ProviderEvent.Completed)
            }
            override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flow {
                retainedDeliveries++
                emit(ProviderEvent.TextDelta("Saved findings remain available."))
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(
            AgentRunLimits(githubTask = true, runTimeoutMillis = 1_000L),
            checkpoint = checkpoint,
            remainingTaskMillis = { 60_000L },
            verificationOnlyRequested = true
        ).run(session, listOf(tool(read.name), tool(queued.name))).toList()

        assertEquals(1, retainedDeliveries)
        assertEquals(before, checkpoint.journal())
    }

    @Test fun ordinaryExecutionStillRestoresAndDispatchesQueuedWorkOnce() = runBlocking {
        val call = ProviderEvent.ToolCall("queued", "safe_read", JsonObject(emptyMap()))
        val checkpoint = ToolBatchCheckpoint(null, "ordinary-execution")
        checkpoint.enqueue(listOf(call))
        var dispatches = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Read complete."))
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(checkpoint = checkpoint).run(session, listOf(tool(call.name) { dispatches++ })).toList()
        assertEquals(1, dispatches)
        assertTrue(checkpoint.pending().isEmpty())
    }

    private fun runner(checkpoint: ToolBatchCheckpoint, restore: (suspend (List<AgentToolExchange>) -> Unit)? = null) = AgentRunner(
        AgentRunLimits(githubTask = true),
        checkpoint = checkpoint,
        onToolResultsRestored = restore,
        verificationOnlyRequested = true
    )

    private fun evidence(outcome: String, tools: Set<String> = emptySet()): GitHubEvidenceBook {
        val saved = mutableMapOf<String, JsonObject>()
        return GitHubEvidenceBook(
            "binding",
            "owner/repo",
            "main",
            tools,
            now = { 1000L },
            load = { saved[it] },
            save = { key, value -> saved[key] = value },
            latestVerdict = {
                buildJsonObject {
                    put("binding", "binding")
                    put("failure_code", "GITHUB_VERIFICATION_INTERNAL_ERROR")
                    put("task_outcome", outcome)
                    put("evidence_retention_complete", true)
                    put("resume_allowed", false)
                }
            }
        )
    }

    private fun verifiedSession(evidence: GitHubEvidenceBook, validate: suspend () -> Unit = {}, published: suspend () -> Unit = {}) = GitHubVerifiedSession(
        delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Verification cannot open primary generation")
        },
        evidence = evidence, task = "Explain the observed repository branch", inputLimit = { 8192 },
        openVerifier = { error("A missing draft can only recheck saved receipts") },
        validatePublication = validate, progress = {}, now = { 1000L },
        openExecutionContinuation = { error("Verification cannot reopen execution") },
        onPublication = { published() }, verificationOnlyRequested = true
    )

    private fun tool(name: String, definition: AgentToolDefinition = AgentToolDefinition(name, "Safe test operation", JsonObject(emptyMap())), dispatch: () -> Unit = { error("Verification must never dispatch a tool") }) = object : AgentTool {
        override val definition = definition
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            dispatch()
            return AgentToolResult(callId, ToolResultContent.Text("Observed test result"), false)
        }
    }

    private suspend fun ToolBatchCheckpoint.journal() = recoverySnapshot().copy(loadMillis = 0)

    private fun List<AgentRunEvent>.failures() = filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>()
}
