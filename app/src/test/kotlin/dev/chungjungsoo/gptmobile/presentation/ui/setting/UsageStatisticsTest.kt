package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.dao.RunOutputLength
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatisticsTest {
    @Test fun generatedTokensExcludeInputAndUnknownUsageAndRespectDateRange() {
        val today = LocalDate.of(2026, 9, 25)
        fun run(id: String, age: Long, output: Int?) = AgentRun(
            id, 1, 2, 3, "profile", "OpenAI", "model", AgentRunStatus.COMPLETED,
            createdAt = today.minusDays(age).atStartOfDay(ZoneId.systemDefault()).toEpochSecond(),
            inputTokens = 500, outputTokens = output, totalTokens = output?.plus(500)
        )
        val result = calculateUsageStatistics(listOf(run("a", 0, 25), run("b", 6, 75), run("c", 0, null), run("old", 7, 999)), mapOf("profile" to "Writing"), 7, today)
        assertEquals(3, result.runs)
        assertEquals(100L, result.generatedTokens)
        assertEquals(1500L, result.inputTokens)
        assertEquals(2, result.reportedRuns)
        assertEquals(listOf("Writing" to 100L), result.profileTokens)
        assertEquals(7, result.dailyTokens.size)
        assertEquals(25L, result.dailyTokens.last().second)
    }

    private val today = LocalDate.of(2026, 9, 26)
    private val timestamp = today.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    private fun run(id: String, status: String = AgentRunStatus.COMPLETED, output: Int? = null) =
        AgentRun(id, 1, 2, 3, "profile", "provider", "model", status, createdAt = timestamp, outputTokens = output)

    @Test
    fun successExcludesCanceledAndActiveRunsAndCountsInterruptions() {
        val runs = listOf(run("ok"), run("bad", AgentRunStatus.FAILED), run("interrupted", AgentRunStatus.INTERRUPTED), run("cancel", AgentRunStatus.CANCELED), run("active", AgentRunStatus.RUNNING))
        val result = calculateUsageStatistics(runs, emptyMap(), 7, today)
        assertEquals(100.0 / 3, result.successPercent!!, 0.001)
        assertNull(calculateUsageStatistics(listOf(run("active", AgentRunStatus.RUNNING)), emptyMap(), 7, today).successPercent)
    }

    @Test
    fun estimatesDoNotReplaceReportedZeroOrIncludeFailedOrOldRuns() {
        val runs = listOf(run("estimate"), run("zero", output = 0), run("actual", output = 12), run("failed", AgentRunStatus.FAILED), run("missing"), run("old").copy(createdAt = timestamp - 8 * 86400))
        val lengths = runs.filter { it.runId != "missing" }.map { RunOutputLength(it.runId, 9) }
        val result = calculateUsageStatistics(runs, emptyMap(), 7, today, lengths)
        assertEquals(12L, result.generatedTokens)
        assertEquals(2, result.reportedRuns)
        assertEquals(3L, result.estimatedTokens)
        assertEquals(1, result.estimatedRuns)
        assertEquals(12L, result.dailyTokens.last().second)
    }

    @Test
    fun sameDisplayNamesAndModelsDoNotCollapseDifferentProfileOrProviderGroups() {
        val runs = listOf(run("a"), run("b").copy(profileUid = "other"), run("c").copy(providerSnapshot = "different"))
        val result = calculateUsageStatistics(runs, mapOf("profile" to "Writing", "other" to "Writing"), 0, today)
        assertEquals(3, result.profileModelUsage.size)
        assertEquals(3, result.profileModelUsage.sumOf { it.runs })
    }

    @Test
    fun toolRankingFiltersDatesAndCountsProtocolErrorsWithoutCombiningServers() {
        fun tool(id: String, connection: String, time: Long?, error: Boolean = false) = ToolEvent(
            eventId = id, runId = "run", sequence = 0, callId = id,
            connectionUidSnapshot = connection, connectionNameSnapshot = connection,
            toolName = "search", modelToolName = "search", arguments = "{}", result = null,
            resultType = null, status = ToolEventStatus.COMPLETED, isError = error, startedAt = time
        )
        val events = listOf(tool("a", "A", timestamp), tool("b", "A", timestamp, true), tool("c", "B", timestamp), tool("old", "A", timestamp - 8 * 86400), tool("future", "A", timestamp + 86400), tool("unknown", "A", null))
        val result = calculateUsageStatistics(emptyList(), emptyMap(), 7, today, tools = events)
        assertEquals(3, result.toolCalls)
        assertEquals(1, result.failedTools)
        assertEquals(listOf(2, 1), result.toolUsage.map { it.calls })
        assertEquals("A · search", result.toolUsage.first().name)
    }
}
