package dev.chungjungsoo.gptmobile.data.agent

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 4)
        try {
            val first = checkpoint()
            val initial = ToolExecutionBudget(limits, initialCalls = first.initialize(4), durableCalls = first)
            assertFalse(initial.bind(tool).execute("first", buildJsonObject {}).isError)
            val recreated = checkpoint()
            assertEquals(1, recreated.initialize(4))
            val continued = ToolExecutionBudget(limits, initialCalls = recreated.initialize(4), durableCalls = recreated)
            assertFalse(continued.bind(tool).execute("second", buildJsonObject {}).isError)
            val exhausted = continued.bind(tool).execute("third", buildJsonObject {})
            assertEquals("VERIFICATION_RESERVE_REACHED", exhausted.errorCode)
            assertFalse(exhausted.dispatched)
            assertEquals(2, dispatched)
            assertEquals(2, checkpoint().initialize(200))
            assertEquals(3, checkpoint().acquire(200))
            assertEquals(4, checkpoint().acquire(200))
            assertNull(checkpoint().acquire(200))
        } finally {
            ToolCallBudgetCheckpoint.clearInMemoryChat(chatId)
        }
    }

    @Test fun githubPagesKeepIndependentByteAllowancesUnderTheHistorical24576ByteLimit() = runTest {
        var dispatched = 0
        val page = object : AgentTool {
            override val definition = AgentToolDefinition("github_pr", "bounded PR page", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("x".repeat(8192)), false)
            }
        }
        val githubBudget = ToolExecutionBudget(
            AgentRunLimits(githubTask = true, maxToolCalls = 24, maxToolOutputBytes = 24_576)
        )
        repeat(6) { index ->
            val result = githubBudget.bind(page).execute("github-page-$index", buildJsonObject {})
            assertFalse("GitHub page $index was withheld: ${result.errorCode}", result.isError)
            assertFalse("GitHub page $index exhausted a shared result budget", result.outputBudgetExhausted)
            assertEquals(24_576, githubBudget.remainingOutputBytes())
        }
        assertEquals(6, dispatched)
        assertEquals(6, githubBudget.usedCalls())
        assertNull(githubBudget.snapshot().resultBytesLimit)

        // Ordinary non-GitHub work still obeys the original cumulative byte
        // allowance; this exception is for authorized paged GitHub workflows.
        val standard = ToolExecutionBudget(
            AgentRunLimits(maxToolCalls = 24, maxToolOutputBytes = 24_576)
        )
        repeat(3) { index ->
            assertFalse(standard.bind(page).execute("ordinary-page-$index", buildJsonObject {}).isError)
        }
        assertEquals(0, standard.remainingOutputBytes())
        val fourth = standard.bind(page).execute("ordinary-page-4", buildJsonObject {})
        assertTrue(fourth.isError)
        assertFalse(fourth.dispatched)
        assertEquals(9, dispatched)
    }

    @Test fun oneAndTwoCallSourceAllowancesCanBeSpentAndRemainExhaustedAfterRestore() = runTest {
        for (limit in 1..2) {
            var row: JsonObject? = null
            val checkpoint = ToolCallBudgetCheckpoint("small-$limit", { row }, { row = it })
            val limits = AgentRunLimits(githubTask = true, maxToolCalls = limit)
            var dispatches = 0
            val source = object : AgentTool {
                override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatches++
                    return AgentToolResult(callId, ToolResultContent.Text("receipt"), false)
                }
            }
            repeat(limit) { index ->
                val budget = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(limit), durableCalls = checkpoint)
                assertFalse(budget.bind(source).execute("read-$index", buildJsonObject {}).isError)
            }
            val resumed = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(limit), durableCalls = checkpoint)
            assertEquals("BUDGET_EXHAUSTED", resumed.bind(source).execute("extra", buildJsonObject {}).errorCode)
            assertEquals(limit, dispatches)
        }
    }

    @Test fun retainedLocalPagesCanUseVerificationReserveWithoutRenewingTheDurableCeiling() = runTest {
        var row: JsonObject? = null
        var dispatches = 0
        val checkpoint = ToolCallBudgetCheckpoint("retained-verification", { row }, { row = it })
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                "github_read",
                "read",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("view_cursor", buildJsonObject { put("type", "string") })
                            put("ref", buildJsonObject { put("type", "string") })
                        }
                    )
                    put("additionalProperties", false)
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("retained bytes"), false)
            }
        }
        checkpoint.initialize(4)
        checkpoint.acquire(4)
        checkpoint.acquire(4)
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 4)
        suspend fun restored() = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(4), durableCalls = checkpoint)
        val reserve = restored().bind(tool)
        assertEquals("VERIFICATION_RESERVE_REACHED", reserve.execute("source", buildJsonObject { put("ref", "main") }).errorCode)
        assertEquals(
            "VERIFICATION_RESERVE_REACHED",
            reserve.execute(
                "mixed",
                buildJsonObject {
                    put("view_cursor", "saved:0:1024")
                    put("ref", "main")
                }
            ).errorCode
        )
        assertEquals("VERIFICATION_RESERVE_REACHED", reserve.execute("malformed", buildJsonObject { put("view_cursor", "not-an-issued-cursor") }).errorCode)
        assertEquals(0, dispatches)
        assertFalse(reserve.execute("local-first", buildJsonObject { put("view_cursor", "saved:0:1024") }).isError)
        val continued = restored().bind(tool)
        assertFalse(continued.execute("local-next", buildJsonObject { put("view_cursor", "saved:1:1024") }).isError)
        val exhausted = restored().bind(tool).execute("local-extra", buildJsonObject { put("view_cursor", "saved:2:1024") })
        assertEquals("BUDGET_EXHAUSTED", exhausted.errorCode)
        assertFalse(exhausted.dispatched)
        assertEquals(2, dispatches)
        assertEquals(4, checkpoint.initialize(200))
    }

    @Test fun retainedPageReserveStillRequiresCurrentAuthorizationBeforeCharging() = runTest {
        var dispatched = false
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                "github_read",
                "read",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("view_cursor", buildJsonObject { put("type", "string") }) })
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched = true
                return AgentToolResult(callId, ToolResultContent.Text("retained bytes"), false)
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 4), initialCalls = 2)
        val denied = budget.bind(tool, authorize = { _, _ -> false }).execute("revoked", buildJsonObject { put("view_cursor", "saved:0:1024") })
        assertEquals("AUTH_REQUIRED", denied.errorCode)
        assertFalse(dispatched)
        assertEquals(2, budget.usedCalls())
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
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 4)
        val first = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(4), durableCalls = checkpoint)
        assertFalse(first.bind(tool("github_read")).execute("first", buildJsonObject {}).isError)
        val restored = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(4), durableCalls = checkpoint)
        assertFalse(restored.bind(tool("github_status")).execute("second", buildJsonObject {}).isError)
        val rejected = restored.bind(tool("github_read")).execute("third", buildJsonObject {})
        assertEquals("VERIFICATION_RESERVE_REACHED", rejected.errorCode)
        assertFalse(rejected.dispatched)
        assertEquals(2, dispatched)
    }

    @Test fun unexpectedAuthorizationPrecheckErrorIsTypedAndConsumesNoToolCall() = runTest {
        var toolDispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                toolDispatched++
                return AgentToolResult(callId, ToolResultContent.Text("This must not execute"), false)
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 8))
        val error = budget.bind(tool, authorize = { _, _ ->
            throw IllegalStateException("PRIVATE_KEY_SHOULD_NOT_APPEAR_IN_A_RESULT")
        }).execute("precheck-failed", buildJsonObject {})
        assertTrue(error.isError)
        assertEquals("TOOL_PRECHECK_FAILED", error.errorCode)
        assertFalse(error.dispatched)
        assertEquals(0, toolDispatched)
        assertEquals(0, budget.usedCalls())
        assertEquals(JsonPrimitive("tool_precheck"), error.executionReceipt?.get("failed_stage"))
        assertEquals(JsonPrimitive("AUTHORIZATION_CALLBACK"), error.executionReceipt?.get("precheck_origin"))
        assertTrue(error.executionReceipt.toString().contains("exception_type_hashes"))
        assertFalse(error.toString().contains("PRIVATE_KEY_SHOULD_NOT_APPEAR_IN_A_RESULT"))
    }

    @Test fun unavailableBudgetStorageFailsClosedAndAuthorizationConsumesNoSlot() = runTest {
        var row: JsonObject? = null
        var broken = false
        var dispatched = 0
        val checkpoint = ToolCallBudgetCheckpoint("storage", { row }, { if (broken) error("disk unavailable") else row = it })
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 3), initialCalls = checkpoint.initialize(3), durableCalls = checkpoint)
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
        val other = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 3), durableCalls = checkpoint)
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
