package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkQueuePolicyTest {
    @Test fun childAbortsInFirstMiddleAndLastPositionsDoNotCancelIndependentProfiles() = runTest {
        for (failedPosition in 0..2) {
            val executed = mutableListOf<String>()
            val outcomes = listOf("A", "B", "C").mapIndexed { index, profile ->
                benchmarkProfileIsolated(
                    block = {
                        executed += profile
                        if (index == failedPosition) throw CancellationException("Provider child aborted")
                        "COMPLETED"
                    },
                    failed = { failure ->
                        assertTrue(failure is CancellationException)
                        benchmarkFailure(failure).code
                    }
                )
            }
            assertEquals(listOf("A", "B", "C"), executed)
            assertEquals(1, outcomes.count { it == "CHILD_OPERATION_ABORTED" })
            assertEquals(2, outcomes.count { it == "COMPLETED" })
        }
    }

    @Test fun trueParentCancellationNeverStartsAnotherProfileOrRunsFailureRecovery() = runTest {
        val entered = CompletableDeferred<Unit>()
        var recovered = false
        var nextStarted = false
        val parent = launch {
            benchmarkProfileIsolated(
                block = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
                failed = { recovered = true }
            )
            nextStarted = true
        }
        entered.await()
        parent.cancelAndJoin()
        assertFalse(recovered)
        assertFalse(nextStarted)
    }

    @Test fun canonicalStorageFailureCannotBeReclassifiedAsProviderFailure() = runTest {
        var recovered = false
        var nextStarted = false
        try {
            benchmarkProfileIsolated<Unit>(
                block = { throw BenchmarkStorageFailure(IllegalStateException("disk full")) },
                failed = { recovered = true }
            )
            nextStarted = true
        } catch (_: BenchmarkStorageFailure) {
            // The application owner pauses; it never advances an uncertain durable cursor.
        }
        assertFalse(recovered)
        assertFalse(nextStarted)
    }

    @Test fun knownZeroAccuracyIsDifferentFromBlockedOrUnknownAccuracy() {
        fun run(sample: BenchmarkSample) = BenchmarkRun("run", "profile", "Name", "OPENAI", "model", "config", false, BenchmarkMode.QUICK, 1, listOf(sample), plannedTrials = 1)
        val blocked = BenchmarkSample("task", "Task", "task", BenchmarkOutcome.ERROR, requestDispatched = false, failureCategory = "DNS_FAILURE")
        val failed = blocked.copy(outcome = BenchmarkOutcome.FAILED, requestDispatched = true, outputCharacters = 10, terminalReceived = true, failureCategory = "MODEL_QUALITY")
        val unknown = blocked.copy(requestDispatched = null, failureCategory = null)
        fun metrics(sample: BenchmarkSample) = benchmarkMetricResults(listOf(run(sample))).associateBy { it.id }
        assertNull(metrics(blocked).getValue("answer_correctness").value)
        assertEquals(0.0, metrics(blocked).getValue("profile_availability").value!!, .001)
        assertNull(metrics(blocked).getValue("delivery_completion").value)
        assertEquals(0.0, metrics(failed).getValue("answer_correctness").value!!, .001)
        assertEquals(100.0, metrics(failed).getValue("delivery_completion").value!!, .001)
        assertNull(metrics(unknown).getValue("profile_availability").value)
    }

    @Test fun newSuiteUsesCompleteUniqueFixturesAndHistoricalSevenRemainsFrozen() {
        val current = benchmarkSuiteForVersion(8, BenchmarkMode.FULL)
        val historical = benchmarkSuiteForVersion(7, BenchmarkMode.FULL)
        assertEquals(50, current.size)
        assertEquals(50, current.map { it.id }.distinct().size)
        assertEquals(mapOf("speed" to 18, "task" to 10, "json" to 6, "tools" to 10, "intelligence" to 6), current.groupingBy { it.category }.eachCount())
        assertEquals(12, historical.count { it.category == "tools" })
        assertTrue(historical.any { it.id == "context" })
        assertTrue(current.none { it.id == "context" })
        assertTrue(benchmarkSuiteForVersion(999, BenchmarkMode.FULL).isEmpty())
        current.filter { it.category == "json" || it.category == "intelligence" }.forEach { kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(it.expected)) }
    }
}
