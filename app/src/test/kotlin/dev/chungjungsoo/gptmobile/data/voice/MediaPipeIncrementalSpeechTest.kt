package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeIncrementalSpeechTest {
    @Test fun textWorkerPreservesCapturedPrivacyAndNormalFinishRetiresBeforeClose() {
        val finishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : IncrementalStream() {
            override fun finishInput() {
                super.finishInput()
                finishing.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
        }
        val engine = engine(Task(stream))
        val privacy = ConversationPrivacyContext(71, true)
        val worker = Executors.newSingleThreadExecutor()
        var input = false
        try {
            val operation = worker.submit {
                ConversationPrivacyContext.withCaptured(privacy) {
                    engine.streamInput({
                        assertEquals(privacy, ConversationPrivacyContext.capture())
                        if (input) null else "word".also { input = true }
                    }, 0, 1f) { true }
                }
            }
            assertTrue(finishing.await(3, TimeUnit.SECONDS))
            assertFalse(operation.isDone)
            assertEquals(0, stream.closes.get())
            release.countDown()
            operation.get(3, TimeUnit.SECONDS)
            assertEquals(1, stream.finishes.get())
            assertEquals(1, stream.closes.get())
            engine.close()
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun oneStreamDeliversAudioWhileLaterTextIsStillArrivingAndFinishesOnce() {
        val firstAudio = CountDownLatch(1)
        val stream = IncrementalStream()
        val task = Task(stream)
        val engine = engine(task)
        var input = 0
        val output = mutableListOf<Float>()
        engine.streamInput({
            when (input++) {
                0 -> "Hello "
                1 -> {
                    assertTrue(firstAudio.await(3, TimeUnit.SECONDS))
                    assertEquals(0, stream.finishes.get())
                    "world."
                }
                else -> null
            }
        }, 0, 1f) {
            output += it.samples.toList()
            firstAudio.countDown()
            true
        }
        assertEquals(listOf("Hello ", "world."), stream.inputs)
        assertEquals(listOf(.1f, .1f), output)
        assertEquals(1, task.opens.get())
        assertEquals(1, stream.finishes.get())
        assertEquals(1, stream.closes.get())
        assertEquals(0, task.closes.get())
        engine.close()
        assertEquals(1, task.closes.get())
    }

    @Test fun cancellationInterruptsPendingTextAndRetiresItBeforeDestroyingTheStream() {
        val waiting = CountDownLatch(1)
        val inputRetired = CountDownLatch(1)
        val stream = object : IncrementalStream() {
            override fun close() {
                assertEquals(0L, inputRetired.count)
                super.close()
            }
        }
        val engine = engine(Task(stream))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<Throwable?> {
                runCatching {
                    engine.streamInput({
                        waiting.countDown()
                        try {
                            CountDownLatch(1).await()
                            null
                        } finally {
                            inputRetired.countDown()
                        }
                    }, 0, 1f) { true }
                }.exceptionOrNull()
            }
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            engine.cancel()
            assertTrue(operation.get(3, TimeUnit.SECONDS) is CancellationException)
            assertEquals(1, stream.closes.get())
            assertEquals(0, stream.finishes.get())
            engine.close()
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun nativePushMustRetireBeforeModelLeaseIsReleased() {
        val pushing = CountDownLatch(1)
        val cancel = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val stream = object : IncrementalStream() {
            override fun pushText(text: String) {
                pushing.countDown()
                try {
                    // Represents a native call that does not respond to Java interruption.
                    while (cancel.count != 0L) runCatching { cancel.await(20, TimeUnit.MILLISECONDS) }
                } finally {
                    retired.countDown()
                }
            }
            override fun cancel() {
                cancel.countDown()
                super.cancel()
            }
            override fun close() {
                assertEquals(0L, retired.count)
                super.close()
            }
        }
        val task = Task(stream)
        val released = AtomicInteger()
        val engine = engine(task) { released.incrementAndGet() }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val operation = workers.submit<Throwable?> { runCatching { engine.streamInput({ "word" }, 0, 1f) { true } }.exceptionOrNull() }
            assertTrue(pushing.await(3, TimeUnit.SECONDS))
            workers.submit { engine.close() }.get(3, TimeUnit.SECONDS)
            assertTrue(operation.get(3, TimeUnit.SECONDS) is CancellationException)
            assertEquals(1, released.get())
            assertEquals(1, task.closes.get())
        } finally {
            cancel.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun oversizedInputIsRejectedBeforeNativePushAndNeverTruncated() {
        val stream = IncrementalStream()
        val engine = engine(Task(stream))
        assertThrows(IllegalArgumentException::class.java) {
            engine.streamInput({ "x".repeat(VoiceIncrementalStreamLimits.MAX_INPUT_BYTES + 1) }, 0, 1f) { true }
        }
        assertTrue(stream.inputs.isEmpty())
        assertEquals(1, stream.closes.get())
        engine.close()
    }

    @Test fun perUtteranceVoiceSelectionReusesTheSameTaskAndRejectsUnknownVoices() {
        val voices = mutableListOf<String>()
        val closes = AtomicInteger()
        val task = object : MediaPipeSpeechTask {
            override fun voiceIds() = setOf("freya", "claire")
            override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream {
                voices += voiceId
                var read = false
                return object : MediaPipeSpeechStream {
                    override fun readNext(): AudioChunk? = if (read) null else AudioChunk(floatArrayOf(.1f)).also { read = true }
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            override fun cancel() = Unit
            override fun close() {
                closes.incrementAndGet()
            }
        }
        val engine = engine(task)
        engine.stream("first", 0, 1f) { true }
        engine.selectVoice("claire")
        engine.stream("second", 0, 1f) { true }
        assertThrows(IllegalArgumentException::class.java) { engine.selectVoice("missing") }
        assertEquals(listOf("freya", "claire"), voices)
        assertEquals(0, closes.get())
        engine.close()
        assertEquals(1, closes.get())
    }

    @Test fun explicitPhraseExperimentRetainsLegacyStreamingAndRejectsIncrementalCalls() {
        val stream = IncrementalStream()
        val engine = MediaPipeVoicePhraseEngine(Task(stream), AutoCloseable {}, VoiceSpeechBackend.PHONON_CPU, 1f, "freya", false)
        assertFalse(engine.supportsIncrementalInput)
        assertThrows(IllegalStateException::class.java) { engine.streamInput({ "word" }, 0, 1f) { true } }
        engine.close()
    }

    private fun engine(task: MediaPipeSpeechTask, release: () -> Unit = {}) =
        MediaPipeVoicePhraseEngine(task, AutoCloseable(release), VoiceSpeechBackend.PHONON_CPU, 1f, "freya")

    private class Task(private val stream: IncrementalStream) : MediaPipeSpeechTask {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        override fun voiceIds() = setOf("freya")
        override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("A persistent reply must use unsealed input")
        override fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream {
            assertEquals("freya", voiceId)
            opens.incrementAndGet()
            return stream
        }
        override fun cancel() = stream.cancel()
        override fun close() {
            closes.incrementAndGet()
        }
    }

    private open class IncrementalStream : MediaPipeSpeechStream {
        private val queue = LinkedBlockingQueue<Any>()
        private val end = Any()
        val inputs = CopyOnWriteArrayList<String>()
        val finishes = AtomicInteger()
        val closes = AtomicInteger()
        override fun pushText(text: String) {
            inputs += text
            queue.put(AudioChunk(floatArrayOf(.1f)))
        }
        override fun finishInput() {
            finishes.incrementAndGet()
            queue.put(end)
        }
        override fun readNext(): AudioChunk? = queue.poll(3, TimeUnit.SECONDS).let { if (it === end) null else it as AudioChunk }
        override fun cancel() {
            queue.clear()
            queue.offer(end)
        }
        override fun close() {
            closes.incrementAndGet()
        }
    }
}
