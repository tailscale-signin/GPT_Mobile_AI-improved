package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHandleLifecycleTest {
    @Test fun cancellationDuringBlockingCreationReleasesWithoutPublishing() = runBlocking {
        val released = mutableListOf<Long>()
        var adopted = false
        val request = launch {
            val job = currentCoroutineContext()[Job]!!
            adoptNativeHandle({
                job.cancel()
                42L
            }, { error("Cancelled handle was validated") }, { adopted = true }, { released += it })
        }
        request.join()
        assertTrue(request.isCancelled)
        assertFalse(adopted)
        assertEquals(listOf(42L), released)
    }

    @Test fun failedValidationRetainsOriginalFailureAndReleasesOnce() = runBlocking {
        val original = IllegalStateException("capability query failed")
        val cleanup = IllegalStateException("destroy failed")
        var releases = 0
        val error = runCatching {
            adoptNativeHandle({ 4L }, { throw original }, { error("Invalid handle adopted") }, {
                releases++
                throw cleanup
            })
        }.exceptionOrNull()
        assertEquals(original, error)
        assertEquals(listOf(cleanup), error!!.suppressed.toList())
        assertEquals(1, releases)
    }

    @Test fun successfulAdoptionTransfersOwnershipAndZeroHandlesAreNeverDestroyed() = runBlocking {
        var adopted = 0L
        var releases = 0
        adoptNativeHandle({ 7L }, {}, { adopted = it }, { releases++ })
        assertEquals(7L, adopted)
        assertEquals(0, releases)
        assertTrue(runCatching { adoptNativeHandle({ 0L }, {}, {}, { releases++ }) }.isFailure)
        assertEquals(0, releases)
    }

    @Test fun shutdownAttemptsDestroyAfterStopFailureAndPreservesBothErrors() {
        val stop = IllegalStateException("stop failed")
        val destroy = IllegalStateException("destroy failed")
        val destroyed = mutableListOf<Long>()
        val error = runCatching {
            releaseNativeHandle(9L, { throw stop }, {
                destroyed += it
                throw destroy
            })
        }.exceptionOrNull()
        assertEquals(listOf(9L), destroyed)
        assertEquals(stop, error)
        assertEquals(listOf(destroy), error!!.suppressed.toList())
    }
}
