package dev.chungjungsoo.gptmobile.data.github

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPinnedSavedViewStoreTest {
    @Test fun rereadSubstitutesOnlyOriginalCoverageAndRejectsChangedContent() = runBlocking {
        val state = GitHubRepositoryStateStore()
        val store = GitHubSavedViewStore(state, "connection")
        val id = UUID.randomUUID().toString()
        val cursor = "$id:0:2048"
        val request = buildJsonObject {
            put("owner", "o")
            put("repo", "r")
            put("commit_sha", "a".repeat(40))
            put("ranges", JsonArray(listOf(buildJsonObject { put("path", "A.kt") })))
        }
        val identity = buildJsonObject { put("total_lines", 1) }
        val range = buildJsonObject {
            put("start_line", 1)
            put("end_line", 1)
            put("range_complete", true)
        }
        val first = GitHubSourceReadCoverage.merge(null, identity, "evidence", listOf(range))
        val reread = GitHubSourceReadCoverage.merge(first, identity, "evidence", listOf(range))
        assertEquals(JsonPrimitive(false), first["already_read"])
        assertEquals(JsonPrimitive(true), reread["already_read"])
        fun snapshot(text: String, coverage: JsonArray): JsonObject {
            val raw = buildJsonObject {
                put("content", text)
                put("source_coverage", coverage)
            }
            return buildJsonObject {
                put("operation", "github_read")
                put("scope", "scope")
                put("model_name", "github_read")
                put("issued_cursors", JsonArray(listOf(JsonPrimitive(cursor))))
                put("original_error", false)
                put("page_bytes", 2048)
                put("source_sha256", GitHubRepositoryStateStore.digest(raw.toString()))
                put("fragments", JsonArray(listOf(raw)))
                put("captured_source_coverage", coverage)
                put(
                    "source_recovery",
                    buildJsonObject {
                        put("tool", "github_read")
                        put("arguments", request)
                    }
                )
            }
        }
        val saved = snapshot("original bytes", JsonArray(listOf(first)))
        store.save(id, saved)
        state.remove("connection", "saved-view-body:$id")
        val current = JsonArray(listOf(reread))
        assertFalse(store.restorePinnedSource(cursor, request, snapshot("changed", current)) { previous, _ -> snapshot("changed", requireNotNull(previous)) })
        assertTrue(store.restorePinnedSource(cursor, request, snapshot("original bytes", current)) { previous, _ -> snapshot("original bytes", requireNotNull(previous)) })
        assertEquals(saved["fragments"], store.loadWithDiagnostics(id)["fragments"])
    }

    @Test fun repairAfterRestartChecksExactSourceLayoutScopeAndGrant() = runBlocking {
        val directory = Files.createTempDirectory("verified-source").toFile()
        try {
            val id = UUID.randomUUID().toString()
            val cursor = "$id:0:2048"
            val request = buildJsonObject {
                put("owner", "a")
                put("repo", "repo")
                put("commit_sha", "a".repeat(40))
                put("ranges", JsonArray(listOf(buildJsonObject { put("path", "A.kt") })))
            }
            val first = buildJsonObject {
                put("scope", "A")
                put("model_name", "github_read")
                put("operation", "github_read")
                put("page_bytes", 2048)
                put("context", JsonObject(request - "commit_sha"))
                put("source_call_id", "original")
                put("source_sha256", "f".repeat(64))
                put("issued_cursors", JsonArray(listOf(JsonPrimitive(cursor))))
                put("original_error", false)
                put("fragments", JsonArray(listOf(buildJsonObject { put("value", "source") })))
                put(
                    "source_recovery",
                    buildJsonObject {
                        put("tool", "github_read")
                        put("arguments", request)
                    }
                )
            }
            val state = GitHubRepositoryStateStore(directory)
            GitHubSavedViewStore(state, "connection").save(id, first)
            state.remove("connection", "saved-view-body:$id")
            val restarted = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "connection")
            val recipe = first.getValue("source_recovery") as JsonObject
            val reordered = JsonObject(recipe + ("arguments" to JsonObject(request.entries.reversed().associate { it.key to it.value })))
            assertEquals(recipe, reordered)
            assertFalse(recipe.toString() == reordered.toString())
            val fresh = JsonObject(first + mapOf("source_call_id" to JsonPrimitive("new-read"), "context" to request, "source_recovery" to reordered))
            assertFalse(restarted.restorePinnedSource("$id:100:2048", request, fresh))
            assertFalse(restarted.restorePinnedSource(cursor, request, JsonObject(fresh + ("source_sha256" to JsonPrimitive("1".repeat(64))))))
            assertFalse(restarted.restorePinnedSource(cursor, request, JsonObject(fresh + ("page_bytes" to JsonPrimitive(4096)))))
            assertFalse(restarted.restorePinnedSource(cursor, request, JsonObject(fresh + ("scope" to JsonPrimitive("other")))))
            assertTrue(restarted.restorePinnedSource(cursor, request, fresh))
            val restored = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "connection").loadWithDiagnostics(id)
            assertEquals(first.getValue("source_call_id"), restored["source_call_id"])
            assertEquals(first.getValue("issued_cursors"), restored["issued_cursors"])
            assertEquals(first.getValue("fragments"), restored["fragments"])
            assertEquals(first.getValue("context"), restored["context"])
        } finally {
            directory.deleteRecursively()
        }
    }
}
