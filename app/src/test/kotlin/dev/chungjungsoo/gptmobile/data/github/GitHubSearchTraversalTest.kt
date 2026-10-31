package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchTraversalTest {
    private val head = "a".repeat(40)
    private val tree = "b".repeat(40)

    private class Fixture(val session: GitHubRepositorySession, val blobs: AtomicInteger)

    private fun fixture(count: Int, source: String = "fun needle() = 1", truncated: Boolean = false): Fixture {
        val blobs = AtomicInteger()
        val session = GitHubRepositorySession("account", "connection", "chat", "task", GitHubRepositoryStateStore(), { path ->
            when {
                "/commits/" in path -> buildJsonObject {
                    put("sha", head)
                    put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
                }
                "/git/trees/" in path -> buildJsonObject {
                    put("truncated", truncated)
                    put(
                        "tree",
                        JsonArray(
                            (1..count).map { index ->
                                buildJsonObject {
                                    put("path", "src/File%03d.kt".format(index))
                                    put("sha", "%040x".format(index))
                                    put("type", "blob")
                                    put("mode", "100644")
                                    put("size", source.toByteArray().size)
                                }
                            }
                        )
                    )
                }
                "/git/blobs/" in path -> {
                    blobs.incrementAndGet()
                    buildJsonObject {
                        put("encoding", "base64")
                        put("content", Base64.getEncoder().encodeToString(source.toByteArray()))
                    }
                }
                else -> error("Unexpected request $path")
            }
        }, { _, _ -> error("Search must never mutate") })
        return Fixture(session, blobs)
    }

    private fun args(mode: String, maxBlobs: Int = 2) = buildJsonObject {
        put("ref", head)
        put("commit_sha", head)
        put("query", if (mode == "paths") "*" else "needle")
        put("mode", mode)
        put("max_new_blobs", maxBlobs)
    }

    @Test fun pathPagesTraverseOnceWithoutDownloadingBlobs() = runBlocking {
        val fixture = fixture(105)
        val first = fixture.session.execute("search_repository", "owner", "repo", args("paths")).jsonObject
        assertEquals(100, first.getValue("returned").jsonPrimitive.int)
        val continuation = first.getValue("continuation").jsonObject
        val last = fixture.session.execute("search_repository", "owner", "repo", continuation).jsonObject
        assertEquals(5, last.getValue("returned").jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), last["matches_complete"])
        val paths = (first.getValue("items").jsonArray + last.getValue("items").jsonArray).map { it.jsonObject["path"] }
        assertEquals(105, paths.distinct().size)
        assertEquals(0, fixture.blobs.get())
        val changed = JsonObject(continuation + ("query" to JsonPrimitive("different")))
        assertTrue(runCatching { fixture.session.execute("search_repository", "owner", "repo", changed) }.isFailure)
    }

    @Test fun contentPagesPreserveFetchBudgetCoverageAndCache() = runBlocking {
        val fixture = fixture(5)
        var next = args("literal")
        var total = 0
        var pages = 0
        while (true) {
            val page = fixture.session.execute("search_repository", "owner", "repo", next).jsonObject
            assertTrue(page.getValue("new_blobs").jsonPrimitive.int <= 2)
            total += page.getValue("returned").jsonPrimitive.int
            pages++
            assertTrue(pages <= 3)
            if (page["has_more"] == JsonPrimitive(false)) {
                assertEquals(JsonPrimitive(true), page["matches_complete"])
                break
            }
            next = page.getValue("continuation").jsonObject
        }
        assertEquals(5, total)
        assertEquals(5, fixture.blobs.get())
        val cached = fixture.session.execute("search_repository", "owner", "repo", args("literal")).jsonObject
        assertEquals(JsonPrimitive(true), cached["cache_hit"])
        assertEquals(JsonPrimitive(0), cached["new_blobs"])
        assertEquals(5, fixture.blobs.get())
    }

    @Test fun truncatedMatchesAndTreesNeverClaimExhaustiveSearch() = runBlocking {
        val fixture = fixture(1, "needle\n".repeat(20))
        val page = fixture.session.execute("search_repository", "owner", "repo", args("literal")).jsonObject
        assertEquals(10, page.getValue("items").jsonArray.single().jsonObject.getValue("matches").jsonArray.size)
        assertEquals(JsonPrimitive(false), page["matches_complete"])
        assertEquals(JsonPrimitive(true), page["complete"])
        val partial = fixture(1, truncated = true).session.execute("search_repository", "owner", "repo", args("paths")).jsonObject
        assertEquals(JsonPrimitive(false), partial["complete"])
        assertEquals(JsonPrimitive(false), partial["matches_complete"])
        assertFalse(partial.containsKey("continuation"))
    }
}
