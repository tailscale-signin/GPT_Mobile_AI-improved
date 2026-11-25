package dev.chungjungsoo.gptmobile.data.voice

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real JNI/checkpoint smoke. Push the exact catalog model to
 * /data/local/tmp/mediapipe-speech/<fileName> and pass mediapipeSmokeEngine=phonon,
 * kokoro or pocket. A supplied argument makes missing/bad weights a failure.
 * On a compatible phone, set mediapipeSmokeDelegate to GPU or NPU for Kokoro.
 * The receipt identifies the request. Kernel placement needs separate device evidence.
 */
@RunWith(AndroidJUnit4::class)
class MediaPipeSpeechNativeSmokeTest {
    @Test(timeout = 300_000)
    fun pinnedCheckpointStreamsAndCancelsThroughThePackagedAndroidJni() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val argument = InstrumentationRegistry.getArguments().getString("mediapipeSmokeEngine")
        assumeTrue("Provision a catalog checkpoint and set mediapipeSmokeEngine for this explicit native smoke.", argument != null)
        val runId = InstrumentationRegistry.getArguments().getString("mediapipeSmokeRunId")?.also {
            require(it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        }
        val kind = MediaPipeSpeechEngine.entries.single { it.name.equals(argument, ignoreCase = true) }
        val delegate = InstrumentationRegistry.getArguments().getString("mediapipeSmokeDelegate")?.let(Delegate::valueOf) ?: Delegate.CPU
        require(delegate == Delegate.CPU || kind == MediaPipeSpeechEngine.KOKORO) { "Accelerator requests apply to Kokoro only." }
        val backend = when (kind) {
            MediaPipeSpeechEngine.KOKORO -> when (delegate) {
                Delegate.CPU -> VoiceSpeechBackend.LITERT_KOKORO_CPU
                Delegate.GPU -> VoiceSpeechBackend.LITERT_KOKORO_GPU
                Delegate.NPU -> VoiceSpeechBackend.LITERT_KOKORO_NPU
                else -> error("Unsupported speech delegate")
            }
            MediaPipeSpeechEngine.PHONON -> VoiceSpeechBackend.PHONON_CPU
            MediaPipeSpeechEngine.POCKET -> VoiceSpeechBackend.POCKET_CPU
        }
        val descriptor = MediaPipeSpeechCatalog.model(kind)
        val context = instrumentation.targetContext
        val evidenceFile = File(context.filesDir, "mediapipe-smoke-${kind.name.lowercase()}.json")
        evidenceFile.delete()
        val directory = File(context.cacheDir, "mediapipe-native-smoke/${descriptor.id}").also { check(it.mkdirs() || it.isDirectory) }
        val model = File(directory, descriptor.fileName)
        val expectedBytes = descriptor.downloadBytes
        assertTrue("Insufficient space for model and runtime caches", directory.usableSpace >= expectedBytes * 4)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        val staged = File(directory, descriptor.fileName + ".part")
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/mediapipe-speech/${descriptor.fileName}")
        ).use { input ->
            staged.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    assertTrue("Provisioned model exceeds its pinned size", copied <= expectedBytes)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        assertEquals(expectedBytes, copied)
        assertEquals(descriptor.sha256, digest.digest().joinToString("") { "%02x".format(it) })
        check(staged.renameTo(model))
        val openedAt = SystemClock.elapsedRealtime()
        val cache = File(directory, "runtime-cache").also { check(it.mkdirs() || it.isDirectory) }
        if (delegate == Delegate.NPU) prepareMediaPipeSpeechNpu(context)
        val task = ObservedNativeTask(AndroidMediaPipeSpeechTask(MediaPipeSpeechTaskConfiguration(model, cache, delegate, 1f, descriptor.defaultVoice)))
        val prepareMs = SystemClock.elapsedRealtime() - openedAt
        val engine = MediaPipeVoicePhraseEngine(task, AutoCloseable {}, backend, 1f, descriptor.defaultVoice)
        var evidence: JSONObject? = null
        try {
            assertTrue(descriptor.defaultVoice in task.voiceIds())
            if (kind == MediaPipeSpeechEngine.KOKORO) assertTrue(task.voiceIds().containsAll(setOf("af_heart", "am_adam", "bf_emma")))
            val frames = VoiceStreamFrames(FIXTURE)
            val started = SystemClock.elapsedRealtime()
            var firstPcmMs: Long? = null
            var chunks = 0
            var unicodeCue = false
            engine.stream(FIXTURE, 0, 1f) { chunk ->
                chunks++
                if (chunk.samples.isNotEmpty() && firstPcmMs == null) firstPcmMs = SystemClock.elapsedRealtime() - started
                frames.accept(chunk).forEach { cue ->
                    assertEquals(VoiceWordCueProvenance.ESTIMATED, cue.provenance)
                    unicodeCue = unicodeCue || cue.range == 0..3
                }
                true
            }
            frames.finish()
            assertTrue(firstPcmMs != null)
            assertTrue("Expected a valid UTF-8 Café cue from the real SDK", unicodeCue)
            val before = cancelNativeStream(task, descriptor.defaultVoice, afterFirstChunk = false)
            val after = cancelNativeStream(task, descriptor.defaultVoice, afterFirstChunk = true)
            engine.resetCancellation()
            val recovered = VoiceStreamFrames(FIXTURE)
            engine.stream(FIXTURE, 0, 1f) {
                recovered.accept(it)
                true
            }
            recovered.finish()
            val incremental = consumeIncremental(engine, task)
            val incrementalBefore = cancelNativeStream(task, descriptor.defaultVoice, afterFirstChunk = false, incremental = true)
            val incrementalAfter = cancelWaitingInput(engine, task)
            engine.resetCancellation()
            val incrementalRecovered = consumeIncremental(engine, task)
            evidence = JSONObject()
                .put("schema", "mediapipe-android-jni-smoke-v2")
                .put("scope", "android_jni_checkpoint_smoke")
                .put("status", "passed")
                .put("run_id", runId ?: JSONObject.NULL)
                .put("engine", kind.name)
                .put("runtime", MediaPipeSpeechCatalog.RUNTIME_ID)
                .put("model_sha256", descriptor.sha256)
                .put("model_bytes", copied)
                .put("requested_delegate", delegate.name)
                .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("sample_rate_hz", VOICE_PCM_SAMPLE_RATE)
                .put("frames", frames.frames)
                .put("emitted_chunks", chunks)
                .put("prepare_ms", prepareMs)
                .put("first_pcm_ms", firstPcmMs)
                .put("utf8_estimated_cue_observed", unicodeCue)
                .put("cancel_before_first_read", before)
                .put("cancel_after_first_chunk", after)
                .put("recovered_frames", recovered.frames)
                .put("incremental_stream", incremental)
                .put("incremental_cancel_before_first_read", incrementalBefore)
                .put("incremental_cancel_while_waiting_for_text", incrementalAfter)
                .put("incremental_stream_after_cancellation", incrementalRecovered)
        } finally {
            // If native retirement fails, preserve its files rather than unlink mapped data.
            engine.close()
            check(directory.deleteRecursively()) { "Could not remove the native smoke checkpoint/cache." }
        }
        val completedEvidence = checkNotNull(evidence)
        evidenceFile.writeText(completedEvidence.toString(2))
        instrumentation.sendStatus(2, Bundle().apply { putString("mediapipe_smoke_evidence", completedEvidence.toString()) })
    }

    private fun cancelNativeStream(task: MediaPipeSpeechTask, voiceId: String, afterFirstChunk: Boolean, incremental: Boolean = false): String {
        (if (incremental) task.openIncrementalStream(voiceId) else task.openStream(CANCEL_FIXTURE, voiceId)).use { stream ->
            if (incremental) stream.pushText(FIRST_DELTA)
            if (afterFirstChunk) assertTrue(checkNotNull(stream.readNext()).samples.isNotEmpty())
            stream.cancel()
            val result = runCatching { stream.readNext() }
            if (result.isSuccess) {
                assertEquals(null, result.getOrNull())
                return "end_of_stream"
            }
            val failure = checkNotNull(result.exceptionOrNull())
            assertTrue("The pinned JNI must report its concrete cancellation exception", failure is CancellationException)
            return "native_cancelled"
        }
    }

    private fun consumeIncremental(engine: MediaPipeVoicePhraseEngine, task: ObservedNativeTask): JSONObject {
        val firstPcm = CountDownLatch(1)
        val frames = VoiceStreamFrames(FIXTURE)
        val started = SystemClock.elapsedRealtime()
        var firstPcmMs: Long? = null
        var supplied = 0
        var chunks = 0
        var unicodeCue = false
        var lastWordCue = false
        engine.streamInput(
            nextText = {
                when (supplied++) {
                    0 -> FIRST_DELTA
                    1 -> {
                        check(firstPcm.await(60, TimeUnit.SECONDS)) { "No native PCM arrived while incremental input remained open." }
                        LAST_DELTA
                    }
                    else -> null
                }
            },
            speaker = 0,
            speed = 1f
        ) { chunk ->
            check(++chunks <= 4_096)
            if (chunk.samples.isNotEmpty() && firstPcmMs == null) {
                firstPcmMs = SystemClock.elapsedRealtime() - started
                firstPcm.countDown()
            }
            frames.accept(chunk).forEach { cue ->
                assertEquals(VoiceWordCueProvenance.ESTIMATED, cue.provenance)
                unicodeCue = unicodeCue || cue.range == 0..3
                lastWordCue = lastWordCue || FIXTURE.lastIndex - 1 in cue.range
            }
            check(frames.frames <= VOICE_PCM_SAMPLE_RATE * 30L) { "Incremental smoke exceeded its PCM bound." }
            true
        }
        frames.finish()
        val trace = checkNotNull(task.latestInput)
        assertEquals(listOf(FIRST_DELTA, LAST_DELTA), trace.pushedText.toList())
        assertEquals(1, trace.finishCalls.get())
        assertTrue("Native PCM must precede finishInput and the later text delta", trace.firstPcmBeforeFinish.get())
        assertTrue("Both ends of the full Unicode input need valid cues", unicodeCue && lastWordCue)
        assertTrue(trace.closed.get())
        assertTrue(frames.frames in 1..(VOICE_PCM_SAMPLE_RATE * 30L))
        return JSONObject()
            .put("input_mode", "incremental")
            .put("input_utf8_bytes", FIXTURE.toByteArray(Charsets.UTF_8).size)
            .put("input_sha256", MessageDigest.getInstance("SHA-256").digest(FIXTURE.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
            .put("text_delta_count", trace.pushedText.size)
            .put("finish_input_calls", trace.finishCalls.get())
            .put("first_pcm_before_finish_input", trace.firstPcmBeforeFinish.get())
            .put("full_input_cues_observed", unicodeCue && lastWordCue)
            .put("stream_closed", trace.closed.get())
            .put("frames", frames.frames)
            .put("emitted_chunks", chunks)
            .put("first_pcm_ms", checkNotNull(firstPcmMs))
    }

    private fun cancelWaitingInput(engine: MediaPipeVoicePhraseEngine, task: ObservedNativeTask): JSONObject {
        val waiting = CountDownLatch(1)
        val inputRetired = CountDownLatch(1)
        val holdInput = CountDownLatch(1)
        var supplied = false
        var firstPcm = false
        val started = SystemClock.elapsedRealtime()
        val outcome = runCatching {
            engine.streamInput(
                nextText = {
                    if (!supplied) {
                        supplied = true
                        FIRST_DELTA
                    } else {
                        waiting.countDown()
                        try {
                            check(holdInput.await(60, TimeUnit.SECONDS)) { "Open-input cancellation did not interrupt the input supplier." }
                            error("Cancelled input must not be finished.")
                        } finally {
                            inputRetired.countDown()
                        }
                    }
                },
                speaker = 0,
                speed = 1f
            ) { chunk ->
                if (chunk.samples.isNotEmpty()) {
                    assertTrue("No PCM may be emitted after cancellation", !firstPcm)
                    firstPcm = true
                    assertTrue(waiting.await(5, TimeUnit.SECONDS))
                    engine.cancel()
                }
                true
            }
        }
        assertTrue("The real production owner must report cancellation", outcome.exceptionOrNull() is CancellationException)
        assertTrue(firstPcm)
        assertEquals(0L, inputRetired.count)
        val trace = checkNotNull(task.latestInput)
        assertEquals(listOf(FIRST_DELTA), trace.pushedText.toList())
        assertEquals(0, trace.finishCalls.get())
        assertTrue(trace.closed.get())
        return JSONObject()
            .put("outcome", "native_cancelled")
            .put("input_open_at_cancel", true)
            .put("finish_input_calls", trace.finishCalls.get())
            .put("first_pcm_observed", firstPcm)
            .put("input_owner_retired", inputRetired.count == 0L)
            .put("stream_closed", trace.closed.get())
            .put("total_ms", SystemClock.elapsedRealtime() - started)
    }

    /** Observes successful calls to the packaged JNI; every operation delegates to the real SDK. */
    private class ObservedNativeTask(private val native: MediaPipeSpeechTask) : MediaPipeSpeechTask by native {
        var latestInput: InputTrace? = null
            private set

        override fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream {
            val stream = native.openIncrementalStream(voiceId)
            val trace = InputTrace().also { latestInput = it }
            return object : MediaPipeSpeechStream by stream {
                override fun pushText(text: String) {
                    stream.pushText(text)
                    trace.pushedText.add(text)
                }

                override fun finishInput() {
                    trace.finishCalls.incrementAndGet()
                    stream.finishInput()
                }

                override fun readNext(): AudioChunk? = stream.readNext().also { chunk ->
                    if (chunk?.samples?.isNotEmpty() == true && trace.firstPcmSeen.compareAndSet(false, true)) {
                        trace.firstPcmBeforeFinish.set(trace.finishCalls.get() == 0)
                    }
                }

                override fun close() {
                    stream.close()
                    trace.closed.set(true)
                }
            }
        }
    }

    private class InputTrace {
        val pushedText = CopyOnWriteArrayList<String>()
        val finishCalls = AtomicInteger()
        val firstPcmSeen = AtomicBoolean()
        val firstPcmBeforeFinish = AtomicBoolean()
        val closed = AtomicBoolean()
    }

    private companion object {
        const val FIRST_DELTA = "Café is ready. "
        const val LAST_DELTA = "The quick brown fox jumps over the lazy dog."
        const val FIXTURE = FIRST_DELTA + LAST_DELTA
        val CANCEL_FIXTURE = "This sentence checks cancellation before the entire request finishes. ".repeat(12)
    }
}
