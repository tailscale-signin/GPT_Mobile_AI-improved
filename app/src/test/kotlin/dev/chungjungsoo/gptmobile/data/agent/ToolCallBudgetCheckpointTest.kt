package dev.chungjungsoo.gptmobile.data.agent

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
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
    @Test fun temporaryBudgetRecreationPreservesTheAcceptedLimitAcrossBoundTools() = runTest {
        val chatId = memoryChatIds.incrementAndGet()
        var dispatched = 0
        fun checkpoint() = ToolCallBudgetCheckpoint.inMemory(chatId, "accepted-source:profile")
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("receipt"), false)
            }
        }
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 2)
        try {
            val first = checkpoint()
            val initial = ToolExecutionBudget(limits, initialCalls = first.initialize(2), durableCalls = first)
            assertFalse(initial.bind(tool).execute("first", buildJsonObject {}).isError)
            val recreated = checkpoint()
            assertEquals(1, recreated.initialize(2))
            val continued = ToolExecutionBudget(limits, initialCalls = recreated.initialize(2), durableCalls = recreated)
            assertFalse(continued.bind(tool).execute("second", buildJsonObject {}).isError)
            val exhausted = continued.bind(tool).execute("third", buildJsonObject {})
            assertEquals("BUDGET_EXHAUSTED", exhausted.errorCode)
            assertFalse(exhausted.dispatched)
            assertEquals(2, dispatched)
            assertEquals(2, checkpoint().initialize(200))
            assertNull(checkpoint().acquire(200))
        } finally {
            ToolCallBudgetCheckpoint.clearInMemoryChat(chatId)
        }
    }

    @Test fun concurrentTemporaryHandlesReserveAgainstOneCounter() = runTest {
        val chatId = memoryChatIds.incrementAndGet()
        try {
            ToolCallBudgetCheckpoint.inMemory(chatId, "accepted-source").initialize(16)
            val reservations = (1..48).map {
                async(Dispatchers.Default) { ToolCallBudgetCheckpoint.inMemory(chatId, "accepted-source").acquire(16) }
            }.awaitAll()
            assertEquals((1..16).toList(), reservations.filterNotNull().sorted())
            assertEquals(16, ToolCallBudgetCheckpoint.inMemory(chatId, "accepted-source").initialize(16))
        } finally {
            ToolCallBudgetCheckpoint.clearInMemoryChat(chatId)
        }
    }

    @Test fun clearingTemporaryChatClosesKnownWorkersButAllowsANewTaskAndOtherChats() = runTest {
        val chatId = memoryChatIds.incrementAndGet()
        val otherChatId = memoryChatIds.incrementAndGet()
        assertFalse(ToolCallBudgetCheckpoint.hasInMemoryAllowance(chatId, "old-source"))
        val initialized = ToolCallBudgetCheckpoint.inMemory(chatId, "old-source")
        val notInitialized = ToolCallBudgetCheckpoint.inMemory(chatId, "waiting-source")
        val otherChat = ToolCallBudgetCheckpoint.inMemory(otherChatId, "old-source")
        try {
            assertTrue(ToolCallBudgetCheckpoint.hasInMemoryAllowance(chatId, "old-source"))
            assertTrue(ToolCallBudgetCheckpoint.hasInMemoryAllowance(chatId, "waiting-source"))
            assertFalse(ToolCallBudgetCheckpoint.hasInMemoryAllowance(otherChatId, "waiting-source"))
            initialized.initialize(2)
            assertEquals(1, initialized.acquire(2))
            otherChat.initialize(2)
            assertEquals(1, otherChat.acquire(2))
            ToolCallBudgetCheckpoint.clearInMemoryChat(chatId)
            assertTrue(ToolCallBudgetCheckpoint.hasInMemoryAllowance(chatId, "old-source"))
            assertTrue(ToolCallBudgetCheckpoint.hasInMemoryAllowance(chatId, "waiting-source"))
            assertTrue(runCatching { initialized.acquire(2) }.isFailure)
            assertTrue(runCatching { initialized.initialize(2) }.isFailure)
            assertTrue(runCatching { notInitialized.initialize(2) }.isFailure)
            assertTrue(runCatching { ToolCallBudgetCheckpoint.inMemory(chatId, "old-source").initialize(2, "additional-allowance") }.isFailure)
            assertTrue(runCatching { ToolCallBudgetCheckpoint.inMemory(chatId, "waiting-source").initialize(2) }.isFailure)
            assertFalse(ToolCallBudgetCheckpoint.hasInMemoryAllowance(chatId, "new-source"))
            val newTask = ToolCallBudgetCheckpoint.inMemory(chatId, "new-source")
            assertEquals(0, newTask.initialize(1))
            assertEquals(1, newTask.acquire(1))
            assertEquals(1, ToolCallBudgetCheckpoint.inMemory(otherChatId, "old-source").initialize(2))
            assertEquals(2, otherChat.acquire(2))
        } finally {
            ToolCallBudgetCheckpoint.clearInMemoryChat(chatId)
            ToolCallBudgetCheckpoint.clearInMemoryChat(otherChatId)
        }
    }

    @Test fun temporaryAllowanceRenewalIsExplicitAndIdempotentAcrossRecreatedHandles() = runTest {
        val chatId = memoryChatIds.incrementAndGet()
        fun checkpoint() = ToolCallBudgetCheckpoint.inMemory(chatId, "accepted-source")
        try {
            checkpoint().initialize(1)
            assertEquals(1, checkpoint().acquire(1))
            assertEquals(1, checkpoint().initialize(4))
            assertNull(checkpoint().acquire(4))
            assertEquals(0, checkpoint().initialize(2, "additional-allowance"))
            assertEquals(1, checkpoint().acquire(2))
            assertEquals(1, checkpoint().initialize(2, "additional-allowance"))
            assertEquals(2, checkpoint().acquire(2))
            assertNull(checkpoint().acquire(2))
        } finally {
            ToolCallBudgetCheckpoint.clearInMemoryChat(chatId)
        }
    }

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

    companion object {
        private val memoryChatIds = AtomicInteger(10_000)
    }
}
