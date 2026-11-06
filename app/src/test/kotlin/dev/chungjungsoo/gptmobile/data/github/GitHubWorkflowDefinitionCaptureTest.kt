package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkflowDefinitionCaptureTest {
    @Test fun unreadableDefinitionDoesNotDiscardIndependentSuccessesOrRetryAgain() = runTest {
        var reads = 0
        val rows = (1..3).map { index ->
            GitHubWorkflowDefinitionCapture.capture {
                reads++
                if (index == 2) throw IOException("private endpoint must not appear in the result")
                "name: Build $index\non: [push]\njobs:\n  test:\n    runs-on: ubuntu-latest"
            }
        }
        assertEquals(3, reads)
        assertEquals(JsonPrimitive(true), rows.first()["metadata_available"])
        assertEquals(JsonPrimitive(false), rows[1]["metadata_available"])
        assertEquals(JsonPrimitive("GITHUB_TRANSIENT_NETWORK"), rows[1]["error_code"])
        assertFalse(rows[1].toString().contains("private endpoint"))
        assertEquals(JsonPrimitive(true), rows.last()["metadata_available"])
    }

    @Test fun upstreamFailureAndMetadataBudgetKeepSeparateReasons() = runTest {
        val missing = GitHubWorkflowDefinitionCapture.capture { throw GitHubRequestRejected(404, "missing") }
        val server = GitHubWorkflowDefinitionCapture.capture { throw GitHubReadRetry.HttpFailure(503, null) }
        val oversized = GitHubWorkflowDefinitionCapture.capture { "x".repeat(256 * 1024 + 1) }
        assertEquals(JsonPrimitive("GITHUB_NOT_FOUND_OR_INACCESSIBLE"), missing["error_code"])
        assertEquals(JsonPrimitive("GITHUB_TRANSIENT_SERVER"), server["error_code"])
        assertEquals(JsonPrimitive("GITHUB_WORKFLOW_METADATA_LIMIT"), oversized["error_code"])
    }

    @Test fun cancellationTlsPermissionsAndUnknownFailuresStopCapture() = runTest {
        for (failure in listOf(CancellationException("cancel"), SSLException("TLS"), GitHubRequestRejected(403, "permission"), IllegalStateException("unexpected"), IllegalArgumentException("identity mismatch"))) {
            val actual = runCatching { GitHubWorkflowDefinitionCapture.capture { throw failure } }.exceptionOrNull()
            assertSame(failure, actual)
        }
        assertTrue(GitHubWorkflowDefinitionCapture.capture { "on: push" }["metadata_available"] == JsonPrimitive(true))
    }
}
