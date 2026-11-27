package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.media.AudioAttributes
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoiceOutput
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.QualcommVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoicePlaybackMeasurementObserver
import dev.chungjungsoo.gptmobile.data.voice.VoiceReplyPlayback
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBuffer
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechExecutionEvidence
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in playback of one fixed public fixture through the normal buffer, renderer and AudioTrack. */
class KokoroListeningInstrumentedTest {
    @Test fun playPublicFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Listening trial requires a public fixture number", args.containsKey("listeningFixture"))
        val id = requireNotNull(args.getString("listeningFixture"))
        val text = instrumentation.context.assets.open("voice/listening-v1.tsv").bufferedReader().useLines { lines ->
            lines.drop(1).map { it.split('\t') }.single { it.first() == id }[1]
        }
        val backend = VoiceSpeechBackend.valueOf(requireNotNull(args.getString("listeningBackend")))
        val variant = KokoroModelVariant.ORIGINAL_FP32
        val context = instrumentation.targetContext
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"), variant = variant)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = AtomicReference<String?>()
        val ended = CountDownLatch(1)
        var verifiedPhrases = 0
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) {
                if (backend.isStreamingLocal) return
                val provider = when (backend) {
                    VoiceSpeechBackend.CPU -> "cpu"
                    VoiceSpeechBackend.QNN_GPU -> "qnn-gpu"
                    VoiceSpeechBackend.QNN_HTP -> "qnn-htp"
                    else -> error("Unsupported listening route")
                }
                if (evidence?.verifiedAudio == true &&
                    evidence.effectiveProvider == provider &&
                    (backend == VoiceSpeechBackend.CPU || evidence.profileAvailable == true && evidence.acceleratorKernels > 0)
                ) {
                    verifiedPhrases++
                } else {
                    failure.compareAndSet(null, "Native route unverified")
                }
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() }, { 1f }, measurements = observer)
        try {
            val factory = if (backend.isStreamingLocal) {
                val engine = requireNotNull(backend.streamingEngine()).also { require(it.isKokoro) }
                val sdk = MediaPipeSpeechModelStore(context, scope)
                runBlocking { sdk.refresh(engine) }
                MediaPipeVoicePhraseEngineFactory(sdk, engine, backend, 1f, KokoroVoice.HEART.streamingVoiceId)
            } else {
                runBlocking { models.refresh() }
                check(models.isInstalled()) { "Install the original FP32 pack" }
                val native = QualcommVoicePhraseEngineFactory(context, backend, {}, modelVariant = variant)
                // Bypass automatic recovery so every stimulus is attributable to its requested route.
                object : VoicePhraseEngineFactory by native {
                    override fun create(directory: File, lexicon: String, threads: Int) = native.createBenchmarkEngine(directory, lexicon, threads)
                }
            }
            lateinit var reply: VoiceReplyPlayback
            instrumentation.runOnMainSync {
                output.configureEngine(factory)
                output.configureThreads(2)
                output.configurePronunciation(mapOf("Melo" to "mellow"), language = "en")
                reply = checkNotNull(
                    output.openReply(KokoroVoice.HEART, ended::countDown) { message, _ ->
                        failure.compareAndSet(null, message)
                        ended.countDown()
                    }
                )
            }
            val buffer = VoiceSpeechBuffer(preserveLiteralTerms = true)
            val characterArrival = args.getString("listeningArrival") == "character"
            val ends = if (characterArrival) (1..text.length).toList() else Regex("\\S+\\s*").findAll(text).map { it.range.last + 1 }.toList()
            for (end in ends) {
                val pieces = checkNotNull(buffer.append(text.substring(0, end), final = end == text.length, earlyStart = true))
                instrumentation.runOnMainSync { pieces.forEach(reply::enqueue) }
                if (end < text.length) Thread.sleep(if (characterArrival) 5 else 25)
            }
            instrumentation.runOnMainSync(reply::finish)
            assertTrue("Fixture never reached playback completion", ended.await(120, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertTrue(backend.isStreamingLocal || verifiedPhrases > 0)
        } finally {
            instrumentation.runOnMainSync(output::close)
            check(output.awaitClosed(30_000))
            scope.cancel()
        }
    }
}
