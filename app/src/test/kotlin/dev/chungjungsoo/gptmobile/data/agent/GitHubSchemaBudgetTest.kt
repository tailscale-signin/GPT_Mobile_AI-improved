package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSchemaBudgetTest {
    @Test fun verifiedRouteSchemaWindowIncludesFinalEstimateSafetyAndReplay() {
        val budget = GitHubSchemaBudget(5632, 2600, 512, 256, 704, wireSafety = true)
        assertEquals(792L, budget.available)
        val fitted = budget.fitInitialSchemas(900, false)
        assertEquals(596, fitted.toolResults)
        assertEquals(512, fitted.output)
        assertEquals(900L, fitted.available)
        val completeInput = fitted.input + 900 + requireNotNull(fitted.toolResults)
        assertTrue(completeInput + RequestAdmission.estimateSafety(completeInput) + fitted.output <= fitted.capacity)
        assertThrows(GitHubToolContextBudgetException::class.java) { budget.fitInitialSchemas(985, false) }
    }

    @Test fun anAdaptiveOutputTargetCanYieldButAnExplicitOutputLimitCannot() {
        val budget = GitHubSchemaBudget(5632, 2000, 2048, 256, 704, wireSafety = true)
        assertThrows(GitHubToolContextBudgetException::class.java) { budget.fitInitialSchemas(1000, false) }
        val fitted = budget.fitInitialSchemas(1000, true)
        assertTrue(fitted.output in 512 until 2048)
        assertTrue(requireNotNull(fitted.toolResults) >= 512)
        val completeInput = fitted.input + 1000 + requireNotNull(fitted.toolResults)
        assertTrue(completeInput + RequestAdmission.estimateSafety(completeInput) + fitted.output <= 5632)
        assertEquals(5632, fitted.capacity)
    }

    @Test fun roundWindowsCannotSpendTheReservedWireSafety() {
        val budget = GitHubSchemaBudget(4096, 224, 622, 256, wireSafety = true)
        assertEquals(2555L, budget.available)
        assertEquals(695, budget.reservedSafety)
        assertEquals(0L, budget.baseOverrun)
        assertEquals(0L, budget.copy(input = 3000).available)
        assertEquals(221L, budget.copy(input = 3000).baseOverrun)
    }
}
