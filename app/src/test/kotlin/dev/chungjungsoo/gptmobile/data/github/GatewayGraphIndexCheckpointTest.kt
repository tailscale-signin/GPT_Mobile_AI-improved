package dev.chungjungsoo.gptmobile.data.github

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayGraphIndexCheckpointTest {
    @Test fun anotherClientsCompletedIndexCannotResolveAnIdentifiedBackgroundRequest() = runTest {
        val checkpoint = GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(), "selected-host-revision")
        val original = checkpoint.begin(null, requestIdentityRequired = true)
        val other = json("""{"graph":{},"has_more":false,"index_job":{"request_id":"someone-else"}}""")
        assertTrue(checkpoint.reconcileObserved(other).unresolved)
        assertTrue(checkpoint.reconcileObserved(json("""{"graph":{},"has_more":false}""")).unresolved)
        val matched = checkpoint.reconcileObserved(json("""{"index_job":{"accepted_request_ids":["$original"]}}"""))
        assertEquals(checkpoint.attemptHash(original), matched.confirmedAttemptHash)
    }

    @Test fun originalConfirmationSurvivesRestartButAnUnsentRequestCannotProveExecution() = runTest {
        val store = GitHubRepositoryStateStore()
        val checkpoint = GatewayGraphIndexCheckpoint(store, "repo-at-head")
        val first = checkpoint.begin(null)
        checkpoint.confirm(first)
        val restored = GatewayGraphIndexCheckpoint(store, "repo-at-head")
        assertTrue(restored.previouslyConfirmed(restored.attemptHash(first)))
        val second = restored.begin(null)
        restored.confirm(second, executed = false)
        assertFalse(restored.previouslyConfirmed(restored.attemptHash(second)))
    }

    @Test fun lostFirstBatchSurvivesRestartAndAbsentStatusCannotAuthorizeAnotherIndex() = runTest {
        val directory = Files.createTempDirectory("graph-checkpoint").toFile()
        try {
            GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(directory), "selected-host-revision").begin(null)
            val restored = GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(directory), "selected-host-revision")
            assertTrue(restored.unresolved())
            assertTrue(restored.reconcile(json("""{"status":"NOT_INDEXED"}""")))
            assertTrue(runCatching { restored.begin(null) }.isFailure)
            assertFalse(restored.reconcile(json("""{"has_more":true,"index_cursor":"saved-page","graph":{}}""")))
            restored.begin("saved-page")
            assertTrue(restored.unresolved())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unchangedCursorRemainsUnconfirmedAndOnlyProgressOrCompletionReconcilesIt() = runTest {
        val checkpoint = GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(), "selected-host-revision")
        checkpoint.begin("same-page")
        assertTrue(checkpoint.reconcile(json("""{"has_more":true,"index_cursor":"same-page","graph":{}}""")))
        assertFalse(checkpoint.reconcile(json("""{"has_more":true,"index_cursor":"next-page","graph":{}}""")))
        checkpoint.begin("next-page")
        assertFalse(checkpoint.reconcile(json("""{"has_more":false,"graph":{}}""")))
    }

    @Test fun anotherHostOrRevisionCannotClearTheOriginalPendingOperation() = runTest {
        val store = GitHubRepositoryStateStore()
        val original = GatewayGraphIndexCheckpoint(store, "original-host-revision")
        original.begin(null)
        assertFalse(GatewayGraphIndexCheckpoint(store, "another-host-revision").reconcile(json("""{"has_more":false,"graph":{}}""")))
        assertTrue(original.unresolved())
    }

    @Test fun retryingFailedFilesCannotBeReconciledByTheOldCompletedSnapshot() = runTest {
        val checkpoint = GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(), "selected-host-revision")
        val before = json("""{"has_more":false,"graph":{"index_metrics":{"parsed_files":24}}}""")
        checkpoint.begin(null, before)
        assertTrue(checkpoint.reconcile(before))
        assertTrue(checkpoint.reconcile(json("""{"has_more":false,"graph":{"index_metrics":{"parsed_files":"25"}}}""")))
        assertTrue(checkpoint.reconcile(json("""{"has_more":false,"graph":{"index_metrics":{"parsed_files":-1}}}""")))
        assertFalse(checkpoint.reconcile(json("""{"has_more":false,"graph":{"index_metrics":{"parsed_files":25}}}""")))
    }

    @Test fun durableAcknowledgementReconcilesAQueuedJobBeforeItsFirstSourceBatch() = runTest {
        val checkpoint = GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(), "selected-host-revision")
        val request = checkpoint.begin(null)
        assertTrue(checkpoint.reconcile(json("""{"status":"NOT_INDEXED","index_job":{"id":"job","state":"QUEUED","request_id":"other"}}""")))
        assertFalse(checkpoint.reconcile(json("""{"status":"INDEX_JOB_ACCEPTED","index_job":{"id":"job","state":"QUEUED","request_id":"other","accepted_request_ids":["$request","other"]}}""")))
    }

    @Test fun localAttemptJoinSurvivesRestoreAndConfirmsOnlyTheExactExistingAttempt() = runTest {
        val store = GitHubRepositoryStateStore()
        val checkpoint = GatewayGraphIndexCheckpoint(store, "selected-host-account-revision")
        val attempt = checkpoint.begin(null)
        val hash = checkpoint.attemptHash(attempt)
        assertTrue(hash.matches(Regex("[a-f0-9]{64}")))
        assertFalse(hash.contains(attempt))
        assertNotEquals(hash, GatewayGraphIndexCheckpoint(store, "different-host-account-revision").attemptHash(attempt))
        val restored = GatewayGraphIndexCheckpoint(store, "selected-host-account-revision")
        assertEquals(hash, restored.observation().attemptHash)
        assertNull(restored.confirm("foreign-attempt"))
        assertTrue(restored.observation().unresolved)
        assertEquals(hash, restored.confirm(attempt))
        assertFalse(restored.observation().unresolved)
        assertNull(restored.observation().attemptHash)
        assertNull(restored.confirm(attempt))
    }

    @Test fun confirmationEvidenceCannotComeFromRemoteFieldsOrAReplacedAttempt() = runTest {
        val checkpoint = GatewayGraphIndexCheckpoint(GitHubRepositoryStateStore(), "selected-host-account-revision")
        val first = checkpoint.begin(null)
        val firstHash = checkpoint.attemptHash(first)
        val forged = checkpoint.reconcileObserved(json("""{"status":"NOT_INDEXED","confirmed_index_attempt_hash":"$firstHash","index_checkpoint":{"confirmed_index_attempt_hash":"$firstHash"}}"""))
        assertTrue(forged.unresolved)
        assertEquals(firstHash, forged.attemptHash)
        assertNull(forged.confirmedAttemptHash)
        val confirmed = checkpoint.reconcileObserved(json("""{"index_job":{"request_id":"$first"}}"""))
        assertFalse(confirmed.unresolved)
        assertEquals(firstHash, confirmed.confirmedAttemptHash)
        val second = checkpoint.begin(null)
        val unchanged = checkpoint.reconcileObserved(json("""{"index_job":{"request_id":"$first"}}"""))
        assertTrue(unchanged.unresolved)
        assertEquals(checkpoint.attemptHash(second), unchanged.attemptHash)
        assertNull(unchanged.confirmedAttemptHash)
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
