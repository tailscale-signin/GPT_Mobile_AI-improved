package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundedBatchTest {
    @Test fun workersActuallyOverlapRemainBoundedAndPreserveOrderAndIndividualFailures() = runTest {
        var active = 0
        var peak = 0
        val results = executeBoundedBatch((0..9).map { BatchRequest(prompt = "$it") }, 3) { request ->
            active++
            peak = maxOf(peak, active)
            try {
                delay(100L - request.prompt.toLong())
                if (request.prompt == "4") error("one failure")
                request.prompt
            } finally {
                active--
            }
        }
        assertEquals(3, peak)
        assertEquals(0, active)
        assertEquals(
            (0..9).toList(),
            results.map {
                when (it) {
                    is BatchResult.Success -> it.index
                    is BatchResult.Failure -> it.index
                }
            }
        )
        assertTrue(results[4] is BatchResult.Failure)
        assertEquals("9", (results.last() as BatchResult.Success).response)
    }

    @Test fun cancellationStopsAdmissionAndReleasesAllActiveWorkers() = runTest {
        var entered = 0
        var exited = 0
        val request = async {
            executeBoundedBatch(List(100) { BatchRequest(prompt = "test") }, 2) {
                entered++
                try {
                    CompletableDeferred<String>().await()
                } finally {
                    exited++
                }
            }
        }
        runCurrent()
        assertEquals(2, entered)
        request.cancelAndJoin()
        assertEquals(2, exited)
        assertTrue(request.isCancelled)
        assertFalse(request.isActive)
    }
}
