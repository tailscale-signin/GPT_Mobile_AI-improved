package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

class GitHubIndexPreparationTest {
    private val head = "a".repeat(40)
    private val tree = "b".repeat(40)
    private fun args(cursor: Int? = null) = buildJsonObject {
        put("ref", "feature/programmer")
        put("commit_sha", head)
        cursor?.let { put("index_cursor", it) }
    }

    @Test fun preparationIsPinnedBoundedAndResumesWithoutFetchingCachedBlobs() = runTest {
        var blobs = 0
        val state = GitHubRepositoryStateStore()
        val request: suspend (String) -> kotlinx.serialization.json.JsonElement = { path ->
            when {
                "/commits/" in path -> Json.parseToJsonElement("""{"sha":"$head","commit":{"tree":{"sha":"$tree"}}}""")
                "/git/trees/" in path -> buildJsonObject {
                    put("truncated", false)
                    put(
                        "tree",
                        buildJsonArray {
                            (1..50).forEach { n ->
                                add(
                                    buildJsonObject {
                                        put("path", "src/File$n.kt")
                                        put("sha", "%040x".format(n))
                                        put("size", 20)
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
                        put("encoding", "base64")
                        put("content", Base64.getEncoder().encodeToString("fun example() = 1".toByteArray()))
                    }
                }
                else -> error("Unexpected request $path")
            }
        }
        fun session(task: String) = GitHubRepositorySession("account", "connection", "workspace", task, state, request, { _, _ -> error("Indexing must never commit") })
        val first = session("initial").execute("build_index", "owner", "repo", args()).jsonObject
        assertEquals(head, first.getValue("commit_sha").jsonPrimitive.content)
        assertEquals(24, first.getValue("attempted_this_batch").jsonPrimitive.int)
        assertEquals(24, blobs)
        assertFalse(first.getValue("complete").jsonPrimitive.content.toBoolean())
        val resumed = session("after-restart").execute("build_index", "owner", "repo", args(first.getValue("next_index_cursor").jsonPrimitive.int)).jsonObject
        assertEquals(48, blobs)
        val final = session("after-restart").execute("build_index", "owner", "repo", args(resumed.getValue("next_index_cursor").jsonPrimitive.int)).jsonObject
        assertEquals(50, blobs)
        assertTrue(final.getValue("complete").jsonPrimitive.content.toBoolean())
        session("another-task").execute("build_index", "owner", "repo", args())
        assertEquals("Cached blobs must survive a new task without more physical fetches", 50, blobs)
    }

    @Test fun malformedTextStillConsumesThePhysicalFetchAllowance() = runTest {
        var blobs = 0
        val session = GitHubRepositorySession("account", "connection", "workspace", "binary", GitHubRepositoryStateStore(), { path ->
            when {
                "/commits/" in path -> Json.parseToJsonElement("""{"sha":"$head","commit":{"tree":{"sha":"$tree"}}}""")
                "/git/trees/" in path -> buildJsonObject {
                    put("truncated", false)
                    put(
                        "tree",
                        buildJsonArray {
                            (1..50).forEach { n ->
                                add(
                                    buildJsonObject {
                                        put("path", "src/File$n.kt")
                                        put("sha", "%040x".format(n))
                                        put("size", 4)
                                        put("type", "blob")
                                    }
                                )
                            }
                        }
                    )
                }
                else -> {
                    blobs++
                    buildJsonObject {
                        put("encoding", "base64")
                        put("content", Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2)))
                    }
                }
            }
        }, { _, _ -> error("Unexpected mutation") })
        val result = session.execute("build_index", "owner", "repo", args()).jsonObject
        assertEquals(24, blobs)
        assertEquals(24, result.getValue("skipped_this_batch").jsonPrimitive.int)
        assertFalse(result.getValue("complete").jsonPrimitive.content.toBoolean())
        assertTrue("A failed file is not cached as evidence", result.getValue("indexed_blobs").jsonPrimitive.int == 0)
    }
}
