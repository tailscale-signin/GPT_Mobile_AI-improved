package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineResidentModelTest {
    @Test fun twoTurnsRetainOneModelLeaseWithFreshStreamsAndReleaseItBeforeRemovalCallback() {
        val fixture = Fixture()
        try {
            repeat(2) { turn ->
                fixture.audio.captureBatchMs = if (turn == 0) 40 else 20
                fixture.audio.listen(fixture.listener)
                fixture.awaitCapture(turn)
                fixture.audio.finishListening()
                fixture.quiesce()
            }
            assertEquals(listOf("turn 1", "turn 2"), fixture.finals)
            assertEquals(1, fixture.modelsCreated.get())
            assertEquals(1, fixture.leases.get())
            assertEquals(2, fixture.decoder.streamReleases.get())
            assertEquals(0, fixture.decoder.modelCloses.get())
            assertEquals(listOf(40, 20), fixture.captures.map { it.batchMs })
            var callbackLeaseCount: Int? = null
            fixture.audio.releaseWarmModel { callbackLeaseCount = fixture.leases.get() }
            fixture.quiesce()
            assertEquals(0, callbackLeaseCount)
            assertEquals(1, fixture.decoder.modelCloses.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun finalizedTextAndMicrophoneReleasePrecedeSlowNativeCleanup() {
        val cleanupEntered = CountDownLatch(1)
        val cleanupResume = CountDownLatch(1)
        val fixture = Fixture(
            Decoder(reuse = false, beforeClose = {
                cleanupEntered.countDown()
                assertTrue(cleanupResume.await(3, TimeUnit.SECONDS))
            })
        )
        val probe = "next-microphone-${System.nanoTime()}"
        try {
            fixture.audio.listen(fixture.listener)
            fixture.awaitCapture(0)
            fixture.audio.finishListening()
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
            fixture.drain()
            assertEquals(listOf("turn 1"), fixture.finals)
            assertTrue(fixture.captures.first().closed)
            assertTrue(VoiceMicrophoneLease.acquire(probe))
            assertEquals(0, fixture.decoder.modelCloses.get())
            cleanupResume.countDown()
            fixture.quiesce()
            assertEquals(listOf("turn 1"), fixture.finals)
            assertEquals(1, fixture.decoder.modelCloses.get())
        } finally {
            VoiceMicrophoneLease.release(probe)
            cleanupResume.countDown()
            fixture.close()
        }
    }

    @Test fun doneSpeakingFlushesThePartialCaptureBatchBeforeNativeFinalization() {
        val fixture = Fixture(pending = FloatArray(77) { .1f })
        try {
            fixture.audio.listen(fixture.listener)
            fixture.awaitCapture(0)
            fixture.audio.finishListening()
            fixture.quiesce()
            assertEquals(listOf(77), fixture.decoder.accepted.toList())
            assertEquals(listOf("turn 1"), fixture.finals)
            assertFalse(fixture.captures.first().pending.isNotEmpty())
        } finally {
            fixture.close()
        }
    }

    private class Fixture(val decoder: Decoder = Decoder(), private val pending: FloatArray = FloatArray(0)) {
        val worker = Executors.newSingleThreadExecutor()
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val captures = ConcurrentLinkedQueue<Capture>()
        val modelsCreated = AtomicInteger()
        val leases = AtomicInteger()
        val finals = mutableListOf<String>()
        val audio = MoonshineVoiceRecognizer(
            modelsReady = {
                leases.incrementAndGet()
                true
            },
            modelDirectory = { File("model") },
            releaseModel = { leases.decrementAndGet() },
            hasPermission = { true },
            post = { callbacks.add(it) },
            schedule = { _, _ -> {} },
            decoderFactory = {
                modelsCreated.incrementAndGet()
                decoder
            },
            captureFactory = { Capture(pending).also { captures.add(it) } },
            worker = worker
        ).apply { languageTag = "en-US" }
        val listener = object : VoiceAudio.Listener {
            override fun ready() = Unit
            override fun level(value: Float) = Unit
            override fun partial(text: String) = Unit
            override fun final(text: String) {
                finals += text
            }
            override fun error(message: String): Unit = throw AssertionError(message)
        }
        fun awaitCapture(index: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (captures.size <= index && System.nanoTime() < deadline) Thread.yield()
            assertTrue(captures.elementAt(index).started.await(3, TimeUnit.SECONDS))
        }
        fun drain() {
            while (true) (callbacks.poll() ?: break).invoke()
        }
        fun quiesce() {
            worker.submit {}.get(3, TimeUnit.SECONDS)
            drain()
        }
        fun close() {
            audio.close()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class Decoder(private val reuse: Boolean = true, private val beforeClose: () -> Unit = {}) : LocalSpeechDecoder {
        override val supportsReuse get() = reuse
        private lateinit var events: (LocalSpeechEvent) -> Unit
        private var turn = 0
        val streamReleases = AtomicInteger()
        val modelCloses = AtomicInteger()
        val accepted = ConcurrentLinkedQueue<Int>()
        override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) {
            this.events = events
            turn++
        }
        override fun accept(samples: FloatArray) {
            accepted.add(samples.size)
        }
        override fun finish() {
            events(LocalSpeechEvent.Final("turn $turn"))
        }
        override fun releaseStream() {
            streamReleases.incrementAndGet()
        }
        override fun close() {
            beforeClose()
            modelCloses.incrementAndGet()
        }
    }

    private class Capture(var pending: FloatArray) : LocalVoiceCapture {
        val started = CountDownLatch(1)
        private val stopped = CountDownLatch(1)

        @Volatile var closed = false
        var batchMs = 0
        override fun configure(batchMs: Int) {
            this.batchMs = batchMs
        }
        override fun start() {
            started.countDown()
        }
        override fun read(): FloatArray {
            stopped.await(20, TimeUnit.MILLISECONDS)
            return FloatArray(0)
        }
        override fun stop() {
            stopped.countDown()
        }
        override fun drainPending() = pending.also { pending = FloatArray(0) }
        override fun close() {
            closed = true
        }
    }
}
