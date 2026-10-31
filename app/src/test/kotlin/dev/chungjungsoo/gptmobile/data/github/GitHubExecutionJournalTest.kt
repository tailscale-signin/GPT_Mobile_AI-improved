package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubExecutionJournalTest {
    private val arguments = buildJsonObject {
        put("stage_id", "patch")
        put("expected_head_sha", "a".repeat(40))
    }
    private val receipt = buildJsonObject { put("sha", "b".repeat(40)) }

    @Test fun concurrentSessionsShareOneClaimAndCanonicalReceipt() = runTest {
        val store = GitHubRepositoryStateStore()
        val first = GitHubExecutionJournal(store, "scope")
        val second = GitHubExecutionJournal(store, "scope")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val owner = async {
            first.run("commit", arguments) {
                writes++
                entered.complete(Unit)
                release.await()
                receipt
            }
        }
        entered.await()
        val pending = second.run("commit", arguments) { error("Must not repeat an in-flight write") }.jsonObject
        assertEquals("EXECUTION_IN_PROGRESS", pending["status"]!!.jsonPrimitive.content)
        release.complete(Unit)
        val applied = owner.await().jsonObject
        val reordered = JsonObject(arguments.entries.reversed().associate { it.toPair() })
        val replay = second.run("commit", reordered) { error("Must use saved receipt") }.jsonObject
        assertEquals(applied["sha"], replay["sha"])
        assertEquals(applied["execution"]!!.jsonObject["execution_id"], replay["execution"]!!.jsonObject["execution_id"])
        assertTrue(replay["execution"]!!.jsonObject["replayed_receipt"]!!.jsonPrimitive.booleanOrNull == true)
        assertEquals(1, writes)
    }

    @Test fun completedReceiptsSurviveRestartWithoutRetainingSourceArguments() = runTest {
        val directory = Files.createTempDirectory("github-journal").toFile()
        try {
            val args = JsonObject(arguments + ("source" to JsonPrimitive("private-source-marker")))
            GitHubExecutionJournal(GitHubRepositoryStateStore(directory), "scope").run("commit", args) { receipt }
            val restored = GitHubExecutionJournal(GitHubRepositoryStateStore(directory), "scope")
            val result = restored.run("commit", args) { error("Restart cannot repeat publication") }
            assertEquals(receipt["sha"], result.jsonObject["sha"])
            assertEquals(1, restored.context()["executions"]!!.jsonArray.size)
            assertFalse(directory.listFiles()!!.any { it.readText().contains("private-source-marker") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun lostResponseRetainsUncertaintyAcrossSessions() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        val journal = GitHubExecutionJournal(store, "scope")
        assertTrue(
            runCatching {
                journal.run("commit", arguments) {
                    writes++
                    throw IOException("Lost acknowledgement")
                }
            }.isFailure
        )
        val recovered = GitHubExecutionJournal(store, "scope").run("commit", arguments) {
            writes++
            receipt
        }.jsonObject
        assertEquals("RECONCILE_REQUIRED", recovered["status"]!!.jsonPrimitive.content)
        assertEquals(1, writes)
        assertFalse(journal.context().toString().contains("Lost acknowledgement"))
    }

    @Test fun observedRejectionAllowsANewExplicitAttemptWithoutAutomaticRetry() = runTest {
        val journal = GitHubExecutionJournal(GitHubRepositoryStateStore(), "scope")
        var writes = 0
        assertTrue(
            runCatching {
                journal.run("commit", arguments) {
                    writes++
                    throw GitHubRequestRejected(403, "Denied")
                }
            }.isFailure
        )
        assertEquals(1, writes)
        val result = journal.run("commit", arguments) {
            writes++
            receipt
        }.jsonObject
        assertEquals(2, writes)
        assertEquals("2", result["execution"]!!.jsonObject["attempt"]!!.jsonPrimitive.content)
    }

    @Test fun cancellationDoesNotReleaseAnUncertainPublicationClaim() = runTest {
        val journal = GitHubExecutionJournal(GitHubRepositoryStateStore(), "scope")
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val execution = async {
            journal.run("commit", arguments) {
                entered.complete(Unit)
                blocked.await()
                receipt
            }
        }
        entered.await()
        execution.cancelAndJoin()
        val result = journal.run("commit", arguments) { error("Cancelled write must be reconciled") }.jsonObject
        assertEquals("RECONCILE_REQUIRED", result["status"]!!.jsonPrimitive.content)
    }

    @Test fun retainedReceiptsAndContextAreBoundedAndScopeIsolated() = runTest {
        val store = GitHubRepositoryStateStore()
        val first = GitHubExecutionJournal(store, "scope")
        first.run("commit", arguments) { JsonObject(receipt + ("source" to JsonPrimitive("x".repeat(200_000)))) }
        val replay = first.run("commit", arguments) { error("Must use bounded receipt") }.jsonObject
        assertTrue(replay["receipt_bounded"]!!.jsonPrimitive.booleanOrNull == true)
        assertFalse("source" in replay)
        repeat(40) { index -> first.run("commit", buildJsonObject { put("index", index) }) { receipt } }
        assertEquals(32, first.context()["executions"]!!.jsonArray.size)
        var independent = false
        GitHubExecutionJournal(store, "other-scope").run("commit", arguments) {
            independent = true
            receipt
        }
        assertTrue(independent)
    }
}
