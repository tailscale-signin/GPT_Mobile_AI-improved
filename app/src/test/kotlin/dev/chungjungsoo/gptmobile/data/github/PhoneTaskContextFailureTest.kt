package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTaskContextFailureTest {
    private val revision = "a".repeat(40)
    private val blob = "b".repeat(40)
    private val path = "src/Receipt.kt"
    private val files = listOf(GitHubContextFile(path, blob, 100))

    @Test fun aLocalFailureRetainsItsStageAndOriginalCauseWithoutPuttingPrivateTextInTheMessage() = runTest {
        val original = IllegalArgumentException("private source and task text")
        val failure = runCatching {
            GitHubTaskContextBundle.build("owner/repo", revision, "main", files, path, load = { throw original })
        }.exceptionOrNull() as PhoneTaskContextFailure
        assertEquals(PhoneTaskContextPhase.SOURCE_LOAD, failure.phase)
        assertEquals(PhoneTaskContextReason.LOCAL_INVARIANT_REJECTED, failure.reason)
        assertSame(original, failure.cause)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun rankingFailureRemainsDistinctFromAnEarlierSuccessfulSourceRead() = runTest {
        var reads = 0
        val failure = runCatching {
            GitHubTaskContextBundle.build(
                "owner/repo",
                revision,
                "main",
                files,
                path,
                GitHubContextVariant.SEMANTIC,
                load = {
                    reads++
                    GitHubContextSource(path, blob, "fun receipt() = 1")
                },
                localRanker = { _, _ -> error("private index content") }
            )
        }.exceptionOrNull() as PhoneTaskContextFailure
        assertEquals(1, reads)
        assertEquals(PhoneTaskContextPhase.SEMANTIC_RANKING, failure.phase)
        assertEquals(PhoneTaskContextReason.LOCAL_STATE_INVALID, failure.reason)
    }

    @Test fun identityPolicyTransportBudgetAndCancellationFailuresAreNeverReclassifiedAsLocalFallback() = runTest {
        val failures = listOf(
            GatewayGraphIntegrityException("GRAPH_REVISION_MISMATCH", "identity changed"),
            GatewayGraphAdmissionException("PERMISSION_DENIED", "not authorized"),
            GitHubReadValidation("READ_DEADLINE", JsonObject(emptyMap())),
            GitHubReadRequestBudgetExceeded(),
            GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_INVALID", "invalid text"),
            IOException("offline"),
            CancellationException("cancelled")
        )
        for (failure in failures) {
            val caught = runCatching { taskContextPhase(PhoneTaskContextPhase.SOURCE_LOAD) { throw failure } }.exceptionOrNull()
            assertSame(failure, caught)
        }
        val mismatch = runCatching {
            GitHubTaskContextBundle.build("owner/repo", revision, "main", files, path, load = { GitHubContextSource(path, revision, "wrong source") })
        }.exceptionOrNull()
        assertTrue(mismatch is GitHubReadValidation)
        assertEquals("EVIDENCE_REVISION_MISMATCH", (mismatch as GitHubReadValidation).code)
    }

    @Test fun deferredSourceIsAttemptedOnceAndReasonRemainsSeparateFromSourceEvidence() = runTest {
        var reads = 0
        val packet = GitHubTaskContextBundle.build(
            "owner/repo",
            revision,
            "main",
            files,
            path,
            load = {
                reads++
                null
            },
            readState = { GitHubContextReadState.PREPARATION_DEADLINE }
        )
        assertEquals(1, reads)
        assertEquals(JsonPrimitive(false), packet["source_complete"])
        assertEquals(JsonPrimitive(1), packet["unavailable_files"])
        assertEquals(JsonPrimitive(1), packet.getValue("deferred_reason_counts").jsonObject["PREPARATION_DEADLINE"])
        assertEquals(JsonPrimitive("PREPARATION_DEADLINE"), (packet.getValue("source_read_candidates") as JsonArray).single().jsonObject["deferred_reason"])
        assertTrue((packet.getValue("selected_ranges") as JsonArray).isEmpty())
    }
}
