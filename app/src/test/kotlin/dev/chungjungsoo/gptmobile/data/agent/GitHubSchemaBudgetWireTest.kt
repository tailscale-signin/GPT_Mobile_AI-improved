package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSchemaBudgetWireTest {
    @Test fun reportedSmallSlotRejectsIrreducibleInputAndAcceptsCompactScaffolding() {
        val requested = GitHubSchemaBudget(5632, 4008, 512, 0, wireSafety = true).withReserves(512, 704)
        assertThrows(GitHubToolContextBudgetException::class.java) { requested.fitInitialSchemas(1129, true) }
        val compact = requested.copy(input = 2050).fitInitialSchemas(1129, true)
        val input = compact.input + 1129 + requireNotNull(compact.toolResults)
        assertTrue(input + RequestAdmission.estimateSafety(input) + compact.output <= compact.capacity)
    }

    @Test fun fittedSchemasAndReplayRemainInsideSerializedSafetyForEveryRoundingRemainder() {
        for (capacity in listOf(4096, 5632, 8192)) {
            for (offset in 0..9) {
                val request = GitHubSchemaBudget(capacity, 620L + offset, 2500, 0, wireSafety = true).withReserves(2500, 704)
                val fitted = request.fitInitialSchemas(1430L, allowOutputReduction = true)
                assertTrue(fitted.available >= 1430L)
                val prompt = fitted.input + fitted.available + requireNotNull(fitted.toolResults)
                assertTrue(prompt + RequestAdmission.estimateSafety(prompt) + fitted.output <= capacity)
                assertEquals(capacity.toLong(), prompt + fitted.safety + fitted.output)
            }
        }
    }
}
