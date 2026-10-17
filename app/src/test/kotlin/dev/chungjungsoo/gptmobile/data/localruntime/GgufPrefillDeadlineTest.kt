package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufPrefillDeadlineTest {
    @Test fun stalledPrefillCancelsNativeAndDiscardsReceipt() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val result = withGgufPrefillDeadline(100, { cancelled.complete(Unit) }) {
            cancelled.await()
            "cancelled receipt"
        }
        assertNull(result)
        assertTrue(cancelled.isCompleted)
    }

    @Test fun productiveDecodingCanOutlivePrefillBudget() = runTest {
        var cancelled = false
        val result = withGgufPrefillDeadline(100, { cancelled = true }) { firstOutput ->
            delay(50)
            firstOutput()
            delay(500)
            "complete"
        }
        assertEquals("complete", result)
        assertFalse(cancelled)
    }

    @Test fun nativeFailureBeforeDeadlineIsPreserved() = runTest {
        val expected = IllegalStateException("vk::Device::createComputePipeline")
        val actual = runCatching {
            withGgufPrefillDeadline(100, {}) { throw expected }
        }.exceptionOrNull()
        assertSame(expected, actual)
    }

    @Test fun cancellationAfterDeadlineIsNeverSwallowed() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val expected = CancellationException("user stopped")
        val actual = runCatching {
            withGgufPrefillDeadline(100, { cancelled.complete(Unit) }) {
                cancelled.await()
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, actual)
    }
}
