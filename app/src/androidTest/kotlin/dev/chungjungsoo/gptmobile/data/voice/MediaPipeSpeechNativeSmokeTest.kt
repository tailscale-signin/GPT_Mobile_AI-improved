package dev.chungjungsoo.gptmobile.data.voice

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
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
 * This does not certify a GPU delegate or establish phone performance rankings.
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
        val backend = when (kind) {
            MediaPipeSpeechEngine.KOKORO -> VoiceSpeechBackend.LITERT_KOKORO_CPU
            MediaPipeSpeechEngine.PHONON -> VoiceSpeechBackend.PHONON_CPU
            MediaPipeSpeechEngine.POCKET -> VoiceSpeechBackend.POCKET_CPU
        }
        val openedAt = SystemClock.elapsedRealtime()
        val cache = File(directory, "runtime-cache").also { check(it.mkdirs() || it.isDirectory) }
        val task = AndroidMediaPipeSpeechTask(MediaPipeSpeechTaskConfiguration(model, cache, false, 1f, descriptor.defaultVoice))
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
            evidence = JSONObject()
                .put("schema", "mediapipe-android-jni-smoke-v1")
                .put("scope", "android_jni_checkpoint_smoke")
                .put("status", "passed")
                .put("run_id", runId ?: JSONObject.NULL)
                .put("engine", kind.name)
                .put("runtime", MediaPipeSpeechCatalog.RUNTIME_ID)
                .put("model_sha256", descriptor.sha256)
                .put("model_bytes", copied)
                .put("requested_delegate", "CPU")
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
        } finally {
            // If native retirement fails, preserve its files rather than unlink mapped data.
            engine.close()
            check(directory.deleteRecursively()) { "Could not remove the native smoke checkpoint/cache." }
        }
        val completedEvidence = checkNotNull(evidence)
        evidenceFile.writeText(completedEvidence.toString(2))
        instrumentation.sendStatus(2, Bundle().apply { putString("mediapipe_smoke_evidence", completedEvidence.toString()) })
    }

    private fun cancelNativeStream(task: MediaPipeSpeechTask, voiceId: String, afterFirstChunk: Boolean): String {
        task.openStream(CANCEL_FIXTURE, voiceId).use { stream ->
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

    private companion object {
        const val FIXTURE = "Café is ready. The quick brown fox jumps over the lazy dog."
        val CANCEL_FIXTURE = "This sentence checks cancellation before the entire request finishes. ".repeat(12)
    }
}
