package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubBoundedCheckWaitTest {
    @Test fun completedRunReturnsItsObservedOutcomeWithoutWaitingForDeadline() = runTest {
        var reads = 0
        val result = GitHubBoundedCheckWait.await(30, { testScheduler.currentTime }) {
            reads++
            buildJsonObject {
                put("status", if (reads == 1) "RUNNING" else "PASSED")
                put("head_sha", "a".repeat(40))
                put("run_id", 8)
            }
        }
        assertEquals(2, reads)
        assertEquals(JsonPrimitive("PASSED"), result["status"])
        assertEquals(JsonPrimitive("TERMINAL_OBSERVED"), result["wait_outcome"])
        assertEquals(JsonPrimitive(5000L), result["wait_elapsed_ms"])
        assertEquals(JsonPrimitive("a".repeat(40)), result["head_sha"])
    }

    @Test fun pendingChecksHaveABoundedTimeoutAndNeverBecomePassingTests() = runTest {
        val result = GitHubBoundedCheckWait.await(30, { testScheduler.currentTime }) { buildJsonObject { put("status", "RUNNING") } }
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(JsonPrimitive("PENDING_CHECKS"), result["status"])
        assertEquals(JsonPrimitive("RUNNING"), result["observed_status"])
        assertEquals(JsonPrimitive(false), result["complete"])
        assertEquals(JsonPrimitive("PENDING_TIMEOUT"), result["wait_outcome"])
        assertEquals(JsonPrimitive("NOT_TESTED"), result["device_test_execution"])
    }

    @Test fun aDeadlineBeforeAnyResponseDoesNotInventAnObservation() = runTest {
        val result = GitHubBoundedCheckWait.await(1, { testScheduler.currentTime }) {
            delay(2000)
            buildJsonObject { put("status", "PASSED") }
        }
        assertEquals(JsonPrimitive(0), result["observations"])
        assertFalse("observed_status" in result)
        assertEquals(JsonPrimitive(false), result["complete"])
    }

    @Test fun failedChecksRemainFailedAndParentCancellationIsPropagated() = runTest {
        val failed = GitHubBoundedCheckWait.await(30, { testScheduler.currentTime }) { buildJsonObject { put("status", "FAILED") } }
        assertEquals(JsonPrimitive("FAILED"), failed["status"])
        assertEquals(JsonPrimitive(true), failed["complete"])
        val cancelled = runCatching {
            withTimeout(10) {
                GitHubBoundedCheckWait.await(30, { testScheduler.currentTime }) {
                    delay(100)
                    buildJsonObject { put("status", "PASSED") }
                }
            }
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }
}
