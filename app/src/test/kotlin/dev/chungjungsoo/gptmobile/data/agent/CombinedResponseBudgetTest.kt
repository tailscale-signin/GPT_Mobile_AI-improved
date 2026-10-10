package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedResponseBudgetTest {
    @Test fun recoveryScalesWithFullContributionsAndHonorsBounds() {
        val body = """{"contributions":[{"response":"${"fact ".repeat(10000)}"}]}"""
        assertEquals(6, CombinedResponseBudget.continuationLimit(body, 4096, null))
        assertEquals(16, CombinedResponseBudget.continuationLimit(body, 128, 100000))
        assertEquals(3, CombinedResponseBudget.continuationLimit("{}", null, null))
    }

    @Test fun recoverOnlyTransientEditorialStreamFailures() {
        assertTrue(isRecoverableStreamFailure("Read error: Failure in SSL library, usually a protocol error"))
        assertTrue(isRecoverableStreamFailure("Service temporarily overloaded"))
        assertFalse(isRecoverableStreamFailure("LLM7 has reached its free allowance. Try again in 85492 seconds"))
        assertFalse(isRecoverableStreamFailure("Connection refused"))
        assertFalse(isRecoverableStreamFailure("SSL certificate handshake error"))
    }
}
