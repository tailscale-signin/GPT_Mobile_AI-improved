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

    @Test fun connectivityWaitRequiresTypedFailureAndProofThatNoGenerationWasSent() {
        val failure = ProviderConnectivityPendingException(dnsRecovery = true, retryAfterMs = 8_000)
        val selected = config.copy(lockedModel = "model")
        val wait = requireNotNull(providerConnectivityWait(selected, "model", failure, false, false, now = 1_000))
        assertEquals("DNS_RESOLUTION_FAILED", wait.category)
        assertEquals("CONNECTIVITY_BACKOFF", wait.retrySource)
        assertEquals(9_000L, wait.retryAt)
        assertTrue(wait.knownNotAccepted)
        assertNull(providerConnectivityWait(selected, "model", failure, true, false))
        assertNull(providerConnectivityWait(selected, "model", failure, false, true))
        assertNull(providerConnectivityWait(selected, "other", failure, false, false))
        assertNull(providerConnectivityWait(selected.copy(apiUrl = "not-a-url"), "model", failure, false, false))
        assertNull(providerConnectivityWait(selected, "model", java.io.IOException("DNS resolution failed"), false, false))
    }

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

    @Test fun credentialCooldownIsSharedAcrossProfilesForTheSameModelOnlyWithoutPersistingCredentials() {
        val paid = ProviderRequestConfig(
            "https://openrouter.ai/api/v1",
            "private-credential",
            profileUid = "one",
            providerType = "OPENROUTER",
            selectedModel = "one-model"
        )
        val failure = ProviderHttpFailure(429, 2_000, knownNotAccepted = true, observedAtMillis = 10_000)
        val first = requireNotNull(providerLimitDecision(paid, "one-model", false, failure))
        val sameModelOtherProfile = requireNotNull(providerLimitDecision(paid.copy(profileUid = "two"), "one-model", false, failure))
        val unrelatedModel = requireNotNull(providerLimitDecision(paid.copy(profileUid = "two", selectedModel = "two-model"), "two-model", false, failure))
        assertEquals(first.scope, sameModelOtherProfile.scope)
        assertFalse(first.scope == unrelatedModel.scope)
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

    @Test fun aRefusedTypedLimitRetainsItsClosedReasonWithoutAuthorizingAWait() {
        val paid = ProviderRequestConfig("https://openrouter.ai/api/v1", "credential", profileUid = "saved", lockedModel = "selected")
        val safe = ProviderHttpFailure(429, 2_000, knownNotAccepted = true)
        val cases = listOf(
            Triple(paid.copy(profileUid = null), safe, "PROFILE_UNAVAILABLE"),
            Triple(paid.copy(lockedModel = "other"), safe, "ROUTE_MISMATCH"),
            Triple(paid, ProviderHttpFailure(429, null, knownNotAccepted = true), "RETRY_DEADLINE_UNAVAILABLE"),
            Triple(paid, ProviderHttpFailure(429, 2_000), "REJECTION_NOT_REPLAY_SAFE"),
            Triple(paid, ProviderHttpFailure(429, 2_000, waitRefusal = dev.chungjungsoo.gptmobile.data.agent.ProviderWaitSchedulingResult.NoWaitReason.REMOTE_JOB_PRESENT), "REMOTE_JOB_PRESENT")
        )
        for ((selected, failure, reason) in cases) {
            assertNull(providerLimitDecision(selected, "selected", false, failure))
            val details = providerLimitRefusalDetails(selected, "selected", false, java.io.IOException(failure))
            assertEquals(kotlinx.serialization.json.JsonPrimitive(reason), details["provider_wait_no_wait_reason"])
            assertFalse(details.toString().contains("credential"))
        }
        assertTrue(providerLimitRefusalDetails(paid, "selected", false, IllegalStateException("HTTP 429")).isEmpty())
    }

    @Test fun wrappedShortRejectionRetainsDeadlineAndDoesNotReplayReceivedOutput() {
        val paid = ProviderRequestConfig("https://openrouter.ai/api/v1", "credential", profileUid = "saved", providerType = "OPENROUTER", lockedModel = "selected")
        val rejection = ProviderHttpFailure(429, 4_013, knownNotAccepted = true, observedAtMillis = 10_000, retrySource = "RETRY_AFTER")
        val wrapped = java.util.concurrent.CompletionException(java.io.IOException("stream rejected", rejection))
        val waiting = requireNotNull(providerLimitDecision(paid, "selected", false, wrapped))
        assertEquals(14_013L, waiting.retryAt)
        assertEquals("RETRY_AFTER", waiting.retrySource)
        assertTrue(waiting.mayWait)
        assertFalse(requireNotNull(providerLimitDecision(paid, "selected", true, wrapped)).mayWait)
        assertNull(providerLimitDecision(paid, "different", false, wrapped))
        assertNull(providerLimitDecision(paid.copy(profileUid = null), "selected", false, wrapped))
    }

    @Test fun cancellationApplicationRefusalsAndUntypedMessagesCannotBecomeRateLimitWaits() {
        val paid = ProviderRequestConfig("https://openrouter.ai/api/v1", "credential", profileUid = "saved")
        val rejection = ProviderHttpFailure(429, 4_000, knownNotAccepted = true)
        val cancelled = kotlinx.coroutines.CancellationException("Stopped").apply { initCause(rejection) }
        assertNull(providerLimitDecision(paid, "selected", false, java.io.IOException(cancelled)))
        assertNull(providerLimitDecision(paid, "selected", false, IllegalStateException("recovery changed", rejection)))
        assertNull(providerLimitDecision(paid, "selected", false, java.io.IOException("HTTP 429 retry after 4")))
        var deeplyWrapped: Exception = rejection
        repeat(8) { deeplyWrapped = java.io.IOException(deeplyWrapped) }
        assertNull(providerLimitDecision(paid, "selected", false, deeplyWrapped))
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
