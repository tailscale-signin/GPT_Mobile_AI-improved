package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedResponseBudgetTest {
    @Test fun recoveryScalesWithFullContributionsAndHonorsBounds() {
        val body = """{"contributions":[{"response":"${"fact ".repeat(10000)}"}]}"""
        assertEquals(6, CombinedResponseBudget.continuationLimit(body, 4096, null))
        org.junit.Assert.assertTrue(CombinedResponseBudget.continuationLimit(body, 128, 100000) > 16)
        assertEquals(1, CombinedResponseBudget.continuationLimit("{}", null, null))
    }

    @Test fun recoverOnlyTransientEditorialStreamFailures() {
        assertTrue(isRecoverableStreamFailure("Read error: Failure in SSL library, usually a protocol error"))
        assertTrue(isRecoverableStreamFailure("Service temporarily overloaded"))
        assertTrue(isRecoverableStreamFailure("Software caused connection abort"))
        assertTrue(isRecoverableStreamFailure("recvfrom failed: ECONNABORTED"))
        assertFalse(isRecoverableStreamFailure("429 rate limit: Software caused connection abort"))
        assertFalse(isRecoverableStreamFailure("LLM7 has reached its free allowance. Try again in 85492 seconds"))
        assertFalse(isRecoverableStreamFailure("Connection refused"))
        assertFalse(isRecoverableStreamFailure("SSL certificate handshake error"))
    }
}
