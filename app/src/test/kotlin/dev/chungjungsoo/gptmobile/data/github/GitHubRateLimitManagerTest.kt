package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRateLimitManagerTest {
    @Test fun roundedDelayTieKeepsTheLaterAbsoluteResetDeadline() {
        val limits = GitHubRateLimitManager()
        limits.record(
            headersOf(
                "X-RateLimit-Resource" to listOf("core"),
                "X-RateLimit-Remaining" to listOf("0"),
                "X-RateLimit-Reset" to listOf("130"),
                "Retry-After" to listOf("29")
            ),
            status = 403,
            nowMillis = 100_125
        )
        val blocked = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 100) }
        assertEquals(30_000L, blocked.delayMillis)
        assertEquals(130_000L, blocked.retryNotBeforeMillis)
        assertEquals(GitHubCooldownActive.Basis.RESOURCE_RESET_AND_RETRY_AFTER, blocked.basis)
        assertEquals(30_000L, limits.retryDelayMillis(100))
        val beforeReset = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 129) }
        assertEquals(130_000L, beforeReset.retryNotBeforeMillis)
        limits.requireAvailable("core", 130)
    }

    @Test fun overlappingCooldownsKeepTheLaterRequestDeadline() {
        for ((retryAfter, expectedDelay, basis) in listOf(
            Triple("10", 30_000L, GitHubCooldownActive.Basis.RESOURCE_RESET_AND_RETRY_AFTER),
            Triple("60", 60_000L, GitHubCooldownActive.Basis.SECONDARY_DEADLINE)
        )) {
            val limits = GitHubRateLimitManager()
            limits.record(
                headersOf(
                    "X-RateLimit-Resource" to listOf("core"),
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("130"),
                    "Retry-After" to listOf(retryAfter)
                ),
                status = 403,
                nowMillis = 100_000
            )
            val blocked = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 100) }
            assertEquals(expectedDelay, blocked.delayMillis)
            assertEquals(100_000L + expectedDelay, blocked.retryNotBeforeMillis)
            assertEquals(basis, blocked.basis)
            assertEquals(expectedDelay, limits.retryDelayMillis(100))
            assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 100 + expectedDelay / 1000 - 1) }
            limits.requireAvailable("core", 100 + expectedDelay / 1000)
        }
    }

    @Test fun resourceRejectionRetainsTheSelectedSnapshotInsteadOfTheLatestResource() {
        val limits = GitHubRateLimitManager()
        limits.record(
            headersOf(
                "X-RateLimit-Resource" to listOf("core"),
                "X-RateLimit-Remaining" to listOf("0"),
                "X-RateLimit-Reset" to listOf("130")
            ),
            nowMillis = 100_000
        )
        limits.record(headersOf("X-RateLimit-Resource" to listOf("search"), "X-RateLimit-Remaining" to listOf("20")), nowMillis = 109_000)
        val error = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 110) }
        assertEquals(GitHubCooldownActive.Resource.CORE, error.resource)
        assertEquals(GitHubCooldownActive.Basis.RESOURCE_RESET, error.basis)
        assertEquals(20_000L, error.delayMillis)
        assertEquals(130_000L, error.retryNotBeforeMillis)
        assertEquals(100_000L, error.observedAtMillis)
        assertEquals(0L, error.remainingRequests)
        limits.requireAvailable("search", 110)
        limits.requireAvailable("graphql", 110)
        limits.requireAvailable("core", 130)
        assertEquals(20_000L, error.delayMillis)
    }

    @Test fun secondaryRejectionDoesNotBorrowAnUnrelatedResourceObservation() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("5000")), nowMillis = 100_000)
        limits.cooldown(30, 100_000)
        val error = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("search", 110) }
        assertEquals(GitHubCooldownActive.Resource.SEARCH, error.resource)
        assertEquals(GitHubCooldownActive.Basis.SECONDARY_DEADLINE, error.basis)
        assertEquals(20_000L, error.delayMillis)
        assertEquals(130_000L, error.retryNotBeforeMillis)
        assertNull(error.observedAtMillis)
        assertNull(error.remainingRequests)
        assertFalse(error.failureFields().containsKey("github_cooldown_observed_at_ms"))
        assertFalse(error.failureFields().containsKey("github_cooldown_remaining_requests"))
        limits.requireAvailable("search", 130)
    }

    @Test fun retainedCooldownEvidenceDoesNotChangeWhenANewerResponseArrives() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("130")), nowMillis = 100_000)
        val error = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 110) }
        val retained = error.failureFields()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("100")), nowMillis = 111_000)
        limits.requireAvailable("core", 111)
        assertEquals(retained, error.failureFields())
        assertEquals(0L, error.remainingRequests)
    }

    @Test fun unknownResourceNamesNeverEnterTheTypedMessageOrEvidence() {
        val limits = GitHubRateLimitManager()
        limits.cooldown(30, 100_000)
        val privateResource = "private-account-and-request"
        val error = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable(privateResource, 110) }
        assertEquals(GitHubCooldownActive.Resource.OTHER, error.resource)
        assertFalse(error.message.orEmpty().contains(privateResource))
        assertFalse(error.failureFields().toString().contains(privateResource))
    }

    @Test fun largeResetRetainsTheExistingGuardAndOverflowSafeDeadline() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(Long.MAX_VALUE.toString())), nowMillis = 1000)
        val error = assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 1) }
        assertTrue(error.delayMillis > 86_400_000L)
        assertEquals(Long.MAX_VALUE, error.retryNotBeforeMillis)
        assertEquals(GitHubCooldownActive.Basis.RESOURCE_RESET, error.basis)
        assertTrue(limits.shouldBackOff(1))
    }

    @Test fun cooldownLongerThanOneDayCannotExpireEarlyOrOverflow() {
        val limits = GitHubRateLimitManager()
        limits.cooldown(172_800, 1000)
        assertEquals(86_400_000L, limits.retryDelayMillis(86_401))
        assertTrue(limits.shouldBackOff(86_401))
        assertThrows(IllegalStateException::class.java) { limits.requireAvailable(nowEpochSeconds = 86_401) }
        assertEquals(0L, limits.retryDelayMillis(172_801))
        limits.cooldown(Long.MAX_VALUE, 10_000)
        assertTrue(limits.retryDelayMillis(10) > 0)
        assertTrue(limits.shouldBackOff(10))
    }

    @Test fun datesUseTheSameClockForParsingAndObservation() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("Retry-After", "Thu, 1 Jan 1970 00:00:10 GMT"), 429, 1000)
        assertEquals(9000L, limits.retryDelayMillis(1))
        assertEquals(1000L, limits.snapshot().observedAtMillis)
        assertEquals(0L, limits.retryDelayMillis(10))
        assertFalse(limits.shouldBackOff(10))
    }

    @Test fun quotaResetWithUntrustedLargeSecondsDoesNotBecomeAnImmediateRetry() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(Long.MAX_VALUE.toString())), nowMillis = 1000)
        assertTrue(limits.retryDelayMillis(1) > 0)
        assertTrue(limits.shouldBackOff(1))
    }

    @Test fun invalidRetryAfterUsesTheBounded429Default() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("Retry-After", "-5"), 429, 1000)
        assertEquals(60_000L, limits.retryDelayMillis(1))
        assertEquals(0L, limits.retryDelayMillis(61))
    }
}
