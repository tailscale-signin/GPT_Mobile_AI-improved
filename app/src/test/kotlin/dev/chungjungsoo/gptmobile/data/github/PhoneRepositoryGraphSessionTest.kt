package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRepositoryGraphSessionTest {
    private val head = "a".repeat(40)
    private val otherHead = "c".repeat(40)
    private val treeSha = "b".repeat(40)
    private val state = GitHubRepositoryStateStore()
    private var blobs = 0
    private var treeSize = 50
    private var truncated = false
    private var invalidSource = false

    private suspend fun request(path: String): JsonElement = when {
        "/commits/" in path -> buildJsonObject {
            put("sha", path.substringAfterLast('/'))
            put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", treeSha) }) })
        }
        "/git/trees/" in path -> buildJsonObject {
            put("truncated", truncated)
            put(
                "tree",
                buildJsonArray {
                    (1..treeSize).forEach { n ->
                        add(
                            buildJsonObject {
                                put("path", "src/File$n.kt")
                                put("sha", "%040x".format(n))
                                put("size", 40)
                                put("type", "blob")
                                put("mode", "100644")
                            }
                        )
                    }
                }
            )
        }
        "/git/blobs/" in path -> {
            blobs++
            buildJsonObject {
                put("sha", path.substringAfterLast('/'))
                put("encoding", "base64")
                put("content", Base64.getEncoder().encodeToString((if (invalidSource) "\u0000" else "package sample\nfun useful() = 1\n").toByteArray()))
            }
        }
        else -> error("Unexpected transport $path")
    }

    private fun session(task: String = "map", account: String = "account") = GitHubRepositorySession(
        account,
        "connection",
        "map-conversation",
        task,
        state,
        ::request,
        { _, _ -> error("Phone map must never write to GitHub") }
    )

    private fun arguments(action: String, cursor: String? = null, revision: String = head) = buildJsonObject {
        put("action", action)
        put("ref", "main")
        put("commit_sha", revision)
        cursor?.let { put("index_cursor", it) }
    }

    @Test fun initialMapShowsTheRealTreeWithoutDownloadingAnySource() = runTest {
        val reader = session()
        val status = reader.repositoryMap("owner", "repo", arguments("status"))
        assertEquals(head, status.getValue("graph").jsonObject.getValue("commit_sha").jsonPrimitive.content)
        assertTrue(status.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(0, blobs)
        val map = reader.repositoryMap("owner", "repo", arguments("architecture"))
        assertTrue((map["nodes"] as JsonArray).isNotEmpty())
        assertEquals(0, blobs)
        assertFalse(map.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
    }

    @Test fun phoneIndexResumesAfterRestartAndReusesImmutableSourceAcrossCommits() = runTest {
        val first = session().repositoryMap("owner", "repo", arguments("index"))
        assertEquals(24, blobs)
        assertTrue(first.getValue("has_more").jsonPrimitive.boolean)
        val observed = session("after-restart").repositoryMap("owner", "repo", arguments("status"))
        assertEquals(first["index_cursor"], observed["index_cursor"])
        val second = session("after-restart").repositoryMap("owner", "repo", arguments("index", observed.getValue("index_cursor").jsonPrimitive.content))
        assertEquals(48, blobs)
        val last = session("after-restart").repositoryMap("owner", "repo", arguments("index", second.getValue("index_cursor").jsonPrimitive.content))
        assertEquals(50, blobs)
        assertFalse(last.getValue("has_more").jsonPrimitive.boolean)
        assertTrue(last.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        val changed = session("new-commit").repositoryMap("owner", "repo", arguments("index", revision = otherHead))
        assertEquals("Unchanged blob shards must be reused for the next revision", 50, blobs)
        assertEquals(otherHead, changed.getValue("graph").jsonObject.getValue("commit_sha").jsonPrimitive.content)
    }

    @Test fun aContinuationCannotMoveToAnotherRevisionOrAccount() = runTest {
        val first = session().repositoryMap("owner", "repo", arguments("index"))
        val cursor = first.getValue("index_cursor").jsonPrimitive.content
        val anotherRevision = runCatching { session("new").repositoryMap("owner", "repo", arguments("index", cursor, otherHead)) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_CURSOR_STALE", (anotherRevision as GatewayGraphResultException).code)
        val anotherAccount = runCatching { session("new-account", "other-account").repositoryMap("owner", "repo", arguments("index", cursor)) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_CURSOR_STALE", (anotherAccount as GatewayGraphResultException).code)
        assertEquals(24, blobs)
    }

    @Test fun sourceCacheFromAnotherAccountCannotPopulateTheMap() = runTest {
        treeSize = 1
        session().repositoryMap("owner", "repo", arguments("index"))
        val status = session("other", "other-account").repositoryMap("owner", "repo", arguments("status"))
        assertEquals(0, status.getValue("graph").jsonObject.getValue("processed_files").jsonPrimitive.int)
        assertFalse(status.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
    }

    @Test fun completedTraversalOfATruncatedTreeNeverClaimsWholeRepositoryCoverage() = runTest {
        treeSize = 1
        truncated = true
        val result = session().repositoryMap("owner", "repo", arguments("index"))
        assertEquals(1, blobs)
        assertFalse(result.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        assertFalse(result.getValue("graph").jsonObject.getValue("complete").jsonPrimitive.boolean)
    }

    @Test fun unsupportedOperationsAndBackgroundJobsNeverDispatchSourceReads() = runTest {
        val unsupported = runCatching { session().repositoryMap("owner", "repo", arguments("note")) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_OPERATION_UNSUPPORTED", (unsupported as GatewayGraphResultException).code)
        val background = runCatching { session().repositoryMap("owner", "repo", JsonObject(arguments("index") + ("background" to JsonPrimitive(true)))) }.exceptionOrNull()
        assertTrue(background?.message.orEmpty().contains("bounded foreground"))
        assertEquals(0, blobs)
    }

    @Test fun anUnusableSourceCanBeRetriedAfterTraversalFinishes() = runTest {
        treeSize = 1
        invalidSource = true
        val failed = session().repositoryMap("owner", "repo", arguments("index"))
        assertFalse(failed.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(1, failed.getValue("graph").jsonObject.getValue("retryable_files").jsonPrimitive.int)
        assertFalse(failed.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        invalidSource = false
        val retried = session().repositoryMap("owner", "repo", JsonObject(arguments("index") + ("retry_failed" to JsonPrimitive(true))))
        assertTrue(retried.getValue("graph").jsonObject.getValue("coverage_complete").jsonPrimitive.boolean)
        assertEquals(0, retried.getValue("graph").jsonObject.getValue("retryable_files").jsonPrimitive.int)
    }
}
