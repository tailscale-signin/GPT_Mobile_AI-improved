package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedFeedbackPolicyTest {
    @Test fun straightforwardComparisonUsesBoundedScopeAndKeepsPrimary() {
        val request = "What is the difference between panzerotti and calzone?"
        assertNull(CombinedRequestScope.defaultMaximumWords(request))
        assertEquals(listOf("a", "b", "i"), CombinedRequestScope.participants(request, ('a'..'i').map { it.toString() }, "i", true, false))
    }

    @Test fun researchAndExplicitSelectionRetainAllProfiles() {
        val profiles = ('a'..'i').map { it.toString() }
        assertEquals(profiles, CombinedRequestScope.participants("Compare these foods", profiles, "i", true, true))
        assertEquals(profiles, CombinedRequestScope.participants("Compare these foods", profiles, "i", false, false))
        for (request in listOf("Write a comprehensive comparison", "Write 2,000 words", "Review the history of Italy", "Debug this code")) {
            assertNull(CombinedRequestScope.defaultMaximumWords(request))
            assertEquals(profiles, CombinedRequestScope.participants(request, profiles, "i", true, false))
        }
    }

    @Test fun permanentProviderErrorsCannotBeRetriedAsIncomplete() {
        for ((details, category) in listOf(
            "HTTP 410 Gone" to "ENDPOINT_RETIRED",
            "HTTP 410 timeout" to "ENDPOINT_RETIRED",
            "HTTP/1.1 410" to "ENDPOINT_RETIRED",
            "Request failed with status: 410" to "ENDPOINT_RETIRED",
            "{\"status\":410}" to "ENDPOINT_RETIRED",
            "HTTP status code 402" to "BILLING_UNAVAILABLE",
            "Your credit balance is too low to access the Anthropic API" to "BILLING_UNAVAILABLE",
            "402 Payment required" to "BILLING_UNAVAILABLE",
            "Insufficient credits; quota exhausted" to "BILLING_UNAVAILABLE"
        )) {
            val failure = ProfileFailureRecord.classify(details)
            assertEquals(category, failure.category)
            assertFalse(failure.retryAllowed)
        }
        assertTrue(ProfileFailureRecord.classify("503 temporarily overloaded").retryAllowed)
        assertEquals("RATE_LIMIT", ProfileFailureRecord.classify("429 rate limited").category)
    }

    @Test fun tokenCountsAndRequestIdsDoNotDisableProviderRetries() {
        for (details in listOf("timeout after 410 tokens", "connection closed after 402 bytes", "NO_VISIBLE_ANSWER: output_tokens=410", "NO_VISIBLE_ANSWER: request_id=402", "HTTP 503: received 410 tokens before timeout")) {
            val failure = ProfileFailureRecord.classify(details)
            assertTrue(details, failure.retryAllowed)
            assertFalse(details, failure.category in setOf("ENDPOINT_RETIRED", "BILLING_UNAVAILABLE"))
        }
    }
}
