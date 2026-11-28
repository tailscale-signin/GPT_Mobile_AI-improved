package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeVoicePhraseEngineTest {
    @Test fun aTimedWordDoesNotReleaseTheFollowingAudioBeforeItsOwnMetadataArrives() {
        var reads = 0
        var emittedFrames = 0
        val spoken = mutableListOf<VoiceWordCue>()
        val stream = TestStream {
            when (++reads) {
                1 -> AudioChunk(FloatArray(9_600) { .2f }, wordTimestamps = listOf(WordTimestamp("one", 0, 3, 0f, .2f)))
                2 -> {
                    assertEquals(4_800, emittedFrames)
                    assertEquals(0, spoken.single().startByte)
                    AudioChunk(FloatArray(0), chunkIndex = 1, isFinal = true, wordTimestamps = listOf(WordTimestamp("two", 4, 7, .2f, .4f)))
                }
                else -> null
            }
        }
        val frames = VoiceStreamFrames("one two")
        engine(TestTask { stream }).use { engine ->
            engine.setCaptionAlignmentEnabled(true)
            engine.stream("one two", 0, 1f) { chunk ->
                frames.accept(chunk)
                spoken += chunk.wordCues
                emittedFrames += chunk.samples.size
                true
            }
        }
        frames.finish()
        assertEquals(9_600, emittedFrames)
        assertEquals(listOf(0, 4), spoken.map { it.startByte })
        assertEquals(listOf(4_800L, 9_600L), spoken.map { it.endFrame })
    }

    @Test fun trailingCaptionMetadataUsesTheSamePitchPreservingTempoTimelineAsItsPcm() {
        for (speed in listOf(.5f, 1.5f, 2f)) {
            var reads = 0
            val stream = TestStream {
                when (++reads) {
                    1 -> AudioChunk(FloatArray(4_800) { (.5 * kotlin.math.sin(2 * Math.PI * 160 * it / VOICE_PCM_SAMPLE_RATE)).toFloat() })
                    2 -> AudioChunk(FloatArray(0), chunkIndex = 1, isFinal = true, wordTimestamps = listOf(WordTimestamp("word", 0, 4, 0f, .2f)))
                    else -> null
                }
            }
            val frames = VoiceStreamFrames("word")
            val captions = VoicePcmCaptions()
            engine(TestTask { stream }).use { engine ->
                engine.setCaptionAlignmentEnabled(true)
                engine.stream("word", 0, speed) { chunk ->
                    captions.appendStream(0, "word", 0, frames.accept(chunk), chunk.samples.size)
                    true
                }
            }
            frames.finish()
            assertEquals((4_800 / speed).toLong(), frames.frames)
            assertEquals(0..3, captions.consumed(frames.frames / 2)?.range)
            assertEquals(IntRange.EMPTY, captions.consumed(frames.frames)?.range)
        }
    }

    @Test fun captionPreferenceSurvivesAcceleratorRecoveryAndCanBeDisabledForTheNextPlayback() {
        val accelerator = MediaPipeVoicePhraseEngine(
            TestTask { TestStream { error("Delegate unavailable") } },
            AutoCloseable {},
            VoiceSpeechBackend.LITERT_KOKORO_GPU,
            1f,
            "freya",
            allowDelegateRecovery = true
        )
        var reads = 0
        var callbacks = 0
        val cpu = MediaPipeVoicePhraseEngine(
            TestTask {
                reads = 0
                TestStream {
                    when (++reads) {
                        1 -> AudioChunk(FloatArray(4_800) { .2f })
                        2 -> AudioChunk(FloatArray(0), chunkIndex = 1, isFinal = true, wordTimestamps = listOf(WordTimestamp("word", 0, 4, 0f, .2f)))
                        else -> null
                    }
                }
            },
            AutoCloseable {},
            VoiceSpeechBackend.LITERT_KOKORO_CPU,
            1f,
            "freya"
        )
        MediaPipePlaybackRecovery(accelerator, "freya", {}, { cpu }, {}).use { recovery ->
            recovery.setCaptionAlignmentEnabled(true)
            recovery.stream("word", 0, 1f) {
                assertEquals(2, reads)
                callbacks++
                true
            }
            assertEquals(1, callbacks)
            recovery.resetCancellation()
            recovery.setCaptionAlignmentEnabled(false)
            callbacks = 0
            recovery.stream("word", 0, 1f) {
                if (callbacks++ == 0) assertEquals(1, reads)
                true
            }
        }
    }

    @Test fun enabledCaptionsHoldAudioUntilTheSdkReturnsItsTrailingTiming() {
        var reads = 0
        val emitted = mutableListOf<VoicePcmChunk>()
        val stream = TestStream {
            when (++reads) {
                1 -> AudioChunk(FloatArray(4_800) { .2f })
                2 -> {
                    assertTrue("Audio must wait for the next packet's timing", emitted.isEmpty())
                    AudioChunk(FloatArray(0), chunkIndex = 1, isFinal = true, wordTimestamps = listOf(WordTimestamp("word", 0, 4, 0f, .2f)))
                }
                else -> null
            }
        }
        val frames = VoiceStreamFrames("word")
        val captions = VoicePcmCaptions()
        engine(TestTask { stream }).use { engine ->
            engine.setCaptionAlignmentEnabled(true)
            engine.stream("word", 0, 1f) { chunk ->
                assertEquals(2, reads)
                captions.appendStream(0, "word", 0, frames.accept(chunk), chunk.samples.size)
                emitted += chunk
                true
            }
        }
        frames.finish()
        assertEquals(4_800, emitted.sumOf { it.samples.size })
        assertEquals(0..3, captions.consumed(1_200)?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(4_800)?.range)
        assertEquals(VoiceWordCueProvenance.ESTIMATED, emitted.single().wordCues.single().provenance)
    }

    @Test fun captionsWithoutSdkTimingKeepAudioIntactAndMakeProgressAtTheRetentionBound() {
        val sampleCount = MediaPipeCaptionBuffer.MAX_BUFFERED_FRAMES + 1
        var reads = 0
        var emittedFrames = 0
        val stream = TestStream {
            if (++reads == 1) AudioChunk(FloatArray(sampleCount) { .2f }, isFinal = true) else null
        }
        engine(TestTask { stream }).use { engine ->
            engine.setCaptionAlignmentEnabled(true)
            engine.stream("word", 0, 1f) { chunk ->
                if (emittedFrames == 0) assertEquals(1, reads)
                assertTrue(chunk.wordCues.isEmpty())
                assertTrue(chunk.samples.all { it == .2f })
                emittedFrames += chunk.samples.size
                true
            }
        }
        assertEquals(sampleCount, emittedFrames)
    }

    @Test fun cancellationDiscardsCaptionAudioWaitingForAnSdkTimestamp() {
        val waitingForTiming = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var reads = 0
        val stream = object : MediaPipeSpeechStream {
            override fun readNext(): AudioChunk? {
                if (++reads == 1) return AudioChunk(FloatArray(4_800) { .2f })
                waitingForTiming.countDown()
                check(cancelled.await(3, TimeUnit.SECONDS))
                throw CancellationException("Native stream cancelled")
            }
            override fun cancel() = cancelled.countDown()
            override fun close() = Unit
        }
        val engine = engine(TestTask { stream })
        engine.setCaptionAlignmentEnabled(true)
        val worker = Executors.newSingleThreadExecutor()
        val emitted = AtomicInteger()
        try {
            val operation = worker.submit<Throwable?> {
                runCatching {
                    engine.stream("word", 0, 1f) {
                        emitted.incrementAndGet()
                        true
                    }
                }.exceptionOrNull()
            }
            assertTrue(waitingForTiming.await(3, TimeUnit.SECONDS))
            engine.cancel()
            assertTrue(operation.get(3, TimeUnit.SECONDS) is CancellationException)
            assertEquals(0, emitted.get())
            engine.close()
        } finally {
            cancelled.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun firstOwnedChunkIsEmittedBeforeTheNextNativeRead() {
        val first = floatArrayOf(.1f, .2f)
        var reads = 0
        val stream = TestStream {
            when (++reads) {
                1 -> AudioChunk(first)
                2 -> {
                    first[0] = .9f
                    AudioChunk(floatArrayOf(.3f), chunkIndex = 1, isFinal = true)
                }
                else -> null
            }
        }
        val engine = engine(TestTask { stream })
        val emitted = mutableListOf<VoicePcmChunk>()
        engine.stream("test", 0, 1f) {
            if (emitted.isEmpty()) assertEquals(1, reads)
            emitted += it
            true
        }
        assertArrayEquals(floatArrayOf(.1f, .2f), emitted.first().samples, 0f)
        assertEquals(1, stream.closes.get())
        engine.close()
    }

    @Test fun silentFirstInferenceUsesThePublicPhraseWithoutPublishingPlaybackEvidence() {
        var receipts = 0
        val task = TestTask {
            var read = false
            TestStream { if (read) null else AudioChunk(floatArrayOf(.2f), isFinal = true).also { read = true } }
        }
        engine(task).use { engine ->
            engine.onValidatedSynthesis = { _, _ -> receipts++ }
            assertTrue(engine.warmFirstInference(0, 1f))
            assertEquals(0, receipts)
            engine.stream("word", 0, 1f) { true }
            assertEquals(1, receipts)
        }
    }

    @Test fun nativeClausesAreSplitIntoBoundedSlicesWithoutDuplicatingCues() {
        val native = AudioChunk(
            FloatArray(VOICE_PCM_SAMPLE_RATE * 7) { .1f },
            isFinal = true,
            wordTimestamps = listOf(WordTimestamp("word", 0, 4, 0f, 1f))
        )
        var read = false
        val engine = engine(TestTask { TestStream { if (read) null else native.also { read = true } } })
        val chunks = mutableListOf<VoicePcmChunk>()
        engine.stream("word", 0, 1f) {
            chunks += it
            true
        }
        assertEquals(7, chunks.size)
        assertTrue(chunks.all { it.samples.size == VOICE_PCM_SAMPLE_RATE })
        assertEquals(1, chunks.sumOf { it.wordCues.size })
        assertEquals(VoiceWordCueProvenance.ESTIMATED, chunks.first().wordCues.single().provenance)
        engine.close()
    }

    @Test fun playbackCrossesALongWordBeforeTheNextSdkPacketAndClearsOnTheLastAudibleFrame() {
        val captions = VoicePcmCaptions()
        var reads = 0
        val engine = engine(
            TestTask {
                TestStream {
                    when (++reads) {
                        1 -> AudioChunk(FloatArray(6_000) { .1f }, wordTimestamps = listOf(WordTimestamp("word", 0, 4, 0f, 1f)))
                        2 -> {
                            // The SDK may spend longer synthesizing the tail than the audio already queued.
                            assertEquals(0..3, captions.consumed(1_200)?.range)
                            assertEquals(6_000L, captions.active()?.endFrame)
                            AudioChunk(FloatArray(3_000) { .1f }, chunkIndex = 1, isFinal = true)
                        }
                        else -> null
                    }
                }
            }
        )
        val source = VoiceStreamFrames("word", cueLimit = 1)
        val stream = VoiceStreamPause(source, VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, 100))
        engine.setCaptionAlignmentEnabled(true)
        engine.stream("word", 0, 1f) { chunk ->
            val packet = stream.accept(chunk)
            captions.appendStream(0, "word", 0, packet.cues, packet.samples.size)
            true
        }
        stream.finish()
        assertEquals(0..3, captions.consumed(8_999)?.range)
        assertEquals(9_000L, captions.active()?.endFrame)
        assertEquals(IntRange.EMPTY, captions.consumed(9_000)?.range)
        engine.close()
    }

    @Test fun callbackCancellationStopsNativePullsAndClosesOnlyTheStream() {
        val stream = TestStream { AudioChunk(floatArrayOf(.1f)) }
        val task = TestTask { stream }
        val released = AtomicBoolean()
        val engine = engine(task) { released.set(true) }
        assertThrows(CancellationException::class.java) { engine.stream("test", 0, 1f) { false } }
        assertTrue(stream.cancels.get() > 0)
        assertEquals(1, stream.closes.get())
        assertFalse(task.closed.get())
        assertFalse(released.get())
        engine.close()
        assertTrue(task.closed.get())
        assertTrue(released.get())
    }

    @Test fun cancelInterruptsABlockedNativeRead() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val stream = object : MediaPipeSpeechStream {
            override fun readNext(): AudioChunk? {
                entered.countDown()
                check(cancelled.await(3, TimeUnit.SECONDS))
                throw IllegalStateException("Native cancellation")
            }
            override fun cancel() = cancelled.countDown()
            override fun close() = Unit
        }
        val engine = engine(TestTask { stream })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<Throwable?> { runCatching { engine.stream("test", 0, 1f) { true } }.exceptionOrNull() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(VoiceSpeechStage.SDK_STREAM_CALL, engine.executionProgress?.stage)
            engine.cancel()
            assertTrue(operation.get(3, TimeUnit.SECONDS) is CancellationException)
            assertEquals(null, engine.executionProgress)
            assertTrue(engine.sdkCancelToReadReturnMs != null)
            engine.close()
        } finally {
            cancelled.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun closeWaitsForCallbackRetirementBeforeDestroyingTaskOrModelLease() {
        val entered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val cancelCalled = CountDownLatch(1)
        val stream = object : MediaPipeSpeechStream {
            override fun readNext() = AudioChunk(floatArrayOf(.1f))
            override fun cancel() = cancelCalled.countDown()
            override fun close() = Unit
        }
        val task = TestTask { stream }
        val leaseReleased = AtomicBoolean()
        val engine = engine(task) { leaseReleased.set(true) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val operation = workers.submit<Throwable?> {
                runCatching {
                    engine.stream("test", 0, 1f) {
                        entered.countDown()
                        check(releaseCallback.await(3, TimeUnit.SECONDS))
                        true
                    }
                }.exceptionOrNull()
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(null, engine.executionProgress)
            val closing = workers.submit { engine.close() }
            assertTrue(cancelCalled.await(3, TimeUnit.SECONDS))
            assertFalse(task.closed.get())
            assertFalse(leaseReleased.get())
            assertFalse(closing.isDone)
            releaseCallback.countDown()
            assertTrue(operation.get(3, TimeUnit.SECONDS) is CancellationException)
            closing.get(3, TimeUnit.SECONDS)
            assertTrue(task.closed.get())
            assertTrue(leaseReleased.get())
        } finally {
            releaseCallback.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun evidenceNamesTheVoiceAndConfiguredDelegateOnlyAfterSuccessfulSynthesis() {
        val ids = mutableListOf<Pair<String, VoiceSpeechBackend>>()
        var delivered = false
        val stream = TestStream { if (delivered) null else AudioChunk(floatArrayOf(.1f)).also { delivered = true } }
        val engine = engine(TestTask { stream })
        engine.onValidatedSynthesis = { id, configured -> ids += id to configured }
        assertTrue(ids.isEmpty())
        engine.stream("test", 0, 1f) { true }
        assertEquals(listOf("freya" to VoiceSpeechBackend.PHONON_CPU), ids)
        assertNull(engine.executionEvidence)
        engine.close()
    }

    @Test fun invalidAudioAndEmptyStreamsFailWithoutLeakingNativeStreams() {
        val cases = listOf(
            AudioChunk(floatArrayOf(Float.NaN)),
            AudioChunk(floatArrayOf(Float.POSITIVE_INFINITY)),
            AudioChunk(floatArrayOf(.1f), sampleRateHz = 22_050),
            AudioChunk(floatArrayOf(0f)),
            AudioChunk(FloatArray(0))
        )
        cases.forEach { invalid ->
            var delivered = false
            val stream = TestStream { if (delivered) null else invalid.also { delivered = true } }
            val engine = engine(TestTask { stream })
            var reported = false
            engine.onValidatedSynthesis = { _, _ -> reported = true }
            assertThrows(IllegalArgumentException::class.java) { engine.stream("test", 0, 1f) { true } }
            assertEquals(1, stream.closes.get())
            assertFalse(reported)
            engine.close()
        }
    }

    @Test fun tempoChangesReuseThePreparedTaskAndDelegateIsOnlyARequest() {
        val samples = FloatArray(24_000) { (.5 * kotlin.math.sin(2 * Math.PI * 160 * it / 24_000)).toFloat() }
        val task = TestTask {
            var read = false
            TestStream { if (read) null else AudioChunk(samples, isFinal = true).also { read = true } }
        }
        val engine = engine(task)
        for ((rate, expected) in listOf(1f to 24_000, 1.5f to 16_000, .5f to 48_000, 2f to 12_000)) {
            var delivered = 0
            engine.stream("test", 0, rate) {
                delivered += it.samples.size
                true
            }
            assertEquals(expected, delivered)
        }
        assertEquals(4, task.opens.get())
        assertEquals(0, task.closes.get())
        assertEquals("mediapipe-phonon-cpu-requested", engine.executionProvider)
        assertNull(engine.executionEvidence)
        engine.close()
    }

    @Test fun cancelBeforeStartRequiresResetBeforeASeparateStreamCanOpen() {
        var read = false
        val task = TestTask { TestStream { if (read) null else AudioChunk(floatArrayOf(.1f)).also { read = true } } }
        val engine = engine(task)
        engine.cancel()
        assertThrows(CancellationException::class.java) { engine.stream("test", 0, 1f) { true } }
        assertEquals(0, task.opens.get())
        engine.resetCancellation()
        engine.stream("test", 0, 1f) { true }
        assertEquals(1, task.opens.get())
        engine.close()
        engine.close()
        assertEquals(1, task.closes.get())
    }

    @Test fun failedNativeDestructionDoesNotReleaseThePossiblyMappedModel() {
        val released = AtomicBoolean()
        val task = object : MediaPipeSpeechTask {
            override fun voiceIds() = setOf("freya")
            override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("Unused")
            override fun cancel() = Unit
            override fun close(): Unit = error("Native destruction failed")
        }
        val engine = engine(task) { released.set(true) }
        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertFalse(released.get())
    }

    @Test fun failedStreamDestructionRetainsItsParentTaskAndModelLease() {
        var read = false
        val stream = object : MediaPipeSpeechStream {
            override fun readNext(): AudioChunk? = if (read) null else AudioChunk(floatArrayOf(.1f)).also { read = true }
            override fun cancel() = Unit
            override fun close(): Unit = error("Stream destruction failed")
        }
        val task = TestTask { stream }
        val released = AtomicBoolean()
        val engine = engine(task) { released.set(true) }
        assertThrows(IllegalStateException::class.java) { engine.stream("test", 0, 1f) { true } }
        assertThrows(IllegalStateException::class.java) { engine.resetCancellation() }
        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertFalse(task.closed.get())
        assertFalse(released.get())
    }

    @Test fun cancellationDoesNotWaitForAStreamDestructorOrTouchItsRetiringHandle() {
        val destructorEntered = CountDownLatch(1)
        val releaseDestructor = CountDownLatch(1)
        val lateCancel = AtomicBoolean()
        var read = false
        val stream = object : MediaPipeSpeechStream {
            override fun readNext(): AudioChunk? = if (read) null else AudioChunk(floatArrayOf(.1f)).also { read = true }
            override fun cancel() {
                if (destructorEntered.count == 0L) lateCancel.set(true)
            }
            override fun close() {
                destructorEntered.countDown()
                check(releaseDestructor.await(3, TimeUnit.SECONDS))
            }
        }
        val engine = engine(TestTask { stream })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val synthesis = workers.submit { engine.stream("test", 0, 1f) { true } }
            assertTrue(destructorEntered.await(3, TimeUnit.SECONDS))
            workers.submit { engine.cancel() }.get(1, TimeUnit.SECONDS)
            assertFalse(lateCancel.get())
            releaseDestructor.countDown()
            synthesis.get(3, TimeUnit.SECONDS)
            engine.close()
        } finally {
            releaseDestructor.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun cancellationDoesNotWaitForATaskDestructorAndLeaseStaysHeld() {
        val destructorEntered = CountDownLatch(1)
        val releaseDestructor = CountDownLatch(1)
        val released = AtomicBoolean()
        val lateCancel = AtomicBoolean()
        val task = object : MediaPipeSpeechTask {
            override fun voiceIds() = setOf("freya")
            override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("Unused")
            override fun cancel() {
                lateCancel.set(true)
            }
            override fun close() {
                destructorEntered.countDown()
                check(releaseDestructor.await(3, TimeUnit.SECONDS))
            }
        }
        val engine = engine(task) { released.set(true) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val closing = workers.submit { engine.close() }
            assertTrue(destructorEntered.await(3, TimeUnit.SECONDS))
            workers.submit { engine.cancel() }.get(1, TimeUnit.SECONDS)
            assertFalse(lateCancel.get())
            assertFalse(released.get())
            releaseDestructor.countDown()
            closing.get(3, TimeUnit.SECONDS)
            assertTrue(released.get())
        } finally {
            releaseDestructor.countDown()
            workers.shutdownNow()
        }
    }

    private fun engine(task: MediaPipeSpeechTask, release: () -> Unit = {}) = MediaPipeVoicePhraseEngine(
        task,
        AutoCloseable(release),
        VoiceSpeechBackend.PHONON_CPU,
        1f,
        "freya"
    )

    private class TestTask(private val createStream: () -> MediaPipeSpeechStream) : MediaPipeSpeechTask {
        val closed = AtomicBoolean()
        val closes = AtomicInteger()
        val opens = AtomicInteger()
        override fun voiceIds() = setOf("freya")
        override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream {
            opens.incrementAndGet()
            return createStream()
        }
        override fun cancel() = Unit
        override fun close() {
            closed.set(true)
            closes.incrementAndGet()
        }
    }

    private class TestStream(private val read: () -> AudioChunk?) : MediaPipeSpeechStream {
        val cancels = AtomicInteger()
        val closes = AtomicInteger()
        override fun readNext(): AudioChunk? = read()
        override fun cancel() {
            cancels.incrementAndGet()
        }
        override fun close() {
            closes.incrementAndGet()
        }
    }
}
