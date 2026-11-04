package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubTaskRecoveryTest {
    @Test fun repeatedEmptyRoundsFinalizeFromReceiptsWithoutRepeatingTheTool() = runTest {
        var rounds = 0
        var executions = 0
        var retainedResponses = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("saved evidence"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                val round = rounds++
                assertEquals(round < 3, tools.isNotEmpty())
                if (round == 0) {
                    emit(ProviderEvent.ToolCall("read-once", tool.definition.name, JsonObject(emptyMap())))
                } else {
                    assertEquals("read-once", exchanges.single().results.single().callId)
                }
                emit(ProviderEvent.Completed)
            }

            override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flow {
                retainedResponses++
                assertEquals("FINAL_RESPONSE_REQUIRED", failure.errorCode)
                emit(ProviderEvent.TextDelta("Saved observations; task incomplete."))
                emit(failure.copy(taskOutcome = "PARTIAL"))
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()
        assertEquals(4, rounds)
        assertEquals(1, executions)
        assertEquals(1, retainedResponses)
        val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals("PARTIAL", published.filterIsInstance<ProviderEvent.Failed>().single().taskOutcome)
        assertFalse(published.any { it is ProviderEvent.Completed })
    }

    @Test fun ordinaryEmptyRoundGetsOneToolFreeFinalizationAttempt() = runTest {
        var rounds = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("No call was requested")
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertEquals(rounds++ == 0, tools.isNotEmpty())
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits()).run(session, listOf(tool)).toList()
        assertEquals(2, rounds)
        assertEquals("FINAL_RESPONSE_REQUIRED", events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun providerBillingAndRetiredEndpointsNeverEnterConnectionRecovery() {
        for (message in listOf("HTTP 402: connection refused", "HTTP 410: LOCAL_SERVICE_UNREACHABLE", "Payment required: connection reset")) {
            assertNull(GitHubTaskRecovery.delayMillis(message, 0))
        }
        for (code in listOf("PAYMENT_REQUIRED", "BILLING_UNAVAILABLE", "INSUFFICIENT_CREDITS", "INSUFFICIENT_QUOTA", "BUDGET_EXCEEDED", "CREDIT_LIMIT_EXCEEDED")) {
            assertNull(GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: connection refused", 0, code))
        }
    }

    @Test fun structuredContractRejectionsAreNotRetriedEvenWithTransportLanguage() {
        for (code in listOf("client_tool_results_required", "terminal_job_not_resumable", "invalid_v14_request", "REASONING_OFF_UNSUPPORTED", "REASONING_ON_UNSUPPORTED", "context_capacity_unverified")) {
            assertNull(GitHubTaskRecovery.delayMillis("connection reset; HTTP 502", 0, code))
        }
    }

    @Test fun unknownGatewayOutcomeNeverEntersTransientRecovery() {
        val retryableMessage = "connection reset; HTTP 502; retryAfterMs=2000"
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN", "gateway_outcome_unknown")) {
            assertNull(GitHubTaskRecovery.delayMillis(retryableMessage, 0, code))
            assertNull(GitHubTaskRecovery.delayMillis("$code: $retryableMessage", 0))
            assertFalse(isRecoverableStreamFailure("$code: $retryableMessage"))
        }
        assertTrue(isRecoverableStreamFailure(retryableMessage))
        assertEquals(2000L, GitHubTaskRecovery.delayMillis(retryableMessage, 0))
    }

    @Test fun legacyUnsentValidationResultsCannotPoisonALaterCorrectedCall() = runTest {
        var rounds = 0
        var actions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("inspect_candidate", "Inspect an admitted candidate", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                if (arguments["candidate"] != kotlinx.serialization.json.JsonPrimitive(4)) return AgentToolResult(callId, ToolResultContent.Text("Local validation requires another candidate"), true, dispatched = false)
                actions++
                return AgentToolResult(callId, ToolResultContent.Text("Verified candidate"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                assertTrue(tools.any { it.name == tool.definition.name })
                val candidate = rounds++
                if (candidate <= 4) emit(ProviderEvent.ToolCall("candidate-$candidate", tool.definition.name, kotlinx.serialization.json.buildJsonObject { put("candidate", kotlinx.serialization.json.JsonPrimitive(candidate)) })) else emit(ProviderEvent.TextDelta("Verified candidate accepted."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits()).run(session, listOf(tool)).toList()
        assertEquals(1, actions)
        assertEquals(6, rounds)
        val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
        assertEquals(4, results.count { it.isError && !it.dispatched })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun unsentPolicyRejectionRemovesItsSchemaWithoutAProviderFailureCircuitOrUnknownWrite() = runTest {
        var rounds = 0
        var checks = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Authorized GitHub write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                checks++
                throw dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("PERSONAL_DATA_LOCAL_ONLY", "Local-only context cannot be shared")
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                if (rounds++ == 0) {
                    assertTrue(tools.any { it.name == tool.definition.name })
                    emit(ProviderEvent.ToolCall("policy", tool.definition.name, JsonObject(emptyMap())))
                } else {
                    assertFalse(tools.any { it.name == tool.definition.name })
                    emit(ProviderEvent.TextDelta("External authorization is required."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()
        assertEquals(1, checks)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
        assertEquals(1, results.size)
        assertTrue(results.all { !it.dispatched && it.errorCode == "PERSONAL_DATA_LOCAL_ONLY" })
    }

    @Test fun recoveryInstructionsRemainBoundedWithRepositoryContext() {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("connection", "owner", "repo", "feature", "a".repeat(40), "profile", stagedFileCount = 2, recentCommits = List(100) { "b".repeat(500) + "\nuntrusted continuation" }, observedAtMillis = 123)
        val prompt = context.promptContext()
        assertTrue(prompt.length < 2000)
        assertTrue(prompt.contains("github_watch_checks"))
        assertTrue(prompt.contains("2 changed file(s)"))
        assertFalse(prompt.contains("untrusted continuation"))
    }

    @Test fun unavailableLocalServerRetriesOnlyOnceAndHonorsCooldown() = runTest {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                rounds++
                emit(ProviderEvent.Failed("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=4290"))
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, emptyList()).toList()
        assertEquals(2, rounds)
        assertEquals(4290L, testScheduler.currentTime)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
    }

    @Test fun cooldownAndBoundsAreRespected() {
        assertEquals(15672L, GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=15672", 0))
        assertEquals(14031L, GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=14031", 0))
        assertEquals(4290L, GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=4290", 0))
        assertNull(GitHubTaskRecovery.delayMillis("HTTP 403 permission denied", 0))
        assertNull(GitHubTaskRecovery.delayMillis("HTTP 429 rate limit", 0))
        assertNull(GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE retryAfterMs=90000", 0))
        assertNull(GitHubTaskRecovery.delayMillis("connection reset", GitHubTaskRecovery.MAX_RECOVERIES))
    }

    @Test fun deterministicPolicyFailuresNeverBecomeTransportRecovery() {
        for (code in listOf("PERSONAL_DATA_LOCAL_ONLY", "PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED", "PRECHECK_REJECTED", "PERMISSION_DENIED", "AUTH_REQUIRED", "INVALID_ARGUMENTS", "UNSUPPORTED_OPERATION")) {
            assertNull(GitHubTaskRecovery.delayMillis("$code: connection reset; unexpected end of stream", 0))
        }
    }

    @Test fun transientFailureAfterVisibleProgressRetainsToolsAndDoesNotRepeatCompletedWrite() = runTest {
        var round = 0
        var writes = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_commit", "Commit authorized changes", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                writes++
                return AgentToolResult(callId, ToolResultContent.Text("commit receipt abc"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                when (round++) {
                    0 -> emit(ProviderEvent.ToolCall("commit", "github_commit", JsonObject(emptyMap())))
                    1 -> {
                        emit(ProviderEvent.TextDelta("The commit completed. Checking it now."))
                        emit(ProviderEvent.Failed("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=15672"))
                        return@flow
                    }
                    else -> {
                        assertTrue(tools.any { it.name == "github_commit" })
                        assertEquals("commit", exchanges.single().results.single().callId)
                        emit(ProviderEvent.TextDelta("The committed change is verified."))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, maxRounds = 2)).run(session, listOf(tool)).toList()
        assertEquals(3, round)
        assertEquals(1, writes)
        assertTrue(testScheduler.currentTime >= 15672L)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun malformedFrameAfterPreambleAndEmptyRoundKeepAuthorizedTools() = runTest {
        for (failureKind in listOf("protocol", "empty", "output_limit")) {
            var rounds = 0
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = error("No tool frame was authorized")
            }
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    if (rounds++ == 0) {
                        if (failureKind != "empty") {
                            emit(ProviderEvent.TextDelta("I am checking the repository."))
                            emit(ProviderEvent.Failed(if (failureKind == "protocol") "The provider returned unparsed tool-call markup." else "Provider reached the model output limit."))
                        } else {
                            emit(ProviderEvent.Completed)
                        }
                    } else {
                        assertTrue(tools.any { it.name == "github_read" })
                        emit(ProviderEvent.TextDelta("Verified outcome."))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()
            assertEquals(2, rounds)
            assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        }
    }

    @Test fun persistentOutageIsBoundedAndCancellationNeverRetries() = runTest {
        var rounds = 0
        val offline = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.Failed("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT"))
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(offline, emptyList()).toList()
        assertEquals(GitHubTaskRecovery.MAX_CONNECTION_RECOVERIES + 1, rounds)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        val cancelled = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow { throw CancellationException("Stopped") }
        }
        val failure = runCatching { AgentRunner(AgentRunLimits(githubTask = true)).run(cancelled, emptyList()).toList() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
