package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalAssistQueueTest {
    @Test fun timeoutWhileWaitingDoesNotCancelOwnerAndLaneRemainsUsable() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = async {
            LocalAssistQueue.run(100) {
                release.await()
                "first"
            }
        }
        runCurrent()
        assertNull(LocalAssistQueue.run(10) { "should not execute" })
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("third", LocalAssistQueue.run(10) { "third" })
    }

    @Test fun cancellingOwnerReleasesLaneAndWaitingDeadlineDoesNotBoundGeneration() = runTest {
        val first = async {
            LocalAssistQueue.run(10) {
                delay(10_000)
                "cancelled"
            }
        }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(
            "complete",
            LocalAssistQueue.run(1) {
                delay(100)
                "complete"
            }
        )
    }

    @Test fun waitingCancellationDoesNotStealLease() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = async {
            LocalAssistQueue.run(100) {
                release.await()
                true
            }
        }
        runCurrent()
        val waiter = async { LocalAssistQueue.run(100) { error("cancelled waiter dispatched") } }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        assertTrue(first.await() == true)
        assertEquals(3, LocalAssistQueue.run(10) { 3 })
    }

    @Test fun preparationPinsPostLoadIdentityAndAcademicSchemaIsRetained() = runTest {
        var fingerprint = "cold"
        var calls = 0
        val content = ToolResultContent.Json(JsonObject(mapOf("papers" to JsonArray((1..8).map { JsonPrimitive("paper $it") }))))
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("academic_source", "source", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, content, false)
        }
        val worker = AssistCoordinator("goal", {
            fingerprint = "warm"
            true
        }, {
            calls++
            "[0]"
        }, {}, turnId = UUID.randomUUID().toString(), runtimeFingerprint = { fingerprint })
        val result = worker.bind(tool).execute("call", JsonObject(emptyMap()))
        assertEquals(1, calls)
        assertEquals(content, result.content)
        assertFalse(result.assistMetadata.isEmpty())
    }

    @Test fun replacingPreparedRuntimeBeforeReplyRejectsAssistanceButPreservesEvidence() = runTest {
        var fingerprint = "cold"
        var calls = 0
        val content = ToolResultContent.Json(JsonObject(mapOf("papers" to JsonArray((1..8).map { JsonPrimitive("paper $it") }))))
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("academic_source", "source", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, content, false)
        }
        val worker = AssistCoordinator("goal", {
            fingerprint = "warm"
            true
        }, {
            calls++
            fingerprint = "different-runtime"
            "[0]"
        }, {}, turnId = UUID.randomUUID().toString(), runtimeFingerprint = { fingerprint })
        val result = worker.bind(tool).execute("call", JsonObject(emptyMap()))
        assertEquals(1, calls)
        assertEquals(content, result.content)
        assertTrue(result.assistMetadata.isEmpty())
    }

    @Test fun physicalAttemptAllowanceIsSharedAcrossTaskKinds() {
        val turn = UUID.randomUUID().toString()
        assertTrue(AssistTurnBudget.available(turn, 2))
        assertTrue(AssistTurnBudget.reserve(turn, 2))
        assertTrue(AssistTurnBudget.reserve(turn, 2))
        assertFalse(AssistTurnBudget.reserve(turn, 2))
        assertFalse(AssistTurnBudget.available(turn, 2))
        assertTrue(AssistTurnBudget.available(UUID.randomUUID().toString(), 2))
    }
}
