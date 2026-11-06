package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDispatchObservationTest {
    private fun result(error: Boolean = false) = AgentToolResult("call", ToolResultContent.Text("body"), error, dispatched = false)

    @Test fun responsesAreCorrelatedAndReorderedOrDuplicateObservationsDoNotInventWrites() = runTest {
        val events = mutableListOf<String>()
        val accounting = GitHubDispatchAccounting(record = events::add)
        withContext(accounting) {
            val write = GitHubDispatchAccounting.started(true)
            val read = GitHubDispatchAccounting.started(false)
            GitHubDispatchAccounting.responded(false, 200, read, "ABC:12")
            GitHubDispatchAccounting.responded(true, 201, write, "DEF:34")
            GitHubDispatchAccounting.responded(true, 201, write)
        }
        val receipt = accounting.finish(result(), true).executionReceipt!!
        assertEquals(JsonPrimitive(1), receipt["accepted_mutations"])
        assertEquals(JsonPrimitive(0), receipt["unknown_mutations"])
        assertEquals(JsonPrimitive(1), receipt["read_response_observations"])
        assertEquals(JsonPrimitive(1), receipt["observation_mismatches"])
        assertEquals(2, events.count { "state=DISPATCHED" in it })
        assertEquals(2, events.count { "state=HTTP_RESPONSE_OBSERVED" in it })
        assertTrue(events.any { "serverRequestId=ABC:12" in it })
        assertTrue(events.any { "serverRequestId=DEF:34" in it })
        assertTrue(events.last().contains("state=OPERATION_TERMINAL"))
        assertTrue(events.last().contains("taskCompletion=NOT_MEASURED"))
        accounting.finish(result(true), true)
        assertEquals(1, events.count { "state=OPERATION_TERMINAL" in it })
    }

    @Test fun requestTimeoutAndServerFailureKeepWritesUnknownWhileAuthorizationIsDefinite() = runTest {
        for ((status, uncertain) in listOf(408 to true, 500 to true, 503 to true, 403 to false, 422 to false)) {
            val accounting = GitHubDispatchAccounting(record = {})
            withContext(accounting) {
                val id = GitHubDispatchAccounting.started(true)
                GitHubDispatchAccounting.responded(true, status, id)
            }
            val completed = accounting.finish(result(true), true)
            assertEquals(JsonPrimitive(uncertain), completed.executionReceipt?.get("requires_reconciliation"))
            assertEquals(JsonPrimitive(if (uncertain) 1 else 0), completed.executionReceipt?.get("unknown_mutations"))
            assertEquals(if (uncertain) "RECONCILIATION_REQUIRED" else null, completed.errorCode)
        }
    }

    @Test fun unfinishedReadIsNotAnUncertainMutationAndCancellationClosesItsTrace() = runTest {
        var now = 10L
        val events = mutableListOf<String>()
        val accounting = GitHubDispatchAccounting(now = { now }, record = events::add)
        withContext(accounting) { GitHubDispatchAccounting.started(false) }
        now = 30
        accounting.cancelled()
        val receipt = accounting.finish(result(true), false).executionReceipt!!
        assertEquals(JsonPrimitive(1), receipt["unobserved_read_requests"])
        assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
        assertTrue(events.any { "state=NO_RESPONSE_OBSERVED" in it && "elapsedMs=20" in it })
        assertTrue(events.any { "phase=CANCELLED" in it })
        assertEquals(1, events.count { "state=OPERATION_TERMINAL" in it })
    }

    @Test fun cancellationAfterWriteDoesNotReleaseItsUnknownOutcome() = runTest {
        val events = mutableListOf<String>()
        val accounting = GitHubDispatchAccounting(record = events::add)
        withContext(accounting) { GitHubDispatchAccounting.started(true) }
        accounting.cancelled()
        val receipt = accounting.finish(result(true), true).executionReceipt!!
        assertEquals(JsonPrimitive(1), receipt["unknown_mutations"])
        assertTrue(events.any { "state=UNKNOWN_OUTCOME" in it })
    }

    @Test fun mismatchedCallbacksAndUntrustedServerIdsCannotPolluteEvidence() = runTest {
        val events = mutableListOf<String>()
        val accounting = GitHubDispatchAccounting(record = events::add)
        withContext(accounting) {
            val id = GitHubDispatchAccounting.started(true)
            GitHubDispatchAccounting.responded(false, 200, id)
            GitHubDispatchAccounting.responded(true, 201, id, "secret\nforged event")
            GitHubDispatchAccounting.responded(true, 201, "another-invocation")
        }
        val receipt = accounting.finish(result(), true).executionReceipt!!
        assertEquals(JsonPrimitive(2), receipt["observation_mismatches"])
        assertEquals(JsonPrimitive(1), receipt["accepted_mutations"])
        assertFalse(events.any { "secret" in it || "forged event" in it })
        assertTrue(events.any { "serverRequestId=not_recorded" in it })
    }

    @Test fun brokenLoggingCannotTurnSuccessfulRemoteAcceptanceIntoFailure() = runTest {
        val accounting = GitHubDispatchAccounting(record = { error("logging unavailable") })
        withContext(accounting) {
            val id = GitHubDispatchAccounting.started(true)
            GitHubDispatchAccounting.responded(true, 201, id)
        }
        val completed = accounting.finish(result(), true)
        assertFalse(completed.isError)
        assertEquals(JsonPrimitive(1), completed.executionReceipt?.get("accepted_mutations"))
    }
}
