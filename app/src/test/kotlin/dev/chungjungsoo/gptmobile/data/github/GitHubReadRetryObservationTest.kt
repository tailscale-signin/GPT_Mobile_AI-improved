package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadRetryObservationTest {
    private fun success() = AgentToolResult("read", ToolResultContent.Text("Current response"), false)

    @Test fun recoveredReadRetainsItsFailedAttemptWithoutRejectingFreshEvidence() = runTest {
        val events = mutableListOf<String>()
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = events::add)
        var attempts = 0
        withContext(accounting) {
            GitHubReadRetry.run {
                val id = GitHubDispatchAccounting.started(false, "/repos/owner/repo/branches/main")
                if (++attempts == 1) throw IOException("connection reset")
                GitHubDispatchAccounting.responded(false, 200, id)
            }
        }
        val result = accounting.finish(success(), false)
        assertFalse(result.isError)
        val receipt = result.executionReceipt!!
        assertEquals(JsonPrimitive(true), receipt["live_read_performed"])
        assertEquals(JsonPrimitive(2), receipt["read_requests"])
        assertEquals(JsonPrimitive(1), receipt["read_response_observations"])
        assertEquals(JsonPrimitive(1), receipt["recovered_read_requests"])
        assertEquals(JsonPrimitive(0), receipt["unobserved_read_requests"])
        assertEquals(1, events.count { "state=READ_RETRY_RECOVERED" in it && "responseForOriginalAttempt=NOT_OBSERVED" in it })
    }

    @Test fun aDifferentPageCannotReplaceTheUnansweredRead() = runTest {
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
        var attempts = 0
        withContext(accounting) {
            GitHubReadRetry.run {
                val id = GitHubDispatchAccounting.started(false, "/repos/owner/repo/issues?page=${++attempts}")
                if (attempts == 1) throw IOException("connection reset")
                GitHubDispatchAccounting.responded(false, 200, id)
            }
        }
        val result = accounting.finish(success(), false)
        assertEquals("GITHUB_LIVE_OBSERVATION_REQUIRED", result.errorCode)
        assertEquals(JsonPrimitive(0), result.executionReceipt?.get("recovered_read_requests"))
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("unobserved_read_requests"))
    }

    @Test fun anIndependentReadOfTheSamePathCannotEraseAnEarlierFailure() = runTest {
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
        withContext(accounting) {
            runCatching {
                GitHubReadRetry.run {
                    GitHubDispatchAccounting.started(false, "/same")
                    throw SSLException("certificate rejected")
                }
            }
            var attempts = 0
            GitHubReadRetry.run {
                val id = GitHubDispatchAccounting.started(false, "/same")
                if (++attempts == 1) throw IOException("connection reset")
                GitHubDispatchAccounting.responded(false, 200, id)
            }
        }
        val result = accounting.finish(success(), false)
        assertTrue(result.isError)
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("live_read_performed"))
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("recovered_read_requests"))
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("unobserved_read_requests"))
    }

    @Test fun earlierFailedHttpResponseCannotCertifyTheFinalRetriedTarget() = runTest {
        for (finalPath in listOf("/same?page=1", "/same?page=2")) {
            val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            var attempts = 0
            withContext(accounting) {
                GitHubReadRetry.run {
                    attempts++
                    val id = GitHubDispatchAccounting.started(false, if (attempts < 3) "/same?page=1" else finalPath)
                    when (attempts) {
                        1 -> {
                            GitHubDispatchAccounting.responded(false, 503, id)
                            throw GitHubReadRetry.HttpFailure(503, null)
                        }
                        2 -> throw IOException("connection reset")
                        else -> GitHubDispatchAccounting.responded(false, 200, id)
                    }
                }
            }
            val result = accounting.finish(success(), false)
            val sameTarget = finalPath == "/same?page=1"
            assertEquals(sameTarget, !result.isError)
            assertEquals(JsonPrimitive(sameTarget), result.executionReceipt?.get("live_read_performed"))
            assertEquals(JsonPrimitive(if (sameTarget) 1 else 0), result.executionReceipt?.get("recovered_read_requests"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("read_http_failures"))
            assertEquals(JsonPrimitive(2), result.executionReceipt?.get("read_response_observations"))
            assertEquals(JsonPrimitive(if (sameTarget) 0 else 1), result.executionReceipt?.get("unobserved_read_requests"))
        }
    }

    @Test fun exhaustedRetriesPreserveAllUnansweredAttempts() = runTest {
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
        withContext(accounting) {
            runCatching {
                GitHubReadRetry.run {
                    GitHubDispatchAccounting.started(false, "/same")
                    throw IOException("still disconnected")
                }
            }
        }
        val result = accounting.finish(success(), false)
        assertTrue(result.isError)
        assertEquals(JsonPrimitive(0), result.executionReceipt?.get("recovered_read_requests"))
        assertEquals(JsonPrimitive(3), result.executionReceipt?.get("unobserved_read_requests"))
    }

    @Test fun onlyQualifiedResponsesCanReplaceAnUnansweredAttempt() = runTest {
        for ((status, expectedAbsence, verified) in listOf(Triple(403, false, false), Triple(404, false, false), Triple(404, true, true), Triple(304, false, true))) {
            val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            var attempts = 0
            withContext(accounting) {
                GitHubReadRetry.run {
                    val id = GitHubDispatchAccounting.started(false, "/same")
                    if (++attempts == 1) throw IOException("connection reset")
                    GitHubDispatchAccounting.responded(false, status, id, null, expectedAbsence)
                }
            }
            val result = accounting.finish(success(), false)
            assertEquals("HTTP $status, expectedAbsence=$expectedAbsence", verified, !result.isError)
            assertEquals(JsonPrimitive(if (verified) 1 else 0), result.executionReceipt?.get("recovered_read_requests"))
            assertEquals(JsonPrimitive(if (verified) 0 else 1), result.executionReceipt?.get("unobserved_read_requests"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("read_response_observations"))
        }
    }

    @Test fun cancellationAfterResponseDoesNotCertifyTheUnfinishedRetry() = runTest {
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
        var attempts = 0
        runCatching {
            withContext(accounting) {
                GitHubReadRetry.run {
                    val id = GitHubDispatchAccounting.started(false, "/same")
                    if (++attempts == 1) throw IOException("connection reset")
                    GitHubDispatchAccounting.responded(false, 200, id)
                    throw CancellationException("cancelled before completing the read")
                }
            }
        }
        val result = accounting.finish(success(), false)
        assertTrue(result.isError)
        assertEquals(JsonPrimitive(0), result.executionReceipt?.get("recovered_read_requests"))
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("unobserved_read_requests"))
    }

    @Test fun successfulReadRecoveryCannotReconcileAnUncertainMutation() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        var writes = 0
        withContext(accounting) {
            runCatching {
                GitHubReadRetry.run(readOnly = false) {
                    writes++
                    GitHubDispatchAccounting.started(true, "/same")
                    throw IOException("write response lost")
                }
            }
            var attempts = 0
            GitHubReadRetry.run {
                val id = GitHubDispatchAccounting.started(false, "/same")
                if (++attempts == 1) throw IOException("connection reset")
                GitHubDispatchAccounting.responded(false, 200, id)
            }
        }
        val result = accounting.finish(success(), true)
        assertEquals(1, writes)
        assertEquals("RECONCILIATION_REQUIRED", result.errorCode)
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("unknown_mutations"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("requires_reconciliation"))
    }
}
