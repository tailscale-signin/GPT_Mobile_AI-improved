package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLimitDecisionTest {
    private val config = ProviderRequestConfig(
        apiUrl = FreeAiProvider.LLM7.apiUrl,
        token = "request-specific-test-key",
        freeProvider = FreeAiProvider.LLM7,
        profileUid = "profile"
    )

    @Test fun requestSpecificTokenRejectionRetainsItsExactDeadline() {
        val deadline = 87_400_123L
        val failure = FreeAiRateLimitException(FreeAiProvider.LLM7, 86_400, retryAt = deadline)
        val decision = requireNotNull(providerLimitDecision(config, "model", receivedPayload = false, failure))
        assertEquals(deadline, decision.retryAt)
        assertEquals("profile", decision.profileUid)
        assertEquals("model", decision.model)
        assertEquals(FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED.name, decision.category)
        assertTrue(decision.knownNotAccepted)
    }

    @Test fun aLimitAfterReceivedOutputCannotAuthorizeAutomaticReplay() {
        val failure = FreeAiRateLimitException(FreeAiProvider.LLM7, 60, retryAt = 60_123L)
        val decision = requireNotNull(providerLimitDecision(config, "model", receivedPayload = true, failure))
        assertFalse(decision.knownNotAccepted)
    }

    @Test fun anUntypedUserFacingMessageCannotCreateAWait() {
        assertNull(providerLimitDecision(config, "model", receivedPayload = false, IllegalStateException("Rate limited until tomorrow")))
    }

    @Test fun onlyAnUnacceptedExplicitContinuationCarriesItsSavedPrefix() {
        val digest = "a".repeat(64)
        val failure = FreeAiRateLimitException(FreeAiProvider.LLM7, 60, retryAt = 60_123L)
        val continuation = config.copy(requestRole = "requested_length_continuation", continuationPrefixDigest = digest)
        val rejected = requireNotNull(providerLimitDecision(continuation, "model", receivedPayload = false, failure))
        assertTrue(rejected.knownNotAccepted)
        assertEquals(digest, rejected.continuationPrefixDigest)
        assertNull(providerLimitDecision(continuation, "model", receivedPayload = true, failure)!!.continuationPrefixDigest)
        assertNull(providerLimitDecision(continuation.copy(requestRole = "primary"), "model", receivedPayload = false, failure)!!.continuationPrefixDigest)
        assertNull(providerLimitDecision(config, "model", receivedPayload = false, failure)!!.continuationPrefixDigest)
        assertFalse(Json { encodeDefaults = true }.encodeToString(rejected.copy(continuationPrefixDigest = null)).contains("continuationPrefixDigest"))
    }
}
