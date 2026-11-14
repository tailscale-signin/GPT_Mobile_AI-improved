package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRateLimitResponseEvidenceTest {
    @Test fun primaryResponseUsesOnlyItsObservedQuotaReset() {
        val evidence = requireNotNull(
            GitHubRateLimitResponseEvidence.fromResponse(
                403,
                "GITHUB_PRIMARY_RATE_LIMITED",
                headersOf(
                    "X-RateLimit-Resource" to listOf("core"),
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("130")
                ),
                100_125
            )
        )
        assertEquals(JsonPrimitive("GITHUB_PRIMARY_RATE_LIMITED"), evidence["code"])
        assertEquals(JsonPrimitive(403), evidence["http_status"])
        assertEquals(JsonPrimitive("CORE"), evidence["github_cooldown_resource"])
        assertEquals(JsonPrimitive("RESOURCE_RESET"), evidence["github_cooldown_basis"])
        assertEquals(JsonPrimitive(29_875L), evidence["github_cooldown_delay_ms"])
        assertEquals(JsonPrimitive(130_000L), evidence["github_cooldown_deadline_ms"])
        assertEquals(JsonPrimitive(100_125L), evidence["github_cooldown_observed_at_ms"])
        assertEquals(JsonPrimitive(0L), evidence["github_cooldown_remaining_requests"])
        assertEquals(JsonPrimitive(29_875L), evidence["retry_after_ms"])
        assertNoAggregateFacts(evidence)
    }

    @Test fun retryAfterHttpDateUsesTheCapturedResponseClock() {
        val evidence = response(
            headersOf(
                "Retry-After" to listOf("Thu, 1 Jan 1970 00:00:10 GMT"),
                "X-RateLimit-Resource" to listOf("search"),
                "X-RateLimit-Remaining" to listOf("40")
            ),
            observedAtMillis = 1250
        )
        assertEquals(JsonPrimitive("SEARCH"), evidence["github_cooldown_resource"])
        assertEquals(JsonPrimitive("RESOURCE_RETRY_AFTER"), evidence["github_cooldown_basis"])
        assertEquals(JsonPrimitive(8750L), evidence["github_cooldown_delay_ms"])
        assertEquals(JsonPrimitive(10_000L), evidence["github_cooldown_deadline_ms"])
        assertEquals(JsonPrimitive(1250L), evidence["github_cooldown_observed_at_ms"])
        assertEquals(JsonPrimitive(40L), evidence["github_cooldown_remaining_requests"])
    }

    @Test fun onlyPositiveObservedContributorsSelectTheCooldownBasis() {
        data class Case(val reset: String, val retryAfter: String, val basis: String, val delay: Long)
        for (case in listOf(
            Case("130", "20", "RESOURCE_RESET_AND_RETRY_AFTER", 30_000),
            Case("120", "30", "RESOURCE_RESET_AND_RETRY_AFTER", 30_000),
            Case("100", "30", "RESOURCE_RETRY_AFTER", 30_000),
            Case("130", "0", "RESOURCE_RESET", 30_000),
            Case("130", "Thu, 1 Jan 1970 00:00:10 GMT", "RESOURCE_RESET", 30_000)
        )) {
            val evidence = response(
                headersOf(
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf(case.reset),
                    "Retry-After" to listOf(case.retryAfter)
                )
            )
            assertEquals(case.toString(), JsonPrimitive(case.basis), evidence["github_cooldown_basis"])
            assertEquals(case.toString(), JsonPrimitive(case.delay), evidence["github_cooldown_delay_ms"])
            assertEquals(JsonPrimitive(130_000L), evidence["github_cooldown_deadline_ms"])
        }
    }

    @Test fun missingMalformedOrExpiredDeadlinesRemainUnavailable() {
        for (headers in listOf(
            headersOf(),
            headersOf("Retry-After", "0"),
            headersOf("Retry-After", "-1"),
            headersOf("Retry-After", "private invalid deadline"),
            headersOf("Retry-After", "Thu, 1 Jan 1970 00:00:10 GMT"),
            headersOf("X-RateLimit-Remaining" to listOf("0")),
            headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("100")),
            headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("-1")),
            headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("private"))
        )) {
            val evidence = response(headers)
            assertNoDeadline(evidence)
            assertEquals(JsonPrimitive(100_000L), evidence["github_cooldown_observed_at_ms"])
            assertFalse(evidence.toString().contains("private"))
        }
        val invalidClock = response(headersOf("Retry-After", "30"), observedAtMillis = -1)
        assertNoDeadline(invalidClock)
        assertFalse("github_cooldown_observed_at_ms" in invalidClock)
    }

    @Test fun nonzeroOrUnknownQuotaCannotTurnAResetIntoACooldown() {
        for (remaining in listOf(null, "1", "16000000", "16000001", "-1", "private", Long.MAX_VALUE.toString())) {
            val headers = Headers.build {
                append("X-RateLimit-Reset", "130")
                remaining?.let { append("X-RateLimit-Remaining", it) }
            }
            val evidence = response(headers)
            assertNoDeadline(evidence)
            if (remaining in listOf("1", "16000000")) {
                assertEquals(JsonPrimitive(requireNotNull(remaining).toLong()), evidence["github_cooldown_remaining_requests"])
            } else {
                assertFalse("github_cooldown_remaining_requests" in evidence)
            }
        }
    }

    @Test fun largeDeadlinesSaturateWithoutAnEarlyRetryOrDayClamp() {
        val reset = response(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(Long.MAX_VALUE.toString())), 1000)
        assertEquals(JsonPrimitive(Long.MAX_VALUE - 1000), reset["github_cooldown_delay_ms"])
        assertEquals(JsonPrimitive(Long.MAX_VALUE), reset["github_cooldown_deadline_ms"])
        assertFalse("retry_after_ms" in reset)
        val retry = response(headersOf("Retry-After", Long.MAX_VALUE.toString()), 100_000)
        assertEquals(JsonPrimitive(Long.MAX_VALUE / 1000 * 1000), retry["github_cooldown_delay_ms"])
        assertEquals(JsonPrimitive(Long.MAX_VALUE), retry["github_cooldown_deadline_ms"])
        assertFalse("retry_after_ms" in retry)
        val twoDays = response(headersOf("Retry-After", "172800"))
        assertEquals(JsonPrimitive(172_800_000L), twoDays["github_cooldown_delay_ms"])
        assertFalse("retry_after_ms" in twoDays)
    }

    @Test fun responseEvidenceUsesClosedResourcesWithoutPrivateHeaders() {
        for ((resource, expected) in listOf("core" to "CORE", "SEARCH" to "SEARCH", "graphql" to "GRAPHQL", "private-account" to "OTHER")) {
            val evidence = response(
                headersOf(
                    "X-RateLimit-Resource" to listOf(resource),
                    "X-OAuth-Scopes" to listOf("private-token-scope"),
                    "X-GitHub-Request-Id" to listOf("private-request"),
                    "X-GitHub-SSO" to listOf("private-account-and-endpoint")
                )
            )
            assertEquals(JsonPrimitive(expected), evidence["github_cooldown_resource"])
            assertEquals(setOf("code", "http_status", "github_cooldown_resource", "github_cooldown_observed_at_ms"), evidence.keys)
            assertFalse(evidence.toString().contains("private"))
            assertNoAggregateFacts(evidence)
        }
        for (headers in listOf(headersOf(), headersOf("X-RateLimit-Resource", " "))) {
            assertFalse("github_cooldown_resource" in response(headers))
        }
    }

    @Test fun unrelatedStatusOrClassificationDoesNotCreateRateEvidence() {
        val headers = headersOf("Retry-After", "30")
        for (status in listOf(0, 200, 304, 401, 404, 500)) {
            assertNull(GitHubRateLimitResponseEvidence.fromResponse(status, "GITHUB_PRIMARY_RATE_LIMITED", headers, 100_000))
        }
        for (code in listOf("GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "private GITHUB_PRIMARY_RATE_LIMITED", "github_primary_rate_limited")) {
            assertNull(GitHubRateLimitResponseEvidence.fromResponse(403, code, headers, 100_000))
        }
    }

    private fun response(headers: Headers, observedAtMillis: Long = 100_000): JsonObject =
        requireNotNull(GitHubRateLimitResponseEvidence.fromResponse(429, "GITHUB_SECONDARY_RATE_LIMITED", headers, observedAtMillis))

    private fun assertNoDeadline(evidence: JsonObject) {
        for (key in listOf("github_cooldown_basis", "github_cooldown_delay_ms", "github_cooldown_deadline_ms", "retry_after_ms")) {
            assertFalse("$key must stay unavailable", key in evidence)
        }
    }

    private fun assertNoAggregateFacts(evidence: JsonObject) {
        for (key in listOf("dispatched", "dispatch_state", "attempt_dispatch_state", "remote_acceptance", "request_replay_allowed", "partial_output_preserved")) {
            assertFalse("$key is not established by these response headers", key in evidence)
        }
        assertTrue(evidence.toString().length < 1024)
    }
}
