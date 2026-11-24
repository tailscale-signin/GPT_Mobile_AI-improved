package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSilentWarmupTest {
    @Test fun successfulWarmupReusesOnlyTheExactModelVoiceAndRate() {
        val warmup = VoiceSilentWarmup()
        val owner = Any()
        var resets = 0
        fun begin(model: Any = owner, speaker: Int = 0, rate: Float = 1f) = warmup.begin(model, speaker, rate, { true }, { resets++ }, {})
        val first = requireNotNull(begin())
        warmup.finish(first, true) { true }
        assertNull(begin())
        assertEquals(1, resets)
        val voiceChanged = requireNotNull(begin(speaker = 1))
        warmup.finish(voiceChanged, true) { true }
        val rateChanged = requireNotNull(begin(speaker = 1, rate = 1.1f))
        warmup.finish(rateChanged, true) { true }
        val modelChanged = requireNotNull(begin(model = Any(), speaker = 1, rate = 1.1f))
        warmup.finish(modelChanged, true) { true }
        assertEquals(4, resets)
    }

    @Test fun staleDeadlineCannotCancelTheNextWarmupOrLiveEngine() {
        val warmup = VoiceSilentWarmup()
        val owner = Any()
        var cancellations = 0
        val first = requireNotNull(warmup.begin(owner, 0, 1f, { true }, {}, { cancellations++ }))
        assertTrue(warmup.cancel(first))
        warmup.finish(first, true) { true }
        val second = requireNotNull(warmup.begin(owner, 0, 1f, { true }, {}, { cancellations++ }))
        assertFalse(warmup.cancel(first))
        assertTrue(warmup.isCurrent(second) { true })
        warmup.finish(second, true) { true }
        // The native owner is now free to reset the same session for live speech.
        assertFalse(warmup.cancel(first))
        assertFalse(warmup.cancel(second))
        assertFalse(warmup.cancel())
        assertEquals(1, cancellations)
    }

    @Test fun queuedPreparationRevokedBeforePublicationNeverResetsTheEngine() {
        val warmup = VoiceSilentWarmup()
        var resets = 0
        assertNull(warmup.begin(Any(), 0, 1f, { false }, { resets++ }, {}))
        assertEquals(0, resets)
    }

    @Test fun liveCancellationCannotBeLostDuringTheNativeReset() {
        val warmup = VoiceSilentWarmup()
        val resetEntered = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        val cancellationRequested = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val published = AtomicReference<VoiceSilentWarmup.Attempt?>()
        val worker = thread(name = "warmup-reset", isDaemon = true) {
            published.set(
                warmup.begin(Any(), 0, 1f, { true }, {
                    resetEntered.countDown()
                    check(releaseReset.await(5, TimeUnit.SECONDS))
                    cancelled.set(false)
                }, { cancelled.set(true) })
            )
        }
        assertTrue(resetEntered.await(5, TimeUnit.SECONDS))
        val liveReply = thread(name = "warmup-interruption", isDaemon = true) {
            cancellationRequested.countDown()
            warmup.cancel()
        }
        try {
            assertTrue(cancellationRequested.await(5, TimeUnit.SECONDS))
        } finally {
            releaseReset.countDown()
            worker.join(5_000)
            liveReply.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertFalse(liveReply.isAlive)
        val attempt = requireNotNull(published.get())
        assertTrue(cancelled.get())
        assertFalse(warmup.isCurrent(attempt) { true })
        warmup.finish(attempt, true) { true }
    }

    @Test fun cancelledInferenceMustRetireBeforeAnotherOwnerCanResetIt() {
        val warmup = VoiceSilentWarmup()
        val owner = Any()
        var resets = 0
        val attempt = requireNotNull(warmup.begin(owner, 0, 1f, { true }, { resets++ }, {}))
        assertTrue(warmup.cancel())
        assertNull(warmup.begin(owner, 0, 1f, { true }, { resets++ }, {}))
        assertEquals(1, resets)
        warmup.finish(attempt, false) { false }
        assertNotNull(warmup.begin(owner, 0, 1f, { true }, { resets++ }, {}))
        assertEquals(2, resets)
    }

    @Test fun staleCompletionCannotReleaseAReplacementOwner() {
        val warmup = VoiceSilentWarmup()
        val owner = Any()
        val first = requireNotNull(warmup.begin(owner, 0, 1f, { true }, {}, {}))
        warmup.finish(first, false) { true }
        val replacement = requireNotNull(warmup.begin(owner, 0, 1f, { true }, {}, {}))
        warmup.finish(first, true) { true }
        assertTrue(warmup.isCurrent(replacement) { true })
        warmup.finish(replacement, true) { true }
    }

    @Test fun revokedOrFailedInferenceDoesNotRecordAReadyConfiguration() {
        val warmup = VoiceSilentWarmup()
        val owner = Any()
        for ((success, authorized) in listOf(false to true, true to false)) {
            val attempt = requireNotNull(warmup.begin(owner, 0, 1f, { true }, {}, {}))
            warmup.finish(attempt, success) { authorized }
        }
        assertNotNull(warmup.begin(owner, 0, 1f, { true }, {}, {}))
    }
}
