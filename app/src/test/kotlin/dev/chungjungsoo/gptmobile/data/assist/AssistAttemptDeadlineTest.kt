package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptRejected
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistAttemptDeadlineTest {
    @Test fun longQueueWaitDoesNotSpendTheGenerationClockOrAttemptAllowance() = runTest {
        var attempts = 0
        val result = withAssistAttemptDeadline(2000, authorize = { attempts++ }) {
            delay(15000)
            assertEquals(0, attempts)
            PhysicalAttemptBoundary.dispatch()
            delay(1000)
            "artifact"
        }
        assertEquals("artifact", result)
        assertEquals(1, attempts)
    }

    @Test fun generationDeadlineCancelsAndCleansUpThePhysicalProducer() = runTest {
        var stopped = false
        val result = withAssistAttemptDeadline(2000, authorize = {}) {
            PhysicalAttemptBoundary.dispatch()
            try {
                delay(10000)
                "late artifact"
            } finally {
                stopped = true
            }
        }
        assertNull(result)
        assertTrue(stopped)
    }

    @Test fun boundedPreparationCannotWaitForeverAndDoesNotChargeAnAttempt() = runTest {
        var attempts = 0
        val outcome = runCatching {
            withAssistAttemptDeadline(2000, preparationMillis = 5000, authorize = { attempts++ }) {
                delay(10000)
                PhysicalAttemptBoundary.dispatch()
                "late artifact"
            }
        }
        assertEquals("PRE_DISPATCH_DEADLINE", (outcome.exceptionOrNull() as PhysicalAttemptRejected).reason)
        assertEquals(0, attempts)
    }
}
