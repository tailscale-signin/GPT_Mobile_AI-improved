package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubInputRegressionTest {
    @Test
    fun issueSearchAddsRequiredDiscriminatorWithoutChangingExplicitPullRequests() {
        assertEquals("repo:owner/repo timeout is:issue", normalizeGitHubIssueQuery("repo:owner/repo timeout"))
        assertEquals("is:pr timeout", normalizeGitHubIssueQuery("is:pr timeout"))
        assertEquals("\"is:pr\" is:issue", normalizeGitHubIssueQuery("\"is:pr\""))
    }

    @Test
    fun fullRepositoryReferencesNormalizeAndConflictingOwnersFail() {
        assertEquals("owner" to "repo", normalizeGitHubRepository(null, "https://github.com/owner/repo.git"))
        assertEquals("owner" to "repo", normalizeGitHubRepository("owner", "owner/repo"))
        assertThrows(IllegalArgumentException::class.java) { normalizeGitHubRepository("other", "owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { normalizeGitHubRepository(null, "https://github.com.evil.org/owner/repo") }
    }

    @Test
    fun searchQuotaSurvivesCoreResponsesAndStopsOnlyUntilReset() {
        val manager = GitHubRateLimitManager()
        manager.record(headersOf("X-RateLimit-Resource" to listOf("search"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("500")))
        manager.record(headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("100")))
        assertThrows(IllegalStateException::class.java) { manager.requireAvailable("search", 499) }
        manager.requireAvailable("core", 499)
        manager.requireAvailable("search", 500)
    }
}
