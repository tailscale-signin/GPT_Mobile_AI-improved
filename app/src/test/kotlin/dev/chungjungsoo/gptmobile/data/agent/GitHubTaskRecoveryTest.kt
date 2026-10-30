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
    @Test fun cooldownAndBoundsAreRespected() {
        assertEquals(15672L, GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=15672", 0))
        assertEquals(14031L, GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=14031", 0))
        assertEquals(4290L, GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT; retryAfterMs=4290", 0))
        assertNull(GitHubTaskRecovery.delayMillis("HTTP 403 permission denied", 0))
        assertNull(GitHubTaskRecovery.delayMillis("HTTP 429 rate limit", 0))
        assertNull(GitHubTaskRecovery.delayMillis("LOCAL_SERVICE_UNREACHABLE retryAfterMs=90000", 0))
        assertNull(GitHubTaskRecovery.delayMillis("connection reset", GitHubTaskRecovery.MAX_RECOVERIES))
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
        assertEquals(GitHubTaskRecovery.MAX_RECOVERIES + 1, rounds)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        val cancelled = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow { throw CancellationException("Stopped") }
        }
        val failure = runCatching { AgentRunner(AgentRunLimits(githubTask = true)).run(cancelled, emptyList()).toList() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
