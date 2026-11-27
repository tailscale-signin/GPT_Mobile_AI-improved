package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRepositoryTreeDiscoveryTest {
    private val root = "a".repeat(40)
    private fun sha(number: Int) = number.toString(16).padStart(40, '0')
    private fun entry(path: String, sha: String, directory: Boolean = false) = buildJsonObject {
        put("path", path)
        put("sha", sha)
        put("type", if (directory) "tree" else "blob")
        put("mode", if (directory) "040000" else "100644")
        if (!directory) put("size", 12)
    }
    private fun tree(sha: String, rows: List<JsonObject>, truncated: Boolean = false) = buildJsonObject {
        put("sha", sha)
        put("tree", JsonArray(rows))
        put("truncated", truncated)
    }

    @Test fun truncatedTreesContinueInBoundedChunksAcrossRestoredCheckpoints() = runTest {
        val initial = PhoneRepositoryTreeDiscovery.initial(tree(root, emptyList(), truncated = true), root)
        val reads = mutableListOf<String>()
        suspend fun read(value: String): JsonObject {
            reads += value
            return if (value == root) tree(root, (1..6).map { entry("module$it", sha(it), true) }) else tree(value, listOf(entry("Main.kt", sha(100))))
        }
        var saved = initial
        val first = PhoneRepositoryTreeDiscovery.advance(initial, ::read) { saved = it }
        assertEquals(4, reads.size)
        assertEquals(JsonPrimitive(true), first["truncated"])
        assertEquals(first, saved)
        val finished = PhoneRepositoryTreeDiscovery.advance(saved, ::read) { saved = it }
        assertEquals(7, reads.size)
        assertEquals(reads.size, reads.toSet().size)
        assertEquals(JsonPrimitive(false), finished["truncated"])
        assertEquals(6, finished.getValue("tree").jsonArray.count { it.jsonObject["type"] == JsonPrimitive("blob") })
        assertEquals(JsonPrimitive(true), initial["truncated"])
    }

    @Test fun incompleteDirectoryPreservesTheUsableSkeletonAndReportsPartialCoverage() = runTest {
        val skeleton = listOf(entry("known.kt", sha(1)))
        val initial = PhoneRepositoryTreeDiscovery.initial(tree(root, skeleton, true), root)
        var retained = initial
        val result = PhoneRepositoryTreeDiscovery.advance(initial, { tree(root, emptyList(), true) }) { retained = it }
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(JsonPrimitive(true), result["tree_discovery_blocked"])
        assertEquals(initial["tree"], retained["tree"])
    }

    @Test fun mismatchedDirectoryIdentityNeverReplacesThePriorCheckpoint() = runTest {
        val initial = PhoneRepositoryTreeDiscovery.initial(tree(root, emptyList(), true), root)
        var changed = false
        assertTrue(runCatching {
            PhoneRepositoryTreeDiscovery.advance(initial, { tree(sha(2), listOf(entry("foreign.kt", sha(1)))) }) { changed = true }
        }.isFailure)
        assertFalse(changed)
    }

    @Test fun invalidChildPathsNeverEscapeTheRetainedRepository() = runTest {
        val initial = PhoneRepositoryTreeDiscovery.initial(tree(root, emptyList(), true), root)
        val result = PhoneRepositoryTreeDiscovery.advance(initial, { tree(root, listOf(entry("../other.kt", sha(1)))) }) {}
        assertEquals(JsonPrimitive(true), result["tree_discovery_blocked"])
        assertTrue(result.getValue("tree").jsonArray.isEmpty())
    }

    @Test fun completeTreeIsReusedWithoutAnyNetworkRead() = runTest {
        val initial = PhoneRepositoryTreeDiscovery.initial(tree(root, listOf(entry("Main.kt", sha(1)))), root)
        val result = PhoneRepositoryTreeDiscovery.advance(initial, { error("Unchanged complete tree must be reused") }) { error("Unchanged tree must not be rewritten") }
        assertEquals(initial, result)
        assertEquals("Main.kt", result.getValue("tree").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test fun explicitRetryResumesBlockedDiscoveryWithoutReplacingThePreviousSkeleton() = runTest {
        val initial = PhoneRepositoryTreeDiscovery.initial(tree(root, emptyList(), true), root)
        val blocked = PhoneRepositoryTreeDiscovery.advance(initial, { tree(root, emptyList(), true) }) {}
        assertEquals(blocked, PhoneRepositoryTreeDiscovery.advance(blocked, { error("A blocked discovery requires explicit retry") }) {})
        val recovered = PhoneRepositoryTreeDiscovery.advance(blocked, { tree(root, listOf(entry("Main.kt", sha(1)))) }, retryFailed = true) {}
        assertEquals(JsonPrimitive(false), recovered["truncated"])
        assertEquals("Main.kt", recovered.getValue("tree").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), blocked["tree_discovery_blocked"])
    }
}
