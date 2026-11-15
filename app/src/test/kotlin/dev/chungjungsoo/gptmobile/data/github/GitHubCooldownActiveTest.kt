package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubCooldownActiveTest {
    @Test fun actualLocalCooldownHasAnActionableNonReplayableClassification() {
        val error = resourceFailure()
        val classified = ErrorClassification.classify(error)
        assertEquals("GITHUB_COOLDOWN_ACTIVE", classified.errorCode)
        assertEquals(ErrorCategory.RATE_LIMIT_EXCEEDED, classified.category)
        assertEquals(20_000L, classified.suggestedBackoffMs)
        assertFalse(classified.isRetryable)
        assertEquals(error.message, classified.userMessage)
        assertEquals("GITHUB_COOLDOWN_ACTIVE", ErrorClassification.classify(IllegalStateException("outer", error)).errorCode)
    }

    @Test fun cooldownProjectionPreservesMeasurementsWithoutInventingWholeRunOutcomes() {
        val error = resourceFailure()
        for (partial in listOf(false, true)) {
            val detail = providerExceptionDetails(error, partial, nowMillis = 999_000)
            assertNotNull(detail)
            assertEquals("GITHUB_COOLDOWN_ACTIVE", detail!!.code)
            assertEquals(false, detail.retryable)
            val fields = requireNotNull(detail.failureDetails)
            assertEquals(JsonPrimitive("repository_admission"), fields["failed_stage"])
            assertEquals(JsonPrimitive("RESOURCE_RESET"), fields["github_cooldown_basis"])
            assertEquals(JsonPrimitive(20_000L), fields["github_cooldown_delay_ms"])
            assertEquals(JsonPrimitive(130_000L), fields["github_cooldown_deadline_ms"])
            assertEquals(JsonPrimitive(20_000L), fields["retry_after_ms"])
            assertEquals(JsonPrimitive(false), fields["retryable"])
            assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
            assertEquals(JsonPrimitive("RETRY_AFTER_BACKOFF"), fields["recovery_action"])
            for (key in listOf("dispatched", "dispatch_state", "attempt_dispatch_state", "remote_acceptance", "partial_output_preserved", "http_status", "originating_http_status")) {
                assertFalse("Unexpected inferred $key", fields.containsKey(key))
            }
        }
    }

    @Test fun cooldownBeyondThePublicShortRetryWindowIsNotClampedIntoAnEarlierRetry() {
        val limits = GitHubRateLimitManager()
        limits.cooldown(172_800, 1000)
        val error = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 1) }
        val fields = requireNotNull(providerExceptionDetails(error, false)?.failureDetails)
        assertEquals(JsonPrimitive(172_800_000L), fields["github_cooldown_delay_ms"])
        assertFalse(fields.containsKey("retry_after_ms"))
        assertFalse(ErrorClassification.classify(error).isRetryable)
    }

    @Test fun cancellationAndEarlierUnknownOutcomesKeepTheirPrecedence() {
        val error = resourceFailure()
        val cancellation = CancellationException("cancelled").apply { initCause(error) }
        assertEquals(ErrorCategory.CLIENT_CANCELLED, ErrorClassification.classify(cancellation).category)
        val unknown = IllegalStateException("GATEWAY_OUTCOME_UNKNOWN", error)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", ErrorClassification.classify(unknown).errorCode)
        assertFalse(ErrorClassification.classify(unknown).isRetryable)
        val changed = IllegalStateException("PROVIDER_WAIT_CHECKPOINT_CHANGED", error)
        assertEquals(ErrorCategory.POLICY_STALE, ErrorClassification.classify(changed).category)
    }

    @Test fun aPlainMessageCannotSupplyTypedCooldownMeasurements() {
        val spoofed = IllegalStateException("GITHUB_COOLDOWN_ACTIVE: arbitrary remote text")
        assertNotEquals("GITHUB_COOLDOWN_ACTIVE", ErrorClassification.classify(spoofed).errorCode)
        assertFalse(providerExceptionDetails(spoofed, false)?.failureDetails?.containsKey("github_cooldown_basis") == true)
    }

    @Test fun mutationAdmissionWrapperPreservesOnlyItsActualTypedCooldownCause() {
        val cooldown = resourceFailure()
        val wrapped = GitHubRequestRejected(0, "Request admission failed before dispatch").apply { initCause(cooldown) }
        for (partial in listOf(false, true)) {
            val detail = requireNotNull(providerExceptionDetails(wrapped, partial))
            assertEquals("GITHUB_COOLDOWN_ACTIVE", detail.code)
            val fields = requireNotNull(detail.failureDetails)
            assertEquals(JsonPrimitive(130_000L), fields["github_cooldown_deadline_ms"])
            assertFalse(fields.containsKey("http_status"))
            assertFalse(fields.containsKey("dispatched"))
            assertFalse(fields.containsKey("remote_acceptance"))
            assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
        }
        assertEquals("GITHUB_COOLDOWN_ACTIVE", ErrorClassification.classify(wrapped).errorCode)
        val withoutCause = GitHubRequestRejected(0, cooldown.message.orEmpty())
        assertFalse(providerExceptionDetails(withoutCause, false)?.failureDetails?.containsKey("github_cooldown_basis") == true)
    }

    private fun resourceFailure(): GitHubCooldownActive {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("130")), nowMillis = 100_000)
        return assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 110) }
    }
}
