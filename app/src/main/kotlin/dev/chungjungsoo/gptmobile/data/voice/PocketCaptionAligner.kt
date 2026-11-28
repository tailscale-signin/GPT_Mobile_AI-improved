package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import java.io.File

internal interface PocketCaptionAlignmentEngine : AutoCloseable {
    fun align(text: String, samples: FloatArray, checkActive: () -> Unit): PocketCaptionAlignment
}

/** The retained reference also retains its model lease when native destruction is unconfirmed. */
internal class PocketCaptionCleanupFailure(val retained: Any, cause: Throwable) : IllegalStateException("Pocket caption native cleanup is unconfirmed.", cause)

/** One synthesis owner serializes the loaded aligner, inference, and retirement. */
internal class PocketCaptionAligner private constructor(
    private val lease: PocketCaptionModelStore.ModelLease,
    private val native: PocketCaptionNativeApi,
    private val onNativeCleanupFailed: (Any) -> Unit
) : PocketCaptionAlignmentEngine {
    private var handle = -1
    private var closed = false
    private var retirementFailure: Throwable? = null

    override fun align(text: String, samples: FloatArray, checkActive: () -> Unit): PocketCaptionAlignment {
        check(!closed && handle >= 0)
        require(samples.size in 1..PocketCaptionPolicy.MAX_PHRASE_FRAMES)
        checkActive()
        // The pinned English frontend drops a partial 80 ms block. Supply one complete
        // zero tail to the analysis only. No padding enters playback or accepted cue ranges.
        val analysisFrames = ((samples.size + ANALYSIS_BLOCK_FRAMES - 1) / ANALYSIS_BLOCK_FRAMES + 1) * ANALYSIS_BLOCK_FRAMES
        val analysis = samples.copyOf(analysisFrames)
        val transcript = try {
            VoiceNativeEngineGate.exclusive {
                checkActive()
                native.transcribe(handle, analysis, VOICE_PCM_SAMPLE_RATE).also { checkActive() }
            }
        } finally {
            analysis.fill(0f)
        }
        return PocketCaptionPolicy.align(text, samples.size, transcript)
    }

    override fun close() {
        retirementFailure?.let { throw PocketCaptionCleanupFailure(this, it) }
        if (closed) return
        try {
            if (handle >= 0) VoiceNativeEngineGate.exclusive { native.free(handle) }
        } catch (failure: Throwable) {
            retirementFailure = failure
            onNativeCleanupFailed(this)
            throw PocketCaptionCleanupFailure(this, failure)
        }
        handle = -1
        closed = true
        lease.close()
    }

    companion object {
        internal const val ANALYSIS_BLOCK_FRAMES = VOICE_PCM_SAMPLE_RATE * 80 / 1_000

        fun open(
            store: PocketCaptionModelStore,
            checkActive: () -> Unit,
            onNativeCleanupFailed: (Any) -> Unit = {},
            native: PocketCaptionNativeApi = JniPocketCaptionNativeApi
        ): PocketCaptionAligner? {
            checkActive()
            val lease = store.acquire() ?: return null
            return open(lease, checkActive, onNativeCleanupFailed, native)
        }

        internal fun open(
            lease: PocketCaptionModelStore.ModelLease,
            checkActive: () -> Unit,
            onNativeCleanupFailed: (Any) -> Unit,
            native: PocketCaptionNativeApi
        ): PocketCaptionAligner {
            val aligner = PocketCaptionAligner(lease, native, onNativeCleanupFailed)
            try {
                VoiceNativeEngineGate.exclusive {
                    checkActive()
                    aligner.handle = native.load(lease.directory)
                    check(aligner.handle >= 0) { "POCKET_CAPTION_MODEL_LOAD" }
                    checkActive()
                }
                return aligner
            } catch (failure: Throwable) {
                try {
                    aligner.close()
                } catch (cleanup: PocketCaptionCleanupFailure) {
                    cleanup.addSuppressed(failure)
                    throw cleanup
                }
                throw failure
            }
        }
    }
}

/** Injectable boundary verifies cancellation/lease ordering without pretending to execute Android JNI. */
internal interface PocketCaptionNativeApi {
    fun load(directory: File): Int
    fun transcribe(model: Int, samples: FloatArray, sampleRate: Int): PocketCaptionTranscript
    fun free(model: Int)
}

private object JniPocketCaptionNativeApi : PocketCaptionNativeApi {
    override fun load(directory: File): Int {
        JNI.ensureLibraryLoaded()
        return JNI.moonshineLoadTranscriberFromFiles(
            directory.absolutePath,
            JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
            arrayOf(
                TranscriberOption("word_timestamps", "true"),
                // Pinned 0.1.6 speculative verification batches attention rows incompatibly
                // with the aligner and omits EOS (upstream Moonshine issue #247).
                TranscriberOption("use_speculative_decoding", "false"),
                TranscriberOption("return_audio_data", "false"),
                TranscriberOption("log_output_text", "false"),
                TranscriberOption("identify_speakers", "false"),
                TranscriberOption("vad_window_duration", "0.25"),
                TranscriberOption("vad_max_segment_duration", "12")
            )
        )
    }

    override fun transcribe(model: Int, samples: FloatArray, sampleRate: Int): PocketCaptionTranscript {
        val transcript = checkNotNull(JNI.moonshineTranscribeWithoutStreaming(model, samples, sampleRate, 0)) { "POCKET_CAPTION_TRANSCRIPTION" }
        if (transcript.lines.size > PocketCaptionPolicy.MAX_RECOGNIZED_WORDS) return PocketCaptionTranscript(emptyList(), false)
        val words = ArrayList<PocketCaptionWord>()
        var complete = true
        for (line in transcript.lines) {
            complete = complete && line.isComplete && (line.text.isNullOrBlank() || !line.words.isNullOrEmpty())
            for (word in line.words.orEmpty()) {
                if (words.size == PocketCaptionPolicy.MAX_RECOGNIZED_WORDS) return PocketCaptionTranscript(emptyList(), false)
                words += PocketCaptionWord(word.word.orEmpty(), word.start.toDouble(), word.end.toDouble())
            }
        }
        // WordTiming.confidence is hardcoded to 1.0 by this runtime. Never expose it as
        // confidence, and never treat a complete transcript as acoustic accuracy proof.
        return PocketCaptionTranscript(words, complete)
    }

    override fun free(model: Int) = JNI.moonshineFreeTranscriber(model)
}
