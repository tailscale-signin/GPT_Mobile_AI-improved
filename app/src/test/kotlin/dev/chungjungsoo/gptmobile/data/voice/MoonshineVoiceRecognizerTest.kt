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
            assertTrue(fixture.errors.single().contains("No speech"))
            assertTrue(fixture.capture.closed)
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
        } finally {
            resume.countDown()
            fixture.close()
        }
    }

    private class Fixture(decoder: FakeDecoder, modelsPresent: Boolean = true, permission: Boolean = true) {
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val worker = Executors.newSingleThreadExecutor()
        val capture = FakeCapture()
        val releases = AtomicInteger()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val repairs = mutableListOf<VoiceRecoveryAction>()
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
            decoderFactory = { decoder },
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
        }

        fun drain() {
            while (true) (callbacks.poll() ?: break).invoke()
        }
        fun close() {
            audio.close()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class FakeDecoder(private val accept: FakeDecoder.() -> Unit = {}) : LocalSpeechDecoder {
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
        }
    }

    private class FakeCapture : LocalVoiceCapture {
        val chunks = LinkedBlockingQueue<FloatArray>()
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)

        @Volatile var closed = false
        override fun start() {
            started.countDown()
        }
        override fun read(): FloatArray = chunks.poll(20, TimeUnit.MILLISECONDS) ?: FloatArray(0)
        override fun stop() = Unit
        override fun close() {
            closed = true
            released.countDown()
        }
    }
}
