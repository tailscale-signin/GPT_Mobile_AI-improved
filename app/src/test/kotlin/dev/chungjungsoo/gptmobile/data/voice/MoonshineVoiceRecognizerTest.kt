package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineVoiceRecognizerTest {
    @Test fun closeDuringInferenceNeverFreesNativeStateUntilInferenceReturns() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val decoder = FakeDecoder {
            entered.countDown()
            assertTrue(resume.await(3, TimeUnit.SECONDS))
            events(LocalSpeechEvent.Final("stale result"))
        }
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            fixture.capture.chunks.add(FloatArray(1600))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            fixture.audio.close()
            assertFalse(decoder.closed.await(100, TimeUnit.MILLISECONDS))
            assertFalse(VoiceMicrophoneLease.acquire("other-session"))
            resume.countDown()
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertTrue(fixture.finals.isEmpty())
            assertEquals(1, fixture.releases.get())
            assertTrue(VoiceMicrophoneLease.acquire("other-session"))
            VoiceMicrophoneLease.release("other-session")
            assertEquals(1, decoder.threads.distinct().size)
        } finally {
            resume.countDown()
            fixture.close()
            VoiceMicrophoneLease.release("other-session")
        }
    }

    @Test fun missingModelDoesNotCreateDecoderOpenMicrophoneOrDownload() {
        val fixture = Fixture(FakeDecoder(), modelsPresent = false)
        try {
            fixture.audio.listen(fixture.listener)
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf(VoiceRecoveryAction.LOCAL_MODEL), fixture.repairs)
            assertEquals(1L, fixture.capture.started.count)
            assertEquals(0, fixture.releases.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun unsupportedLanguageAndMissingPermissionDoNotAcquireMicrophone() {
        val fixture = Fixture(FakeDecoder())
        try {
            fixture.audio.languageTag = "pt-PT"
            fixture.audio.listen(fixture.listener)
            assertEquals(listOf(VoiceRecoveryAction.LOCAL_MODEL), fixture.repairs)
            assertEquals(1L, fixture.capture.started.count)
        } finally {
            fixture.close()
        }
        val noPermission = Fixture(FakeDecoder(), permission = false)
        try {
            noPermission.audio.listen(noPermission.listener)
            assertEquals(listOf(VoiceRecoveryAction.MICROPHONE_PERMISSION), noPermission.repairs)
        } finally {
            noPermission.close()
        }
    }

    @Test fun finalTranscriptArrivesOnceAfterCaptureAndNativeResourcesHaveClosed() {
        val decoder = FakeDecoder {
            events(LocalSpeechEvent.Speech)
            events(LocalSpeechEvent.Partial("hello"))
            events(LocalSpeechEvent.Final("hello world"))
        }
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            fixture.capture.chunks.add(FloatArray(1600))
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf("hello world"), fixture.finals)
            assertTrue(fixture.capture.closed)
            assertEquals(1, fixture.releases.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun decoderCallbackFailureStopsCaptureWithoutThrowingAcrossTheCallback() {
        val decoder = FakeDecoder {
            events(LocalSpeechEvent.Speech)
            events(LocalSpeechEvent.Failure("MOONSHINE_CALLBACK_ERROR"))
        }
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            fixture.capture.chunks.add(FloatArray(1600))
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertTrue(fixture.finals.isEmpty())
            assertTrue(fixture.errors.single().contains("stopped unexpectedly"))
            assertTrue(fixture.capture.closed)
            assertEquals(1, fixture.releases.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun silenceDeadlineStopsCaptureAndNeverSendsAnEmptyTurn() {
        val decoder = FakeDecoder()
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            assertTrue(fixture.timersReady.await(3, TimeUnit.SECONDS))
            fixture.deadlines.first { it.first == 12_000L }.second.invoke()
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertTrue(fixture.finals.isEmpty())
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.retryableErrors.single().contains("No speech"))
            assertEquals(listOf("VOICE_NO_SPEECH"), fixture.codes)
            assertTrue(fixture.capture.closed)
        } finally {
            fixture.close()
        }
    }

    @Test fun permissionRevocationAfterPrecheckRequiresPermissionRepairAndReleasesCapture() {
        val failure = SecurityException("Private platform detail")
        val decoder = FakeDecoder()
        val fixture = Fixture(decoder, capture = FakeCapture(startFailure = failure))
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf(VoiceRecoveryAction.MICROPHONE_PERMISSION), fixture.repairs)
            assertEquals(listOf("VOICE_MICROPHONE_PERMISSION_REQUIRED"), fixture.codes)
            assertSame(failure, fixture.causes.single())
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.capture.closed)
            assertEquals(1, fixture.releases.get())
            assertTrue(VoiceMicrophoneLease.acquire("after-permission-failure"))
        } finally {
            fixture.close()
            VoiceMicrophoneLease.release("after-permission-failure")
        }
    }

    @Test fun missingNativeRuntimePreservesCauseAndOffersARepairInsteadOfAnAutomaticRetry() {
        val failure = UnsatisfiedLinkError("Private native library path")
        val fixture = Fixture(FakeDecoder(), decoderFactoryFailure = failure)
        try {
            fixture.audio.listen(fixture.listener)
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf(VoiceRecoveryAction.LOCAL_MODEL), fixture.repairs)
            assertEquals(listOf("VOICE_RECOGNIZER_UNAVAILABLE"), fixture.codes)
            assertSame(failure, fixture.causes.single())
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.retryableErrors.isEmpty())
            assertEquals(1L, fixture.capture.started.count)
            assertEquals(1, fixture.releases.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun nativeAllocationFailureIsReportedAndReleasesModelAndMicrophoneOwnership() {
        val failure = OutOfMemoryError("Private allocation detail")
        val fixture = Fixture(FakeDecoder(), decoderFactoryFailure = failure)
        try {
            fixture.audio.listen(fixture.listener)
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf(VoiceRecoveryAction.LOCAL_MODEL), fixture.repairs)
            assertEquals(listOf("VOICE_RECOGNIZER_MEMORY_PRESSURE"), fixture.codes)
            assertSame(failure, fixture.causes.single())
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.retryableErrors.isEmpty())
            assertEquals(1, fixture.releases.get())
            assertTrue(VoiceMicrophoneLease.acquire("after-memory-failure"))
        } finally {
            fixture.close()
            VoiceMicrophoneLease.release("after-memory-failure")
        }
    }

    @Test fun nativeFailureAfterSilenceDeadlineCannotBePromotedToAutomaticRetry() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = UnsatisfiedLinkError("Private runtime failure")
        val decoder = FakeDecoder {
            entered.countDown()
            assertTrue(resume.await(3, TimeUnit.SECONDS))
            throw failure
        }
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            assertTrue(fixture.timersReady.await(3, TimeUnit.SECONDS))
            fixture.capture.chunks.add(FloatArray(1600))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            fixture.deadlines.first { it.first == 12_000L }.second.invoke()
            resume.countDown()
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf("VOICE_RECOGNIZER_UNAVAILABLE"), fixture.codes)
            assertEquals(listOf(VoiceRecoveryAction.LOCAL_MODEL), fixture.repairs)
            assertSame(failure, fixture.causes.single())
            assertTrue(fixture.retryableErrors.isEmpty())
        } finally {
            resume.countDown()
            fixture.close()
        }
    }

    @Test fun silenceDeadlineCannotHideDecoderCleanupFailure() {
        val failure = IllegalStateException("Private release failure")
        val decoder = FakeDecoder(closeFailure = failure)
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.timersReady.await(3, TimeUnit.SECONDS))
            fixture.deadlines.first { it.first == 12_000L }.second.invoke()
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf("VOICE_RECOGNIZER_FAILED"), fixture.codes)
            assertTrue(fixture.errors.single().contains("releasing"))
            assertSame(failure, fixture.causes.single())
            assertTrue(fixture.retryableErrors.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun slowDecoderStopsAtTheBoundedQueueInsteadOfDroppingOrGrowingAudio() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val decoder = FakeDecoder {
            events(LocalSpeechEvent.Partial("Captured words"))
            entered.countDown()
            assertTrue(resume.await(3, TimeUnit.SECONDS))
        }
        val fixture = Fixture(decoder)
        try {
            fixture.audio.listen(fixture.listener)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            fixture.capture.chunks.add(FloatArray(1600))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(45) { fixture.capture.chunks.add(FloatArray(1600)) }
            assertTrue(fixture.capture.released.await(3, TimeUnit.SECONDS))
            resume.countDown()
            assertTrue(decoder.closed.await(3, TimeUnit.SECONDS))
            fixture.worker.shutdown()
            assertTrue(fixture.worker.awaitTermination(3, TimeUnit.SECONDS))
            fixture.drain()
            assertTrue(fixture.finals.isEmpty())
            assertTrue(fixture.errors.single().contains("could not keep up"))
            assertEquals(listOf("VOICE_AUDIO_QUEUE_OVERFLOW"), fixture.codes)
        } finally {
            resume.countDown()
            fixture.close()
        }
    }

    private class Fixture(
        decoder: FakeDecoder,
        modelsPresent: Boolean = true,
        permission: Boolean = true,
        val capture: FakeCapture = FakeCapture(),
        decoderFactoryFailure: Throwable? = null
    ) {
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val worker = Executors.newSingleThreadExecutor()
        val releases = AtomicInteger()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val retryableErrors = mutableListOf<String>()
        val repairs = mutableListOf<VoiceRecoveryAction>()
        val codes = mutableListOf<String>()
        val causes = mutableListOf<Throwable?>()
        val deadlines = ConcurrentLinkedQueue<Pair<Long, () -> Unit>>()
        val timersReady = CountDownLatch(2)
        val audio = MoonshineVoiceRecognizer(
            modelsReady = { modelsPresent },
            modelDirectory = { File("test-model") },
            releaseModel = {
                releases.incrementAndGet()
            },
            hasPermission = { permission },
            post = {
                callbacks.add(it)
            },
            schedule = { delay, action ->
                deadlines.add(delay to action)
                timersReady.countDown()
                val cancel: () -> Unit = {}
                cancel
            },
            decoderFactory = {
                decoderFactoryFailure?.let { throw it }
                decoder
            },
            captureFactory = { capture },
            worker = worker
        ).apply { languageTag = "en-CA" }
        val listener = object : VoiceAudio.Listener {
            override fun ready() = Unit
            override fun level(value: Float) = Unit
            override fun partial(text: String) = Unit
            override fun final(text: String) {
                finals += text
            }
            override fun error(message: String) {
                errors += message
            }
            override fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) {
                repairs += action
            }
            override fun retryableFailure(message: String, retryMillis: Long) {
                retryableErrors += message
            }
            override fun failure(message: String, code: String, retryMillis: Long, retryable: Boolean, recoveryAction: VoiceRecoveryAction?, cause: Throwable?) {
                codes += code
                causes += cause
                super.failure(message, code, retryMillis, retryable, recoveryAction, cause)
            }
        }

        fun drain() {
            while (true) (callbacks.poll() ?: break).invoke()
        }
        fun close() {
            audio.close()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class FakeDecoder(private val closeFailure: Throwable? = null, private val accept: FakeDecoder.() -> Unit = {}) : LocalSpeechDecoder {
        lateinit var events: (LocalSpeechEvent) -> Unit
        val threads = ConcurrentLinkedQueue<Thread>()
        val closed = CountDownLatch(1)
        override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) {
            threads.add(Thread.currentThread())
            this.events = events
        }
        override fun accept(samples: FloatArray) {
            threads.add(Thread.currentThread())
            accept.invoke(this)
        }
        override fun finish() {
            threads.add(Thread.currentThread())
        }
        override fun close() {
            threads.add(Thread.currentThread())
            closed.countDown()
            closeFailure?.let { throw it }
        }
    }

    private class FakeCapture(private val startFailure: Throwable? = null) : LocalVoiceCapture {
        val chunks = LinkedBlockingQueue<FloatArray>()
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)

        @Volatile var closed = false
        override fun start() {
            started.countDown()
            startFailure?.let { throw it }
        }
        override fun read(): FloatArray = chunks.poll(20, TimeUnit.MILLISECONDS) ?: FloatArray(0)
        override fun stop() = Unit
        override fun close() {
            closed = true
            released.countDown()
        }
    }
}
