package dev.chungjungsoo.gptmobile.data.github

import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

class PhoneRepositoryProgressBudgetTest {
    private val head = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val reads = ConcurrentHashMap<String, AtomicInteger>()
    private var slow = true
    private var slowRevision = false
    private var latencyMs = 0L
    private var fileCount = 4
    private val slowEntered = CompletableDeferred<Unit>()

    private suspend fun request(path: String): JsonElement = when {
        "/commits/" in path -> buildJsonObject {
            if (slowRevision) awaitCancellation()
            put("sha", head)
            put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
        }
        "/git/trees/" in path -> buildJsonObject {
            put("truncated", false)
            put(
                "tree",
                JsonArray(
                    (1..fileCount).map { index ->
                        buildJsonObject {
                            put("path", "src/File%02d.kt".format(index))
                            put("sha", "%040x".format(index))
                            put("type", "blob")
                            put("mode", "100644")
                            put("size", 25)
                        }
                    }
                )
            )
        }
        "/git/blobs/" in path -> {
            val sha = path.substringAfterLast('/')
            reads.getOrPut(sha) { AtomicInteger() }.incrementAndGet()
            if (slow && sha == "%040x".format(2)) {
                slowEntered.complete(Unit)
                awaitCancellation()
            }
            if (latencyMs > 0) delay(latencyMs)
            buildJsonObject {
                put("sha", sha)
                put("encoding", "base64")
                put("content", Base64.getEncoder().encodeToString("fun needle() = 1\n".toByteArray()))
            }
        }
        else -> error("Unexpected request $path")
    }

    private fun session(store: GitHubRepositoryStateStore, operationMs: Long = 5000) = GitHubRepositorySession(
        "account", "connection", "chat", "task", store, ::request,
        { _, _ -> error("Read progress must never dispatch a mutation") },
        readOperationTimeoutMs = operationMs,
        indexBlobTimeoutMs = 500
    )

    private fun args(action: String) = buildJsonObject {
        put("action", action)
        put("commit_sha", head)
        put("ref", "main")
    }

    @Test fun resolvingThePinnedRevisionConsumesTheSameReadOperationBudget() = runBlocking {
        slowRevision = true
        val reader = session(GitHubRepositoryStateStore(), operationMs = 100)
        val arguments = JsonObject(args("search_repository") + mapOf("query" to JsonPrimitive("needle"), "mode" to JsonPrimitive("literal")))
        val failure = runCatching { reader.execute("search_repository", "owner", "repo", arguments) }.exceptionOrNull() as GitHubReadValidation
        assertEquals("READ_DEADLINE", failure.code)
        assertEquals(JsonPrimitive("resolve_revision"), failure.details["failed_stage"])
        assertTrue(reads.isEmpty())
    }

    @Test fun slowFileIsExplicitlyDeferredAndLaterFilesRemainReachableAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("phone-index-progress").toFile()
        try {
            val first = session(GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", args("index"))
            assertEquals(JsonPrimitive(1), first["prepared_through"])
            assertEquals(JsonPrimitive(true), first["has_more"])
            assertEquals(JsonPrimitive(true), first["read_deadline_reached"])
            val second = session(GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", args("index"))
            assertEquals(JsonPrimitive(2), second["prepared_through"])
            assertEquals(JsonPrimitive("src/File02.kt"), second.getValue("deferred_files").jsonArray.single().jsonObject["path"])
            val reader = session(GitHubRepositoryStateStore(directory))
            val last = reader.repositoryMap("owner", "repo", args("index"))
            assertEquals(JsonPrimitive(4), last["prepared_through"])
            assertEquals(JsonPrimitive(false), last["coverage_complete"])
            assertEquals(JsonPrimitive(1), last["phone_retryable_files"])
            assertEquals(1, reads.getValue("%040x".format(1)).get())
            assertEquals(1, reads.getValue("%040x".format(4)).get())
            slow = false
            val repaired = reader.repositoryMap("owner", "repo", JsonObject(args("index") + ("retry_failed" to JsonPrimitive(true))))
            assertEquals(JsonPrimitive(true), repaired["coverage_complete"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationKeepsOnlyTheCompletedFileFrontierDurable() = runBlocking {
        val directory = Files.createTempDirectory("phone-index-cancel").toFile()
        try {
            val running = async { session(GitHubRepositoryStateStore(directory)).repositoryMap("owner", "repo", args("index")) }
            withTimeout(5000) { slowEntered.await() }
            running.cancelAndJoin()
            val reader = session(GitHubRepositoryStateStore(directory))
            val status = reader.repositoryMap("owner", "repo", args("status"))
            assertEquals(JsonPrimitive(1), status["prepared_through"])
            assertEquals(JsonPrimitive(false), status["coverage_complete"])
            slow = false
            val resumed = reader.repositoryMap("owner", "repo", args("index"))
            assertEquals(JsonPrimitive(true), resumed["coverage_complete"])
            assertEquals(1, reads.getValue("%040x".format(1)).get())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun searchYieldsOneWholeOperationPageAndResumesWithoutSkippingAnInFlightFile() = runBlocking {
        slow = false
        latencyMs = 120
        fileCount = 30
        val store = GitHubRepositoryStateStore()
        session(store).repositoryMap("owner", "repo", args("status"))
        val reader = session(store, operationMs = 500)
        var next = JsonObject(args("search_repository") + mapOf("query" to JsonPrimitive("needle"), "mode" to JsonPrimitive("literal"), "max_new_blobs" to JsonPrimitive(30)))
        val found = mutableListOf<String>()
        val first = reader.execute("search_repository", "owner", "repo", next).jsonObject
        assertEquals(JsonPrimitive("time_budget"), first["stop_reason"])
        assertTrue(first.getValue("scanned_files").jsonPrimitive.int in 1 until fileCount)
        assertFalse(first.getValue("complete").jsonPrimitive.content.toBoolean())
        found += first.getValue("items").jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        next = first.getValue("continuation").jsonObject
        latencyMs = 0
        do {
            val page = reader.execute("search_repository", "owner", "repo", next).jsonObject
            found += page.getValue("items").jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
            next = page["continuation"] as? JsonObject ?: break
        } while (true)
        assertEquals((1..fileCount).map { "src/File%02d.kt".format(it) }, found)
        assertEquals(found.size, found.distinct().size)
    }
}
