package dev.chungjungsoo.gptmobile.data.voice

import android.app.Instrumentation
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real JNI/checkpoint smoke. Push the exact catalog model to
 * /data/local/tmp/mediapipe-speech/<fileName> and pass mediapipeSmokeEngine=phonon,
 * kokoro or pocket. A supplied argument makes missing/bad weights a failure.
 * On a compatible phone, select the optimized GPU or compiled SM8750 pack by catalog id.
 * Each defaults to its supported delegate. Standard Kokoro also accepts an explicit GPU or NPU.
 * The receipt identifies the request. Kernel placement needs separate device evidence.
 */
@RunWith(AndroidJUnit4::class)
class MediaPipeSpeechNativeSmokeTest {
    @Test
    fun checkpointSelectionUsesThePackDelegateAndRejectsMismatches() {
        val defaults = mapOf(
            MediaPipeSpeechEngine.KOKORO to VoiceSpeechBackend.LITERT_KOKORO_CPU,
            MediaPipeSpeechEngine.KOKORO_GPU to VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED,
            MediaPipeSpeechEngine.KOKORO_SM8750 to VoiceSpeechBackend.LITERT_KOKORO_SM8750,
            MediaPipeSpeechEngine.PHONON to VoiceSpeechBackend.PHONON_CPU,
            MediaPipeSpeechEngine.POCKET to VoiceSpeechBackend.POCKET_CPU
        )
        assertEquals(MediaPipeSpeechEngine.entries.toSet(), defaults.keys)
        for ((engine, backend) in defaults) assertEquals(backend, smokeBackend(engine, null))
        assertEquals(VoiceSpeechBackend.LITERT_KOKORO_GPU, smokeBackend(MediaPipeSpeechEngine.KOKORO, "GPU"))
        assertEquals(VoiceSpeechBackend.LITERT_KOKORO_NPU, smokeBackend(MediaPipeSpeechEngine.KOKORO, "NPU"))
        assertThrows(IllegalArgumentException::class.java) { smokeBackend(MediaPipeSpeechEngine.KOKORO_GPU, "CPU") }
        assertThrows(IllegalArgumentException::class.java) { smokeBackend(MediaPipeSpeechEngine.KOKORO_SM8750, "GPU") }
    }

    private fun smokeBackend(kind: MediaPipeSpeechEngine, selectedDelegate: String?): VoiceSpeechBackend {
        val supported = VoiceSpeechBenchmarkEngine.entries.single { it.mediaPipeEngine() == kind }.supportedBackends
        if (selectedDelegate == null) return supported.first()
        return requireNotNull(supported.singleOrNull { requestedSpeechDelegate(it) == selectedDelegate }) {
            "Unsupported delegate $selectedDelegate for ${kind.name}."
        }
    }

    @Test(timeout = 300_000)
    fun pinnedCheckpointStreamsAndCancelsThroughThePackagedAndroidJni() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val argument = InstrumentationRegistry.getArguments().getString("mediapipeSmokeEngine")
        assumeTrue("Provision a catalog checkpoint and set mediapipeSmokeEngine for this explicit native smoke.", argument != null)
        val runId = InstrumentationRegistry.getArguments().getString("mediapipeSmokeRunId")?.also {
            require(it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        }
        val descriptor = MediaPipeSpeechCatalog.models.single { it.engine.name.equals(argument, ignoreCase = true) || it.id.equals(argument, ignoreCase = true) }
        val kind = descriptor.engine
        val backend = smokeBackend(kind, InstrumentationRegistry.getArguments().getString("mediapipeSmokeDelegate"))
        val delegate = Delegate.valueOf(requestedSpeechDelegate(backend))
        if (kind == MediaPipeSpeechEngine.KOKORO_SM8750) {
            require(isSm8750SpeechDevice()) { "This vocoder is compiled for Snapdragon 8 Elite (SM8750)." }
        }
        val context = instrumentation.targetContext
        val delegateSuffix = if (delegate == Delegate.CPU) "" else "-${delegate.name.lowercase()}"
        val evidenceFile = File(context.filesDir, "mediapipe-smoke-${kind.name.lowercase()}$delegateSuffix.json")
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
            if (kind.isKokoro) {
                val missing = SpeechLanguages.synthesis.flatMap { it.voices }.toSet() - task.voiceIds()
                assertTrue("Kokoro pack is missing catalogued voices: $missing", missing.isEmpty())
            }
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
                .put("model_id", descriptor.id)
                .put("configured_backend", backend.name)
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
            try {
                if (backend == VoiceSpeechBackend.LITERT_KOKORO_CPU) {
                    evidence?.put("application_playback", observeKokoroPlayback(instrumentation, descriptor, model))
                }
            } finally {
                check(directory.deleteRecursively()) { "Could not remove the native smoke checkpoint/cache." }
            }
        }
        val completedEvidence = checkNotNull(evidence)
        evidenceFile.writeText(completedEvidence.toString(2))
        instrumentation.sendStatus(2, Bundle().apply { putString("mediapipe_smoke_evidence", completedEvidence.toString()) })
    }

    private fun observeKokoroPlayback(instrumentation: Instrumentation, descriptor: MediaPipeSpeechModel, model: File): JSONObject {
        val context = instrumentation.targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "kokoro-playback-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = MediaPipeSpeechModelStore(File(root, "models"), File(root, "cache"), scope, listOf(descriptor)) {
            object : LocalVoiceAssetSource {
                override fun open(url: String): LocalVoiceAssetStream {
                    check(url == descriptor.downloadUrl)
                    return LocalVoiceAssetStream(model.inputStream(), model.length())
                }
                override fun cancel() = Unit
            }
        }
        val started = AtomicLong()
        val written = AtomicLong(-1)
        val presented = AtomicLong(-1)
        val basis = AtomicReference<String>()
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun firstAudioTrackWrite() {
                written.compareAndSet(-1, SystemClock.elapsedRealtime() - started.get())
            }
            override fun firstPresented(clockBasis: String, route: String) {
                basis.set(clockBasis)
                presented.compareAndSet(-1, SystemClock.elapsedRealtime() - started.get())
            }
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val output = KokoroVoiceOutput(context, KokoroModelPack.store(File(root, "native")), { attributes }, { 1f }, measurements = observer)
        val firstCaption = AtomicBoolean()
        val lastCaption = AtomicBoolean()
        val appendedAfterPresentation = AtomicBoolean()
        val done = CountDownLatch(1)
        val failure = AtomicReference<String>()
        val voice = KokoroVoice.HEART
        val observed = AtomicReference<ObservedNativeTask>()
        val ending = "The next word arrives."
        val factory = MediaPipeVoicePhraseEngineFactory(models, descriptor.engine, VoiceSpeechBackend.LITERT_KOKORO_CPU, 1f, voice.streamingVoiceId) {
            ObservedNativeTask(AndroidMediaPipeSpeechTask(it)).also(observed::set)
        }
        var activity: android.app.Activity? = null
        try {
            runBlocking { models.install(descriptor.engine) }
            check(models.isReady(descriptor.engine))
            // API 35+ grants audio focus to a foreground app. Start its real launcher activity.
            activity = instrumentation.startActivitySync(checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)))
            instrumentation.runOnMainSync {
                output.configureEngine(factory)
                output.configurePronunciation(mapOf("GPU" to "graphics processor"))
                lateinit var reply: VoiceReplyPlayback
                output.bindPlaybackStarted {
                    appendedAfterPresentation.set(presented.get() >= 0)
                    reply.enqueue(ending)
                    reply.finish()
                }
                output.bindSpokenRange { segment, _, range ->
                    if (segment == 0 && range == 0..2) firstCaption.set(true)
                    if (segment == 1 && ending.lastIndex - 1 in range) lastCaption.set(true)
                }
                started.set(SystemClock.elapsedRealtime())
                reply = checkNotNull(output.openReply(voice, done::countDown) { reason, _ ->
                    failure.set(reason)
                    done.countDown()
                })
                reply.enqueue("GPU is ready.")
            }
            check(done.await(60, TimeUnit.SECONDS)) { "Android streaming playback did not drain." }
            check(failure.get() == null) { "Android streaming playback failed: ${failure.get()}" }
            val trace = checkNotNull(observed.get()?.latestInput)
            check(trace.pushedText.joinToString("").contains("graphics processor")) { "The saved pronunciation did not reach the SDK." }
            check(trace.finishCalls.get() == 1 && trace.closed.get())
            check(written.get() in 0..presented.get() && appendedAfterPresentation.get())
            check(firstCaption.get() && lastCaption.get()) { "Playback did not present both original caption ranges." }
            return JSONObject()
                .put("voice_id", voice.streamingVoiceId)
                .put("speaking_rate", 1f.toDouble())
                .put("first_audio_track_write_ms", written.get())
                .put("first_presented_audio_ms", presented.get())
                .put("clock_basis", basis.get())
                .put("presented_before_last_text", appendedAfterPresentation.get())
                .put("pronunciation_reached_sdk", true)
                .put("source_caption_ranges_presented", firstCaption.get() && lastCaption.get())
                .put("finish_input_calls", trace.finishCalls.get())
                .put("stream_closed", trace.closed.get())
        } finally {
            instrumentation.runOnMainSync {
                output.close()
                activity?.finish()
            }
            scope.cancel()
            check(output.awaitClosed(15_000)) { "The application playback owner did not retire." }
            check(root.deleteRecursively())
        }
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
