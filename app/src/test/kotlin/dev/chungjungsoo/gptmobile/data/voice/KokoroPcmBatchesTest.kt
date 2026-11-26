package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroPcmBatchesTest {
    @Test fun reserveReceiptDistinguishesTheActualExitWithoutConsumingAudio() {
        val queue = ArrayBlockingQueue<VoicePcmSlice>(1)
        queue.add(slice(20))
        fun exit(target: Long = 24_000, accepted: Long = 480, active: Boolean = true, producer: Boolean = true, deadline: Boolean = false) =
            KokoroPcmBatches.awaitReserve(queue, target, { accepted }, { active }, { producer }, { deadline }) {
                error("This fixture already has a terminal reserve condition")
            }
        assertEquals(VoicePcmReserveExit.TARGET, exit(target = 480))
        assertEquals(VoicePcmReserveExit.QUEUE_FULL, exit())
        assertEquals(1, queue.size)
        queue.clear()
        assertEquals(VoicePcmReserveExit.DEADLINE, exit(deadline = true))
        assertEquals(VoicePcmReserveExit.PRODUCER_IDLE, exit(producer = false))
        assertEquals(VoicePcmReserveExit.STOPPED, exit(active = false))
    }

    @Test fun hardwarePrimingConsumesOnlyAvailablePcmAfterTheReserveReleases() {
        val queue = object : ArrayBlockingQueue<VoicePcmSlice>(4) {
            override fun poll(timeout: Long, unit: TimeUnit): VoicePcmSlice? = error("Priming waited a second time")
        }
        val samples = FloatArray(8)
        val first = VoicePcmSlice(samples, 0, 2)
        val second = VoicePcmSlice(samples, 2, 3)
        val third = VoicePcmSlice(samples, 5, 3)
        assertEquals(listOf(first), KokoroPcmBatches.primeReady(queue, first, 8))
        queue.add(second)
        queue.add(third)
        assertEquals(listOf(first, second), KokoroPcmBatches.primeReady(queue, first, 5))
        assertEquals(third, queue.poll())
    }

    @Test fun callbackPcmIsBoundedByDurationWithoutLosingOrRepeatingFrames() {
        for (sampleRate in listOf(8_000, 24_000, 48_000)) {
            val original = FloatArray(sampleRate * 2 + 7) { it.toFloat() }
            val emitted = mutableListOf<FloatArray>()
            assertTrue(
                KokoroPcmBatches.emit(original, sampleRate) {
                    emitted += it
                    true
                }
            )
            assertTrue(emitted.all { it.isNotEmpty() && it.size <= sampleRate / 10 })
            assertArrayEquals(original, emitted.flatMap { it.asIterable() }.toFloatArray(), 0f)
            assertEquals(6_000, KokoroPcmBatches.QUEUE_LIMIT_MS)
        }
    }

    @Test fun cancellationStopsProducingBatchesImmediately() {
        var batches = 0
        assertFalse(KokoroPcmBatches.emit(FloatArray(24_000 * 30), 24_000) { ++batches < 2 })
        assertEquals(2, batches)
    }

    @Test fun emptyCallbackDoesNotCreateAPhantomAudioBatch() {
        assertTrue(KokoroPcmBatches.emit(floatArrayOf(), 24_000) { error("empty audio must not be queued") })
    }

    @Test fun fullQueueOfMixedPhraseTailsStartsPlaybackAndUnblocksTheProducer() {
        val queue = mixedTailQueue()
        val heldFirst = slice(100)
        val accepted = AtomicLong(heldFirst.size.toLong() + queue.sumOf { it.size })
        val policy = measuredRefillPolicy()
        val target = 24_000L * policy.bufferMs() / 1_000
        assertEquals(5_600L, queue.sumOf { it.size }.toLong() * 1_000 / 24_000)
        assertEquals(5_706, policy.bufferMs())
        assertEquals(0, queue.remainingCapacity())
        assertTrue("Full slots need not satisfy the duration reserve", accepted.get() < target)

        val next = slice(100)
        val started = CountDownLatch(1)
        val produced = CountDownLatch(1)
        val producer = thread(name = "pcm-reserve-test-producer", isDaemon = true) {
            started.countDown()
            try {
                queue.put(next)
                accepted.addAndGet(next.size.toLong())
                produced.countDown()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse("No PCM slot is available to the real producer", produced.await(20, TimeUnit.MILLISECONDS))
            var elapsed = 0L
            KokoroPcmBatches.awaitReserve(
                queue = queue,
                targetFrames = target,
                acceptedFrames = accepted::get,
                canWait = { true },
                producerPending = { true },
                deadlineReached = { elapsed >= policy.primeWaitMs() },
                poll = { elapsed += 12 }
            )
            assertEquals("Playback must not spend its reserve deadline waiting for a blocked producer", 0L, elapsed)
            assertEquals("Priming must retain the bounded queue intact", KokoroPcmBatches.QUEUE_BATCHES, queue.size)
            queue.remove()
            assertTrue("Consuming one PCM slice allows synthesis to make progress", produced.await(5, TimeUnit.SECONDS))
            assertSame(next, queue.last())
        } finally {
            producer.interrupt()
            producer.join(5_000)
        }
    }

    @Test fun oneFreeSlotBelowTheReserveStillWaitsUntilTheDeadline() {
        val queue = mixedTailQueue()
        queue.remove()
        val accepted = slice(100).size.toLong() + queue.sumOf { it.size }
        val policy = measuredRefillPolicy()
        var elapsed = 0L
        KokoroPcmBatches.awaitReserve(
            queue = queue,
            targetFrames = 24_000L * policy.bufferMs() / 1_000,
            acceptedFrames = { accepted },
            canWait = { true },
            producerPending = { true },
            deadlineReached = { elapsed >= policy.primeWaitMs() },
            poll = { elapsed += 12 }
        )
        assertEquals(1, queue.remainingCapacity())
        assertTrue(elapsed >= policy.primeWaitMs())
        assertTrue(elapsed < policy.primeWaitMs() + 12)
    }

    @Test fun aGrowingQueueStartsAsSoonAsItsDurationReserveArrives() {
        val queue = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
        repeat(2) { queue.add(slice(100)) }
        var elapsed = 0L
        KokoroPcmBatches.awaitReserve(
            queue = queue,
            targetFrames = 12_000,
            acceptedFrames = { queue.sumOf { it.size.toLong() } },
            canWait = { true },
            producerPending = { true },
            deadlineReached = { elapsed >= 60 },
            poll = {
                elapsed += 12
                queue.add(slice(100))
            }
        )
        assertEquals(36L, elapsed)
        assertEquals(5, queue.size)
        assertEquals(55, queue.remainingCapacity())
    }

    @Test fun unmeasuredControllerKeepsItsSmallReserveInsteadOfPreloadingTheQueue() {
        val queue = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
        queue.add(slice(100))
        val policy = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        KokoroPcmBatches.awaitReserve(
            queue = queue,
            targetFrames = 24_000L * policy.bufferMs() / 1_000,
            acceptedFrames = { queue.sumOf { it.size.toLong() } },
            canWait = { true },
            producerPending = { true },
            deadlineReached = { false },
            poll = { error("The unmeasured controller already has its 100 ms reserve") }
        )
        assertEquals(1, queue.size)
        assertEquals(100, policy.bufferMs())
        assertEquals(5_900, VoiceAdaptivePlayback.MAX_RESERVE_MS)
    }

    @Test fun shortIdleReplyDoesNotWaitForMoreTextOrConsumeItsPcm() {
        val queue = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
        val tail = slice(20)
        queue.add(tail)
        KokoroPcmBatches.awaitReserve(
            queue = queue,
            targetFrames = 24_000,
            acceptedFrames = { tail.size.toLong() },
            canWait = { true },
            producerPending = { false },
            deadlineReached = { false },
            poll = { error("An idle text stream cannot fill the reserve") }
        )
        assertSame(tail, queue.single())
    }

    @Test fun cancellationReplacementAndSynthesisCompletionEachEndAnActiveWait() {
        for (reason in listOf("cancelled", "replaced", "completed")) {
            val queue = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
            queue.add(slice(20))
            val playback = Any()
            var active: Any? = playback
            var synthesisDone = false
            var polls = 0
            KokoroPcmBatches.awaitReserve(
                queue = queue,
                targetFrames = 24_000,
                acceptedFrames = { queue.sumOf { it.size.toLong() } },
                canWait = { active === playback && !synthesisDone },
                producerPending = { true },
                deadlineReached = { false },
                poll = {
                    check(++polls == 1) { "Priming continued after $reason" }
                    when (reason) {
                        "cancelled" -> active = null
                        "replaced" -> active = Any()
                        "completed" -> synthesisDone = true
                    }
                }
            )
            assertEquals(reason, 1, polls)
            assertEquals(reason, 1, queue.size)
        }
    }

    private fun mixedTailQueue(): ArrayBlockingQueue<VoicePcmSlice> =
        ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES).apply {
            // Five real phrase slices: eleven 100 ms views and one 20 ms tail each.
            repeat(5) {
                assertTrue(KokoroPcmBatches.slices(FloatArray(24_000 * 1_120 / 1_000), 24_000) { offer(it) })
            }
        }

    private fun slice(durationMs: Int): VoicePcmSlice {
        val samples = FloatArray(24_000 * durationMs / 1_000)
        return VoicePcmSlice(samples, 0, samples.size)
    }

    private fun measuredRefillPolicy(): VoiceAdaptivePlayback = VoiceAdaptivePlayback(VoicePerformanceMode.FAST).apply {
        observe(4_482, 1_120)
        repeat(7) { underrun() }
    }
}
