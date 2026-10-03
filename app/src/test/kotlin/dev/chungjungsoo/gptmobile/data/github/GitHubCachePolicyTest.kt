package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCachePolicyTest {
    @Test fun `cache is bounded by characters and evicts least recently used entries`() {
        val cache = GitHubResponseCache(maxEntries = 2, maxCharacters = 32)
        cache.put("a", "a", JsonPrimitive("first"))
        cache.put("b", "b", JsonPrimitive("second"))
        assertNotNull(cache.get("a"))
        cache.put("c", "c", JsonPrimitive("third"))
        assertNull(cache.get("b"))
        cache.putDecodedBlob("huge", "x".repeat(33))
        assertNull(cache.getDecodedBlob("huge"))
        cache.clear()
        assertNull(cache.get("a"))
    }

    @Test fun `retry after expires without another successful network request`() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("Retry-After", "2"))
        val now = limits.snapshot().observedAtMillis / 1000
        assertTrue(limits.shouldBackOff(now))
        assertFalse(limits.shouldBackOff(now + 3))
        assertEquals(0L, limits.retryDelayMillis(now + 3))
    }
}
