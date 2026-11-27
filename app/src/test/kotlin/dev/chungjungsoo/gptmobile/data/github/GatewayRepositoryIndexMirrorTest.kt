package dev.chungjungsoo.gptmobile.data.github

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRepositoryIndexMirrorTest {
    private val firstSha = "a".repeat(40)
    private val nextSha = "b".repeat(40)

    private fun compressed(value: String): String {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(value.encodeToByteArray()) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun row(path: String, sha: String = firstSha): JsonObject {
        val payload = buildJsonObject {
            put("path", path)
            put("blob_sha", sha)
            put("source_level_only", true)
            put("runtime_verified", false)
        }.toString()
        return buildJsonObject {
            put("path", path)
            put("blob_sha", sha)
            put("digest", GitHubRepositoryStateStore.digest(payload))
            put("encoding", "zlib-base64")
            put("payload", compressed(payload))
        }
    }

    private fun generation(rows: List<JsonObject>) = GitHubRepositoryStateStore.digest(
        rows.sortedBy { it.getValue("path").jsonPrimitive.content }.joinToString("") {
            "${it.getValue("path").jsonPrimitive.content}\u0000${it.getValue("blob_sha").jsonPrimitive.content}\u0000${it.getValue("digest").jsonPrimitive.content}\n"
        }
    )

    private fun page(all: List<JsonObject>, subset: List<JsonObject> = all, revision: String = firstSha, offset: Int = 0, next: Int? = null) = buildJsonObject {
        put("schema_version", 1)
        put("access_scope", "c".repeat(32))
        put("status", if (next == null) "SYNCHRONIZED" else "UPDATING")
        put("graph", buildJsonObject {
            put("repository", "owner/repo")
            put("branch", "main")
            put("commit_sha", revision)
            put("coverage_complete", true)
        })
        put("observed_branch_head", revision)
        put("generation", generation(all))
        put("transfer_id", "d".repeat(32))
        put("delta", false)
        put("record_count", all.size)
        put("change_count", all.size)
        put("offset", offset)
        put("next_offset", next?.let(::JsonPrimitive) ?: JsonNull)
        put("records", JsonArray(subset))
    }

    @Test fun interruptedTransferResumesAfterRestartAndKeepsThePreviousMap() = runTest {
        val directory = Files.createTempDirectory("repository-index-mirror").toFile()
        try {
            val original = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(directory), "account")
            assertTrue(original.accept(page(listOf(row("old.kt"))), firstSha))
            val updated = listOf(row("a.kt"), row("b.kt"))
            assertFalse(original.accept(page(updated, updated.take(1), nextSha, next = 1), nextSha))
            assertEquals(JsonPrimitive(firstSha), original.snapshot()?.get("commit_sha"))
            val restored = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(directory), "account")
            assertEquals(JsonPrimitive(1), restored.request(nextSha)["sync_offset"])
            assertTrue(restored.accept(page(updated, updated.drop(1), nextSha, offset = 1), nextSha))
            assertEquals(JsonPrimitive(nextSha), restored.snapshot()?.get("commit_sha"))
            assertEquals(setOf("a.kt", "b.kt"), restored.snapshot()?.get("entries")?.jsonObject?.keys)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun corruptRecordCannotReplaceAValidMap() = runTest {
        val mirror = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(), "account")
        mirror.accept(page(listOf(row("old.kt"))), firstSha)
        val good = row("new.kt")
        val corrupt = JsonObject(good + ("payload" to JsonPrimitive(compressed("corrupt"))))
        assertTrue(runCatching { mirror.accept(page(listOf(good), listOf(corrupt), nextSha), nextSha) }.isFailure)
        assertEquals(JsonPrimitive(firstSha), mirror.snapshot()?.get("commit_sha"))
    }

    @Test fun corruptManifestCannotReplaceAValidMap() = runTest {
        val mirror = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(), "account")
        mirror.accept(page(listOf(row("old.kt"))), firstSha)
        val bad = JsonObject(page(listOf(row("new.kt")), revision = nextSha) + ("generation" to JsonPrimitive("0".repeat(64))))
        assertTrue(runCatching { mirror.accept(bad, nextSha) }.isFailure)
        assertEquals(JsonPrimitive(firstSha), mirror.snapshot()?.get("commit_sha"))
    }

    @Test fun accountChangeOrOutOfOrderPageCannotCompleteAPartialTransfer() = runTest {
        val mirror = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(), "account")
        val rows = listOf(row("a.kt"), row("b.kt"))
        mirror.accept(page(rows, rows.take(1), next = 1), firstSha)
        val next = page(rows, rows.drop(1), offset = 1)
        assertTrue(runCatching { mirror.accept(JsonObject(next + ("access_scope" to JsonPrimitive("f".repeat(32)))), firstSha) }.isFailure)
        assertTrue(runCatching { mirror.accept(JsonObject(next + ("offset" to JsonPrimitive(2))), firstSha) }.isFailure)
        assertEquals(null, mirror.snapshot())
        assertTrue(mirror.accept(next, firstSha))
    }

    @Test fun compressionBombAndMissingDeltaBaseAreRejected() = runTest {
        val mirror = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(), "account")
        val good = row("a.kt")
        val bomb = JsonObject(good + ("payload" to JsonPrimitive(compressed("x".repeat(32_769)))))
        assertTrue(runCatching { mirror.accept(page(listOf(good), listOf(bomb)), firstSha) }.isFailure)
        val missingBase = JsonObject(page(listOf(good)) + mapOf("delta" to JsonPrimitive(true), "base_generation" to JsonPrimitive("0".repeat(64))))
        assertTrue(runCatching { mirror.accept(missingBase, firstSha) }.isFailure)
        assertEquals(null, mirror.snapshot())
    }

    @Test fun authenticatedDeltaReusesUnchangedRowsAndDeletesOnlyNamedPaths() = runTest {
        val mirror = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(), "account")
        val kept = row("keep.kt")
        val previous = listOf(kept, row("remove.kt"))
        mirror.accept(page(previous), firstSha)
        val added = row("new.kt", nextSha)
        val changed = listOf(
            added,
            buildJsonObject {
                put("path", "remove.kt")
                put("deleted", true)
            }
        )
        val delta = JsonObject(page(listOf(kept, added), changed, nextSha) + mapOf("delta" to JsonPrimitive(true), "base_generation" to JsonPrimitive(generation(previous))))
        assertTrue(mirror.accept(delta, nextSha))
        assertEquals(setOf("keep.kt", "new.kt"), mirror.snapshot()?.get("entries")?.jsonObject?.keys)
    }

    @Test fun evictedDiskShardCannotUseAnInMemoryHitAsAValidDeltaBase() = runTest {
        val directory = Files.createTempDirectory("repository-index-evicted").toFile()
        try {
            val store = GitHubRepositoryStateStore(directory)
            val mirror = GatewayRepositoryIndexMirror(store, "account")
            val source = row("a.kt")
            mirror.accept(page(listOf(source)), firstSha)
            val key = "phone-graph:gateway-sync:record:${source.getValue("digest").jsonPrimitive.content}"
            val file = java.io.File(java.io.File(directory, "source-index"), GitHubRepositoryStateStore.digest("account:$key") + ".record")
            assertTrue(file.delete())
            assertFalse("base_generation" in mirror.request(nextSha))
            assertEquals(JsonPrimitive(true), mirror.snapshot()?.get("source_cache_evicted"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun permissionChangeDuringTransferPreservesPreviousPublishedSnapshot() = runTest {
        val mirror = GatewayRepositoryIndexMirror(GitHubRepositoryStateStore(), "account")
        mirror.accept(page(listOf(row("old.kt"))), firstSha)
        var calls = 0
        assertTrue(runCatching {
            mirror.accept(page(listOf(row("new.kt")), revision = nextSha), nextSha) { ++calls < 3 }
        }.isFailure)
        assertEquals(JsonPrimitive(firstSha), mirror.snapshot()?.get("commit_sha"))
    }
}
