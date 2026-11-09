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

    @Test fun paidOpenRouterRejectionRetainsResponseDeadlineAndExactSelectedRoute() {
        val paid = ProviderRequestConfig("https://openrouter.ai/api/v1", "credential", profileUid = "saved", providerType = "OPENROUTER", lockedModel = "selected")
        val failure = ProviderHttpFailure(429, 2_000, knownNotAccepted = true, observedAtMillis = 10_000)
        val decision = requireNotNull(providerLimitDecision(paid, "selected", false, failure))
        assertEquals(12_000L, decision.retryAt)
        assertEquals("OPENROUTER", decision.provider)
        assertEquals("saved", decision.profileUid)
        assertEquals("selected", decision.model)
        assertEquals(429, decision.httpStatus)
        assertTrue(decision.knownNotAccepted)
        assertNull(decision.authority)
        assertNull(providerLimitDecision(paid, "different-model", false, failure))
    }

    @Test fun credentialCooldownIsSharedAcrossProfilesWithoutPersistingCredentials() {
        val paid = ProviderRequestConfig("https://openrouter.ai/api/v1", "private-credential", profileUid = "one")
        val failure = ProviderHttpFailure(429, 2_000, knownNotAccepted = true, observedAtMillis = 10_000)
        val first = requireNotNull(providerLimitDecision(paid, "one-model", false, failure))
        val other = requireNotNull(providerLimitDecision(paid.copy(profileUid = "two"), "two-model", false, failure))
        assertEquals(first.scope, other.scope)
        assertFalse(first.scope.contains("private-credential"))
        assertFalse(first.scope.contains("openrouter.ai"))
        assertFalse(first.scope == providerLimitDecision(paid.copy(token = "different"), "one-model", false, failure)!!.scope)
    }

    @Test fun uncertainOrNonRateLimitFailuresCannotCreateAPaidProviderWait() {
        val paid = ProviderRequestConfig("https://openrouter.ai/api/v1", "credential", profileUid = "saved")
        assertNull(providerLimitDecision(paid, "selected", false, ProviderHttpFailure(429, 2_000)))
        assertNull(providerLimitDecision(paid, "selected", false, ProviderHttpFailure(503, 2_000, knownNotAccepted = true)))
        assertNull(providerLimitDecision(paid, "selected", false, IllegalStateException("HTTP 429 Retry-After 2")))
        assertFalse(providerLimitDecision(paid, "selected", true, ProviderHttpFailure(429, 2_000, knownNotAccepted = true))!!.knownNotAccepted)
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
