package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubResponseCacheTest {
    private val root = "/repos/owner/project"
    private val sha = "a".repeat(40)

    @Test fun immutableReadsRequireTheExactGitObjectEndpointOrPinnedContents() {
        for (path in listOf("/commits/$sha?page=2", "/git/commits/$sha", "/git/trees/$sha?recursive=1", "/git/blobs/$sha", "/contents?ref=$sha", "/contents/src/check-runs.kt?ref=$sha")) {
            assertTrue(path, GitHubResponseCache.isImmutableRequest(root + path))
        }
        for (path in listOf("/commits/$sha/check-runs", "/commits/$sha/pulls", "/commits/$sha/status", "/commits/${sha}a", "/issues?ref=$sha", "/contents/docs/commits/$sha", "/contents/file?ref=main&ref=$sha")) {
            assertFalse(path, GitHubResponseCache.isImmutableRequest(root + path))
        }
    }

    @Test fun lateNotModifiedCannotRestoreAnInvalidatedOrReplacedBody() {
        val cache = GitHubResponseCache()
        cache.put("key", "old", JsonPrimitive("old"))
        val old = requireNotNull(cache.get("key"))
        cache.revalidated("key", old)
        assertSame(old.value, cache.get("key")?.value)
        cache.invalidateMutable()
        cache.revalidated("key", old)
        assertNull(cache.get("key"))
        cache.put("key", "new", JsonPrimitive("new"))
        cache.revalidated("key", old)
        assertEquals("new", cache.get("key")?.etag)
    }

    @Test fun uncacheableReplacementEvictsPreviouslyEligibleBody() {
        val cache = GitHubResponseCache(maxCharacters = 10)
        for ((etag, replacement) in listOf("new" to "too long to retain", null to "small")) {
            cache.put("key", "old", JsonPrimitive("old"))
            cache.put("key", etag, JsonPrimitive(replacement))
            assertNull(cache.get("key"))
        }
    }
}
