package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
}
