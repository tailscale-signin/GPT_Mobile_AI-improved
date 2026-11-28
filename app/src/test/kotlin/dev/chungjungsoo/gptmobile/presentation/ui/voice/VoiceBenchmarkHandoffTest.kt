package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBenchmarkHandoffTest {
    @Test
    fun theCampaignWaitsForBothRetirementsAndDuplicateCallbacksDoNotResumeItTwice() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val lease = requireNotNull(ownership.acquire())
        var recognition: (() -> Unit)? = null
        var synthesis: (() -> Unit)? = null
        var campaigns = 0
        val job = launch {
            assertTrue(
                runAfterVoiceBenchmarkRetirement(lease, {
                    awaitVoiceBenchmarkRetirement({ recognition = it }, { synthesis = it })
                }, { false }, { error("Cleanup was confirmed") }) { campaigns++ }
            )
        }
        job.invokeOnCompletion { lease.release() }
        runCurrent()

        assertNotNull(recognition)
        assertNotNull(synthesis)
        assertSame(lease, ownership.state.value)
        assertNull(ownership.acquire())
        recognition!!.invoke()
        recognition!!.invoke()
        runCurrent()
        assertEquals(0, campaigns)
        assertFalse(job.isCompleted)
        assertSame(lease, ownership.state.value)
        synthesis!!.invoke()
        runCurrent()
        job.join()
        assertEquals(1, campaigns)
        assertNull(ownership.state.value)
        recognition!!.invoke()
        synthesis!!.invoke()
        runCurrent()
        assertEquals(1, campaigns)
    }

    @Test
    fun userStopBeforeOrDuringHandoffRetainsOwnershipUntilCleanupAndSkipsTheCampaign() = runTest {
        for (alreadyStopped in listOf(false, true)) {
            val ownership = VoiceSpeechBenchmarkOwnership()
            val lease = requireNotNull(ownership.acquire())
            var stopped = alreadyStopped
            var retired: (() -> Unit)? = null
            var campaigns = 0
            val job = launch {
                assertFalse(
                    runAfterVoiceBenchmarkRetirement(lease, {
                        awaitVoiceBenchmarkRetirement({ retired = it })
                    }, { stopped }, { error("Cleanup was confirmed") }) { campaigns++ }
                )
            }
            job.invokeOnCompletion { lease.release() }
            runCurrent()
            stopped = true
            runCurrent()
            assertEquals(0, campaigns)
            assertSame(lease, ownership.state.value)
            assertNull(ownership.acquire())
            assertFalse(job.isCompleted)
            retired!!.invoke()
            job.join()
            assertEquals(0, campaigns)
            assertNull(ownership.state.value)
            assertNull(ownership.quarantineReason.value)
        }
    }

    @Test
    fun lifecycleCancellationCannotReleaseTheLeaseUntilBothOwnersRetire() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val lease = requireNotNull(ownership.acquire())
        val retirements = mutableListOf<() -> Unit>()
        var campaigns = 0
        val job = launch {
            runAfterVoiceBenchmarkRetirement(lease, {
                awaitVoiceBenchmarkRetirement({ retirements += it }, { retirements += it })
            }, { false }, { error("Cleanup was confirmed") }) { campaigns++ }
        }
        job.invokeOnCompletion { lease.release() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(job.isCompleted)
        assertSame(lease, ownership.state.value)
        retirements.first().invoke()
        runCurrent()
        assertFalse(job.isCompleted)
        assertNull(ownership.acquire())
        retirements.last().invoke()
        job.join()
        assertEquals(0, campaigns)
        assertNull(ownership.state.value)
        assertNull(ownership.quarantineReason.value)
    }

    @Test
    fun timeoutQuarantinesCapturedOwnersAndLateCallbacksCannotUnblockAnotherCampaign() = runTest {
        for (lifecycleCancelled in listOf(false, true)) {
            val ownership = VoiceSpeechBenchmarkOwnership()
            val lease = requireNotNull(ownership.acquire())
            var retired: (() -> Unit)? = null
            val beforeStart: suspend () -> Unit = { awaitVoiceBenchmarkRetirement({ retired = it }) }
            var retained: Any? = null
            var failure: VoiceBenchmarkHandoffException? = null
            var campaigns = 0
            val job = launch {
                try {
                    runAfterVoiceBenchmarkRetirement(lease, beforeStart, { false }, { retained = beforeStart }) { campaigns++ }
                } catch (error: VoiceBenchmarkHandoffException) {
                    failure = error
                }
            }
            job.invokeOnCompletion { lease.release() }
            runCurrent()
            if (lifecycleCancelled) job.cancel()
            advanceTimeBy(29_999)
            runCurrent()
            assertFalse(job.isCompleted)
            assertNull(ownership.quarantineReason.value)
            advanceTimeBy(1)
            runCurrent()
            job.join()

            assertEquals(0, campaigns)
            assertSame(beforeStart, retained)
            assertTrue(generateSequence<Throwable>(requireNotNull(failure)) { it.cause }.any { it is TimeoutCancellationException })
            assertTrue(failure!!.message.orEmpty().contains("Restart the app"))
            assertEquals(VoiceSpeechBenchmarkOwnership.QuarantineReason.NATIVE_CLEANUP_UNCONFIRMED, ownership.quarantineReason.value)
            assertSame(lease, ownership.state.value)
            assertFalse(lease.release())
            assertNull(ownership.acquire())
            retired!!.invoke()
            retired!!.invoke()
            runCurrent()
            assertNull(ownership.acquire())
        }
    }

    @Test
    fun aRegistrationFailureStillRequestsEveryOwnerAndCannotReportSuccessfulRetirement() = runTest {
        for (callbackBeforeFailure in listOf(false, true)) {
            val ownership = VoiceSpeechBenchmarkOwnership()
            val lease = requireNotNull(ownership.acquire())
            var secondRequested = false
            var retained = false
            var failure: VoiceBenchmarkHandoffException? = null
            var campaigns = 0
            val job = launch {
                try {
                    runAfterVoiceBenchmarkRetirement(lease, {
                        awaitVoiceBenchmarkRetirement({ done ->
                            if (callbackBeforeFailure) done()
                            error("release was not confirmed")
                        }, { secondRequested = true })
                    }, { false }, { retained = true }) { campaigns++ }
                } catch (error: VoiceBenchmarkHandoffException) {
                    failure = error
                }
            }
            job.invokeOnCompletion { lease.release() }
            job.join()
            assertTrue(secondRequested)
            assertTrue(retained)
            assertEquals("release was not confirmed", generateSequence<Throwable>(requireNotNull(failure)) { it.cause }.last().message)
            assertEquals(0, campaigns)
            assertSame(lease, ownership.state.value)
            assertNull(ownership.acquire())
        }
    }

    @Test
    fun synchronousConfirmedCallbacksStartExactlyOneCampaignWithoutQuarantine() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val lease = requireNotNull(ownership.acquire())
        var campaigns = 0
        assertTrue(
            runAfterVoiceBenchmarkRetirement(lease, {
                awaitVoiceBenchmarkRetirement({ done ->
                    done()
                    done()
                }, { done -> done() })
            }, { false }, { error("Cleanup was confirmed") }) { campaigns++ }
        )
        assertEquals(1, campaigns)
        assertNull(ownership.quarantineReason.value)
        assertTrue(lease.release())
    }
}
