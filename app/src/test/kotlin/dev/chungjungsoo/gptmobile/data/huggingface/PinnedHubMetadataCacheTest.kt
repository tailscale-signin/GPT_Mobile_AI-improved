package dev.chungjungsoo.gptmobile.data.huggingface

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedHubMetadataCacheTest {
    @Test fun `cache separates revision and credential scope and expires`() {
        var now = 0L
        val cache = PinnedHubMetadataCache(nowMs = { now })
        val metadata = buildJsonObject { put("private", false) }
        val sha = "a".repeat(40)
        cache.put("owner/repo", sha, "credential", metadata)
        assertEquals(metadata, cache.get("owner/repo", sha, "credential"))
        assertNull(cache.get("owner/repo", "b".repeat(40), "credential"))
        assertNull(cache.get("owner/repo", sha, "changed"))
        now = 300_000
        assertNull(cache.get("owner/repo", sha, "credential"))
    }

    @Test fun `private unpinned oversized and evicted metadata cannot be returned`() {
        val cache = PinnedHubMetadataCache(maximumBytes = 100, maximumEntries = 1)
        val sha = "a".repeat(40)
        val public = buildJsonObject { put("private", false) }
        cache.put("one", sha, null, buildJsonObject { put("private", true) })
        assertNull(cache.get("one", sha, null))
        cache.put("one", "main", null, public)
        assertNull(cache.get("one", "main", null))
        cache.put(
            "one",
            sha,
            null,
            buildJsonObject {
                put("private", false)
                put("large", "x".repeat(100))
            }
        )
        assertNull(cache.get("one", sha, null))
        cache.put("one", sha, null, public)
        cache.put("two", sha, null, public)
        assertNull(cache.get("one", sha, null))
        assertEquals(public, cache.get("two", sha, null))
    }
}
