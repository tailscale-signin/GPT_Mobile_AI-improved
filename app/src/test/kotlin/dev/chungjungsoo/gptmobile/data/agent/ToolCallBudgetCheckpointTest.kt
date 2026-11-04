package dev.chungjungsoo.gptmobile.data.agent

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallBudgetCheckpointTest {
    @Test fun unreadableExistingAllowanceCannotBeResetAndLegacyCallsAreRetained() = runTest {
        var writes = 0
        val unreadable = ToolCallBudgetCheckpoint("unreadable", { null }, { writes++ }, hasRecord = { true })
        assertTrue(runCatching { unreadable.initialize(256) }.isFailure)
        assertTrue(runCatching { unreadable.initialize(256, "explicit-retry") }.isFailure)
        assertEquals(0, writes)
        var row: JsonObject? = null
        val migrated = ToolCallBudgetCheckpoint("migration", { row }, { row = it })
        assertEquals(255, migrated.initialize(256, previouslyDispatched = 255))
        assertEquals(256, migrated.acquire(256))
        assertNull(migrated.acquire(256))
        assertEquals(256, migrated.initialize(256, previouslyDispatched = 0))
    }

    @Test fun deletingAWorkspaceRowCannotRenewAnInitializedAllowance() = runTest {
        val directory = Files.createTempDirectory("tool-budget-fence").toFile()
        try {
            var row: JsonObject? = null
            fun checkpoint(): ToolCallBudgetCheckpoint {
                val fence = ToolCallBudgetFence.forTurn(directory, 7, "turn-profile")
                return ToolCallBudgetCheckpoint(
                    "turn-profile",
                    { row?.also { fence.mark() } },
                    { value ->
                        fence.mark()
                        row = value
                    },
                    hasRecord = fence::exists
                )
            }
            assertEquals(0, checkpoint().initialize(1))
            assertEquals(1, checkpoint().acquire(1))
            row = null // Inspectable workspace data was deleted or lost.
            assertTrue(runCatching { checkpoint().initialize(1) }.isFailure)
            assertTrue(runCatching { checkpoint().initialize(1, "retry") }.isFailure)
            assertTrue(ToolCallBudgetFence.forTurn(directory, 7, "turn-profile").exists())
            assertFalse(ToolCallBudgetFence.forTurn(directory, 8, "turn-profile").exists())
            ToolCallBudgetFence.deleteChat(directory, 7)
            assertTrue(runCatching { checkpoint().initialize(1) }.isFailure)
            assertTrue(runCatching { ToolCallBudgetFence.forTurn(directory, 7, "turn-profile").mark() }.isFailure)
            assertFalse(directory.resolve("tool-budget-fences/7").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun concurrentReservationsAndRecreatedBudgetsShareOneAcceptedLimit() = runTest {
        var row: JsonObject? = null
        fun checkpoint() = ToolCallBudgetCheckpoint("concurrent", { row }, { row = it })
        val first = checkpoint()
        assertEquals(0, first.initialize(256))
        val admissions = (1..300).map { async { first.acquire(256) } }.awaitAll()
        assertEquals(256, admissions.count { it != null })
        assertEquals(256, admissions.filterNotNull().distinct().size)
        val resumed = checkpoint()
        assertEquals(256, resumed.initialize(512))
        assertNull(resumed.acquire(512))
    }

    @Test fun renewalIsExplicitAndIdempotentAcrossProcessRestarts() = runTest {
        var row: JsonObject? = null
        fun checkpoint() = ToolCallBudgetCheckpoint("renewal", { row }, { row = it })
        checkpoint().initialize(1)
        assertEquals(1, checkpoint().acquire(1))
        assertEquals(1, checkpoint().initialize(10))
        assertNull(checkpoint().acquire(10))
        assertEquals(0, checkpoint().initialize(2, "user-retry-1"))
        assertEquals(1, checkpoint().acquire(2))
        assertEquals(1, checkpoint().initialize(2, "user-retry-1"))
        assertEquals(1, checkpoint().initialize(2))
        assertEquals(2, checkpoint().acquire(2))
        assertNull(checkpoint().acquire(2))
    }

    @Test fun restoredSharedBudgetBlocksEveryBoundToolBeforeDispatch() = runTest {
        var row: JsonObject? = null
        var dispatched = 0
        val checkpoint = ToolCallBudgetCheckpoint("bound", { row }, { row = it })
        fun tool(name: String) = object : AgentTool {
            override val definition = AgentToolDefinition(name, "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("receipt"), false)
            }
        }
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 2)
        val first = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(2), durableCalls = checkpoint)
        assertFalse(first.bind(tool("github_read")).execute("first", buildJsonObject {}).isError)
        val restored = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(2), durableCalls = checkpoint)
        assertFalse(restored.bind(tool("github_status")).execute("second", buildJsonObject {}).isError)
        val rejected = restored.bind(tool("github_read")).execute("third", buildJsonObject {})
        assertEquals("BUDGET_EXHAUSTED", rejected.errorCode)
        assertFalse(rejected.dispatched)
        assertEquals(2, dispatched)
    }

    @Test fun unavailableBudgetStorageFailsClosedAndAuthorizationConsumesNoSlot() = runTest {
        var row: JsonObject? = null
        var broken = false
        var dispatched = 0
        val checkpoint = ToolCallBudgetCheckpoint("storage", { row }, { if (broken) error("disk unavailable") else row = it })
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 1), initialCalls = checkpoint.initialize(1), durableCalls = checkpoint)
        val raw = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("receipt"), false)
            }
        }
        val denied = budget.bind(raw, authorize = { _, _ -> false }).execute("denied", buildJsonObject {})
        assertFalse(denied.dispatched)
        assertEquals(0, budget.usedCalls())
        broken = true
        val other = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 1), durableCalls = checkpoint)
        val failed = other.bind(raw).execute("disk-failure", buildJsonObject {})
        assertTrue(failed.isError)
        assertEquals("BUDGET_CHECKPOINT_UNAVAILABLE", failed.errorCode)
        assertFalse(failed.dispatched)
        assertEquals(0, dispatched)
    }
}
