package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubComparisonPageTest {
    @Test fun requestedSizesProduceExactNonoverlappingWindowsAndContinuations() {
        for (size in listOf(1, 30, 50, 100)) {
            val slices = mutableListOf<Int>()
            var page = 1
            while (true) {
                val window = GitHubComparisonPage.window(
                    buildJsonObject {
                        put("page", page)
                        put("per_page", size)
                    },
                    327
                )
                slices += window.from until window.until
                assertEquals(size, window.size)
                assertTrue(window.until - window.from <= size)
                assertEquals((327 + size - 1) / size, window.totalPages)
                if (!window.hasMore) break
                page++
            }
            assertEquals((0 until 327).toList(), slices)
        }
    }

    @Test fun omittedSizeAndLegacyEvidenceKeepOneHundredButExplicitNewSizeIsRetained() {
        assertEquals(100, GitHubComparisonPage.window(JsonObject(emptyMap()), 327).size)
        assertEquals(100, GitHubComparisonPage.observedSize(JsonObject(emptyMap())))
        assertEquals(50, GitHubComparisonPage.observedSize(buildJsonObject { put("effective_per_page", 50) }))
        assertEquals(null, GitHubComparisonPage.observedSize(buildJsonObject { put("effective_per_page", 0) }))
        assertEquals(null, GitHubComparisonPage.observedSize(buildJsonObject { put("effective_per_page", "50") }))
    }

    @Test fun emptyAndExcessPagesAreBoundedAndInvalidArgumentsAreRejected() {
        val empty = GitHubComparisonPage.window(JsonObject(emptyMap()), 0)
        assertEquals(0, empty.from)
        assertEquals(0, empty.until)
        assertEquals(1, empty.totalPages)
        assertFalse(empty.hasMore)
        val beyond = GitHubComparisonPage.window(
            buildJsonObject {
                put("page", 1000)
                put("per_page", 100)
            },
            327
        )
        assertEquals(327, beyond.from)
        assertEquals(327, beyond.until)
        assertFalse(beyond.hasMore)
        for (invalid in listOf(JsonPrimitive(0), JsonPrimitive(101), JsonPrimitive("50"))) {
            assertTrue(runCatching { GitHubComparisonPage.window(buildJsonObject { put("per_page", invalid) }, 327) }.isFailure)
        }
    }
}
