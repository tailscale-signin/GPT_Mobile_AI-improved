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

    @Test fun repeatedBlobDeadlineDefersOneExactFileAndResumesLaterFilesWithoutClaimingCoverage() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val slow = AtomicInteger()
        val fast = AtomicInteger()
        var simulateSlowBlob = false
        fun content() = buildJsonObject {
            put("encoding", "base64")
            put("content", Base64.getEncoder().encodeToString("fun needle() = 1".toByteArray()))
        }
        fun session() = GitHubRepositorySession(
            "account",
            "connection",
            "chat",
            "task",
            store,
            { path ->
                when {
                    "/commits/" in path -> buildJsonObject {
                        put("sha", head)
                        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
                    }
                    "/git/trees/" in path -> buildJsonObject {
                        put("truncated", false)
                        put(
                            "tree",
                            JsonArray(
                                (1..2).map { index ->
                                    buildJsonObject {
                                        put("path", "src/File$index.kt")
                                        put("sha", "%040x".format(index))
                                        put("type", "blob")
                                        put("size", 16)
                                    }
                                }
                            )
                        )
                    }
                    path.endsWith("/git/blobs/${"%040x".format(1)}") -> {
                        if (simulateSlowBlob) {
                            slow.incrementAndGet()
                            kotlinx.coroutines.awaitCancellation()
                        } else {
                            content()
                        }
                    }
                    "/git/blobs/" in path -> {
                        fast.incrementAndGet()
                        content()
                    }
                    else -> error("Unexpected request $path")
                }
            },
            { _, _ -> error("Search must never mutate") },
            searchBlobTimeoutMs = 500
        )
        val warm = session()
        val warmFirst = warm.execute("search_repository", "owner", "repo", args("literal", 1)).jsonObject
        val warmLast = warm.execute("search_repository", "owner", "repo", warmFirst.getValue("continuation").jsonObject).jsonObject
        assertEquals(JsonPrimitive(true), warmLast["complete"])
        simulateSlowBlob = true
        val first = kotlinx.coroutines.withContext(GitHubDispatchAccounting(forceLiveReads = true, record = {})) {
            session().execute("search_repository", "owner", "repo", args("literal", 1)).jsonObject
        }
        assertEquals(JsonPrimitive(0), first["interval_end"])
        assertEquals(JsonPrimitive(1), first["no_progress_attempts"])
        assertEquals(JsonPrimitive(0), first["total_deferred_files"])
        // A new session retains the retry boundary and the pinned query identity.
        val restored = session()
        val second = kotlinx.coroutines.withContext(GitHubDispatchAccounting(forceLiveReads = true, record = {})) {
            restored.execute("search_repository", "owner", "repo", first.getValue("continuation").jsonObject).jsonObject
        }
        assertEquals(first["search_id"], second["search_id"])
        assertEquals(JsonPrimitive(1), second["interval_end"])
        assertEquals(JsonPrimitive(2), second["no_progress_attempts"])
        val deferred = second.getValue("deferred_files").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("src/File1.kt"), deferred["path"])
        assertEquals(JsonPrimitive("%040x".format(1)), deferred["sha"])
        assertEquals(JsonPrimitive("REPEATED_READ_DEADLINE"), deferred["reason"])
        val last = restored.execute("search_repository", "owner", "repo", second.getValue("continuation").jsonObject).jsonObject
        assertEquals("An earlier fully covered page cannot erase this chain's deferred prefix", JsonPrimitive(false), last["cache_hit"])
        assertEquals(JsonPrimitive("src/File2.kt"), last.getValue("items").jsonArray.single().jsonObject["path"])
        assertEquals(JsonPrimitive(true), last["traversal_complete"])
        assertEquals(JsonPrimitive(false), last["has_more"])
        assertEquals(JsonPrimitive(false), last["complete"])
        assertEquals(JsonPrimitive(false), last["matches_complete"])
        assertEquals(JsonPrimitive(1), last["total_skipped_files"])
        assertEquals(JsonPrimitive(1), last["total_deferred_files"])
        assertEquals(2, slow.get())
        assertEquals(1, fast.get())
    }

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
        val restarted = fixture.session.execute("search_repository", "owner", "repo", changed).jsonObject
        assertEquals(JsonPrimitive("SEARCH_PARAMETERS_CHANGED"), restarted.getValue("recovery").jsonObject["code"])
        assertEquals(JsonPrimitive(0), restarted["interval_start"])
        assertEquals(JsonPrimitive(0), restarted["returned"])
        val retained = fixture.session.execute("search_repository", "owner", "repo", args("paths")).jsonObject
        assertEquals(first["items"], retained["items"])
        assertEquals(JsonPrimitive(true), retained["cache_hit"])
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

    @Test fun unavailableSearchIdentityAndUnobservedBoundaryKeepTheirActualFailureBranch() = runBlocking {
        val fixture = fixture(105)
        suspend fun reject(arguments: JsonObject): GitHubReadValidation {
            try {
                fixture.session.execute("search_repository", "owner", "repo", arguments)
                error("An unissued continuation must be rejected")
            } catch (failure: GitHubReadValidation) {
                return failure
            }
        }
        val missing = reject(JsonObject(args("paths") + mapOf("search_id" to JsonPrimitive("unissued"), "index_cursor" to JsonPrimitive(1))))
        assertEquals("GITHUB_SEARCH_CURSOR_UNAVAILABLE", missing.code)
        assertEquals(JsonPrimitive("RETAINED_SEARCH_MISSING"), missing.details["search_cursor_state"])
        val first = fixture.session.execute("search_repository", "owner", "repo", args("paths")).jsonObject
        val continuation = first.getValue("continuation").jsonObject
        val boundary = reject(JsonObject(continuation + ("index_cursor" to JsonPrimitive(1))))
        assertEquals("GITHUB_SEARCH_CURSOR_UNAVAILABLE", boundary.code)
        assertEquals(JsonPrimitive("PAGE_BOUNDARY_UNOBSERVED"), boundary.details["search_cursor_state"])
        assertEquals(0, fixture.blobs.get())
    }
}
