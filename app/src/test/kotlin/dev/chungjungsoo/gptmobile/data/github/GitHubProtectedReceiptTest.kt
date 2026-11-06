package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProtectedReceiptTest {
    @Test fun upgradeProtectsThePreviousBuildsReceiptBeforeAnyCacheCleanup() = runBlocking {
        val directory = Files.createTempDirectory("github-receipt-upgrade").toFile()
        try {
            val args = buildJsonObject { put("tag_name", "v1") }
            val original = GitHubExecutionJournal(GitHubRepositoryStateStore(directory), "scope").run("publish_release", args) {
                buildJsonObject {
                    put("id", 73)
                    put("head_sha", "a".repeat(40))
                }
            }.jsonObject
            val id = original.getValue("execution").jsonObject.getValue("execution_id").jsonPrimitive.content
            val filename = GitHubRepositoryStateStore.digest("scope:execution:$id") + ".record"
            val protected = directory.resolve("execution-receipts").resolve(filename)
            val legacy = directory.resolve(filename)
            assertTrue(protected.renameTo(legacy))
            val restarted = GitHubRepositoryStateStore(directory)
            restarted.write("cache", "new-read", buildJsonObject { put("ok", true) })
            assertTrue("The old encrypted body is moved before cache pruning", protected.isFile && !legacy.exists())
            val recovered = GitHubExecutionJournal(restarted, "scope").run("publish_release", args) { error("Upgrade cannot replay a known write") }.jsonObject
            assertEquals(JsonPrimitive(73), recovered["id"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationAfterResponseStillCommitsTheKnownReceiptBeforeRecovery() = runBlocking {
        val state = GitHubRepositoryStateStore()
        val journal = GitHubExecutionJournal(state, "scope")
        val args = buildJsonObject { put("tag_name", "v1") }
        val accepted = buildJsonObject {
            put("id", 91)
            put("sha", "a".repeat(40))
        }
        val worker = launch {
            journal.run("publish_release", args) {
                currentCoroutineContext().cancel()
                accepted
            }
        }
        worker.join()
        val recovered = journal.run("publish_release", args) { error("Known accepted result must survive cancellation") }.jsonObject
        assertEquals(JsonPrimitive(91), recovered["id"])
        assertEquals(JsonPrimitive("COMPLETED"), recovered.getValue("execution").jsonObject["status"])
        assertEquals(JsonPrimitive(true), recovered.getValue("execution").jsonObject["replayed_receipt"])
    }

    @Test fun releaseDispatchReceiptSurvivesCachePressureAndRestartWithoutRepeatingTheWrite() = runBlocking {
        val directory = Files.createTempDirectory("github-critical-release").toFile()
        try {
            val arguments = buildJsonObject { put("tag_name", "v1") }
            val dispatch = buildJsonObject {
                put("workflow_run_id", 9_007_199_254_740_993L)
                put("source_sha", "a".repeat(40))
                put("workflow_id", 81)
                put("correlation_id", "accepted-dispatch")
            }
            var writes = 0
            val state = GitHubRepositoryStateStore(directory)
            GitHubExecutionJournal(state, "account:conversation").run("publish_release", arguments) {
                writes++
                buildJsonObject {
                    put("dispatch_receipt", dispatch)
                    put(
                        "release",
                        buildJsonObject {
                            put("id", 41)
                            put("tag_name", "v1")
                        }
                    )
                    put("body", "notes".repeat(40_000))
                }
            }
            val blob = buildJsonObject { put("content", "x".repeat(6 * 1024 * 1024)) }
            repeat(9) { state.write("cache", "blob:$it", blob) }
            val replay = GitHubExecutionJournal(GitHubRepositoryStateStore(directory), "account:conversation").run("publish_release", arguments) {
                writes++
                error("An accepted release must never be dispatched again to recover output")
            }.jsonObject
            assertEquals(1, writes)
            assertEquals(dispatch, replay["dispatch_receipt"])
            assertEquals(JsonPrimitive(41), replay.getValue("release").jsonObject["id"])
            assertEquals(JsonPrimitive(true), replay["receipt_bounded"])
            assertEquals(JsonPrimitive(true), replay.getValue("execution").jsonObject["replayed_receipt"])
            assertTrue(directory.walkTopDown().filter { it.isFile && it.extension == "record" }.sumOf { it.length() } <= GitHubRepositoryStateStore.MAX_DISK_BYTES)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unresolvedWriteReservesSpaceAndRejectsNewWritesBeforeDispatchWhileReadsContinue() = runBlocking {
        val directory = Files.createTempDirectory("github-critical-capacity").toFile()
        try {
            val capacity = GitHubRepositoryStateStore.MAX_PROTECTED_RECORD_BYTES.toLong() + 32 * 1024
            fun state() = GitHubRepositoryStateStore(directory, protectedCapacityBytes = capacity)
            val args = buildJsonObject { put("tag_name", "v1") }
            var dispatches = 0
            assertTrue(
                runCatching {
                    GitHubExecutionJournal(state(), "task").run("publish_release", args) {
                        dispatches++
                        throw IOException("Response lost after dispatch")
                    }
                }.isFailure
            )
            val restarted = state()
            val failure = runCatching {
                GitHubExecutionJournal(restarted, "task").run("publish_release", buildJsonObject { put("tag_name", "v2") }) {
                    dispatches++
                    JsonObject(emptyMap())
                }
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("GITHUB_RECEIPT_CAPACITY"))
            assertEquals(1, dispatches)
            val recovery = GitHubExecutionJournal(restarted, "task").run("publish_release", args) { error("Uncertain claims cannot replay") }.jsonObject
            assertEquals(JsonPrimitive("RECONCILE_REQUIRED"), recovery["status"])
            val observed = buildJsonObject { put("head_sha", "a".repeat(40)) }
            restarted.write("cache", "read", observed)
            assertEquals(observed, restarted.read("cache", "read"))
            val settled = GitHubExecutionJournal(restarted, "task").run("publish_release", args, reconcile = { buildJsonObject { put("id", 1) } }) { error("Reconciliation only reads") }.jsonObject
            assertEquals(JsonPrimitive(1), settled["id"])
            GitHubExecutionJournal(restarted, "task").run("publish_release", buildJsonObject { put("tag_name", "v2") }) {
                dispatches++
                buildJsonObject { put("id", 2) }
            }
            assertEquals("Unused reserved bytes become available only after a definitive receipt", 2, dispatches)
        } finally {
            directory.deleteRecursively()
        }
    }
}
