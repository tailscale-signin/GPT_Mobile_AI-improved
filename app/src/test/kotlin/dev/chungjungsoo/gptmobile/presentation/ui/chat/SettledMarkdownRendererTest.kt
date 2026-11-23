package dev.chungjungsoo.gptmobile.presentation.ui.chat

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettledMarkdownRendererTest {
    @Test fun settledRevisitStartsWithTheSamePreparedSyntaxAndReferenceDefinitions() = runBlocking {
        val cache = SettledMarkdownRendererCache()
        val content = "# Saved answer\n\n[Evidence][Receipt]\n\n| Check | Result |\n|---|---|\n| UTF-8 | ✓ |\n\n```kotlin\nval answer = 42\n```\n\n[Receipt]: https://example.com/pinned"
        val first = requireNotNull(cache.prepare(content, cacheable = true))
        val revisit = requireNotNull(cache.prepare(content, cacheable = true))
        assertSame(first, revisit)
        val ready = revisit.state.value as State.Success
        assertEquals(content, ready.content)
        assertEquals("https://example.com/pinned", ready.referenceLinkHandler.find("[RECEIPT]"))
        assertEquals(1, revisit.links.value.size)
        assertSame(ready, revisit.parse())
        assertNotSame(first, cache.prepare("$content\nChanged", cacheable = true))
    }

    @Test fun protectedOrStreamingPreparationNeverReusesOrPopulatesTheSharedCache() {
        val cache = SettledMarkdownRendererCache()
        val content = "## Exact body\nPrivate values stay composition-scoped."
        val retained = cache.prepare(content, cacheable = true)
        val privateOne = cache.prepare(content, cacheable = false)
        val privateTwo = cache.prepare(content, cacheable = false)
        assertNotSame(retained, privateOne)
        assertNotSame(privateOne, privateTwo)
        assertEquals(1, cache.retainedEntries())
        cache.clear()
        cache.prepare(content, cacheable = false)
        assertEquals(0, cache.retainedEntries())
        assertEquals(0, cache.retainedBytes())
    }

    @Test fun temporaryRoomTransitionFencesLookupAndInFlightPublication() {
        var allowed = true
        val cache = SettledMarkdownRendererCache(retentionAllowed = { allowed })
        val original = cache.prepare("same settled body", cacheable = true)
        allowed = false
        val private = cache.prepare("same settled body", cacheable = true)
        assertNotSame(original, private)
        assertEquals(0, cache.retainedEntries())
        allowed = true
        var checks = 0
        cache.prepare("public parse interrupted by private entry", cacheable = true) {
            if (++checks == 2) {
                allowed = false
                cache.clear()
                allowed = true
            }
        }
        assertEquals(0, cache.retainedEntries())
        assertNotSame(original, cache.prepare("same settled body", cacheable = true))
    }

    @Test fun boundedEvictionAndMemoryClearAlsoFencePreparationAlreadyInFlight() {
        val cache = SettledMarkdownRendererCache(maximumBytes = 4096, maximumEntries = 2)
        val first = cache.prepare("first", cacheable = true)
        cache.prepare("second", cacheable = true)
        cache.prepare("third", cacheable = true)
        assertTrue(cache.retainedBytes() <= 4096)
        assertTrue(cache.retainedEntries() <= 2)
        assertNotSame(first, cache.prepare("first", cacheable = true))
        var checks = 0
        val prepared = cache.prepare("clear during background preparation", cacheable = true) {
            checks++
            if (checks == 2) cache.clear()
        }
        assertTrue(prepared?.state?.value is State.Success)
        assertEquals(0, cache.retainedEntries())
        cache.prepare("large ".repeat(500), cacheable = true)
        assertEquals("An over-budget syntax tree may render but must remain uncached", 0, cache.retainedEntries())
    }
}
