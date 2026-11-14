package dev.chungjungsoo.gptmobile.data.github

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayGraphIndexCheckpointTest {
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

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
