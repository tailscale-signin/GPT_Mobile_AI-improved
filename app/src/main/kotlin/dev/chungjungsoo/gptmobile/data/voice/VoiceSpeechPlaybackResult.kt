package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechPlaybackState { COMPLETE, FAILED, UNSUPPORTED, CANCELLED }

/** Fixed-fixture production playback observations. Unmeasured values stay null; no PCM or text is retained. */
internal data class VoiceSpeechPlaybackResult(
    val requestedBackend: VoiceSpeechBackend,
    val state: VoiceSpeechPlaybackState,
    val firstPcmMs: Long? = null,
    val firstQueuedPcmMs: Long? = null,
    val firstPresentedAudioMs: Long? = null,
    val elapsedMs: Long = 0,
    val totalSynthesisMs: Long = 0,
    val totalAudioMs: Long = 0,
    val chunks: Int = 0,
    val underruns: Int? = null,
    val bufferRestarts: Int = 0,
    val longestRefillMs: Long = 0,
    val cancellationMs: Long? = null,
    val captionObservations: Int = 0,
    val captionClockRegressions: Int = 0,
    val captionTimingViolations: Int = 0,
    val clockBasis: String? = null,
    val route: String? = null,
    val effectiveProvider: String? = null,
    val acceleratorExecutionObserved: Boolean = false,
    val thermalBefore: Int? = null,
    val thermalAfter: Int? = null,
    val failure: VoiceSpeechFailureCode? = null,
    val failureStage: VoiceSpeechStage? = null,
    val validatedChunks: Int = 0,
    val captionWordsExpected: Int = 0,
    val captionWordsObserved: Int = 0
)

/** Optional numeric observer on the production queue. It receives no text, audio, device ID or credentials. */
internal interface VoicePlaybackMeasurementObserver {
    fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) = Unit
    fun pcmQueued() = Unit
    fun captionsPrepared(segment: Int, ranges: List<IntRange>) = Unit
    fun firstPresented(clockBasis: String, route: String) = Unit
    fun terminal(underruns: Int?, bufferRestarts: Int, longestRefillMs: Long, clockBasis: String, route: String) = Unit
}

/** Synthesis, playback and main-thread caption callbacks share one bounded numeric receipt. */
internal class VoiceSpeechPlaybackMeasurement(
    private val backend: VoiceSpeechBackend,
    private val thermalBefore: Int?,
    private val nanoTime: () -> Long = System::nanoTime
) : VoicePlaybackMeasurementObserver {
    private val started = nanoTime()
    private var firstPcm: Long? = null
    private var firstQueued: Long? = null
    private var firstPresented: Long? = null
    private var synthesis = 0L
    private var audioFrames = 0L
    private var audioRate = 24_000
    private var chunks = 0
    private var validatedChunks = 0
    private var underruns: Int? = null
    private var restarts = 0
    private var refill = 0L
    private var clock: String? = null
    private var route: String? = null
    private var provider: String? = null
    private var accelerated = false
    private var captions = 0
    private var regressions = 0
    private var violations = 0
    private var lastCaptionFrame: Long? = null
    private var cancellationRequested: Long? = null
    private var stoppedAt: Long? = null
    private data class CaptionSpan(val segment: Int, val first: Int, val last: Int)
    private val expectedWords = mutableSetOf<CaptionSpan>()
    private val observedWords = mutableSetOf<CaptionSpan>()

    @Synchronized override fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) {
        if (stoppedAt != null) return
        if (firstPcm == null) firstPcm = elapsed()
        synthesis += synthesisMs.coerceAtLeast(0)
        this.audioFrames += audioFrames.coerceAtLeast(0)
        audioRate = sampleRate.coerceAtLeast(1)
        chunks++
        provider = evidence?.effectiveProvider?.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp") } ?: provider
        accelerated = accelerated || evidence?.let { it.verifiedAudio && it.profileAvailable == true && it.acceleratorKernels > 0 && it.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(backend) } == true
        if (audioFrames > 0 &&
            sampleRate == 24_000 &&
            evidence?.verifiedAudio == true &&
            evidence.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(backend) &&
            (backend == VoiceSpeechBackend.CPU || evidence.profileAvailable == true && evidence.acceleratorKernels > 0)
        ) {
            validatedChunks++
        }
    }

    @Synchronized override fun pcmQueued() {
        if (stoppedAt == null && firstQueued == null) firstQueued = elapsed()
    }

    @Synchronized override fun firstPresented(clockBasis: String, route: String) {
        if (stoppedAt != null) return
        if (firstPresented == null) firstPresented = elapsed()
        this.clock = clockBasis.takeIf { it in setOf("audio_timestamp", "playback_head") } ?: this.clock
        this.route = safeRoute(route) ?: this.route
    }

    @Synchronized override fun captionsPrepared(segment: Int, ranges: List<IntRange>) {
        if (stoppedAt != null) return
        for (range in ranges.filterNot { it.isEmpty() }) {
            val span = CaptionSpan(segment, range.first, range.last)
            if (span in expectedWords || expectedWords.size < MAX_CAPTION_WORDS) expectedWords.add(span) else violations++
        }
    }

    @Synchronized fun caption(timing: VoiceCaptionTiming, segment: Int? = null, range: IntRange? = null) {
        if (stoppedAt != null) return
        captions++
        if (lastCaptionFrame?.let { timing.presentedFrame < it } == true) regressions++
        // An empty cue clears the active word and legitimately has equal start/end frames.
        if (timing.wordEndFrame < timing.wordStartFrame || timing.presentedFrame > timing.bufferedEndFrame) violations++
        lastCaptionFrame = timing.presentedFrame
        if (segment != null && range != null && !range.isEmpty()) {
            val span = CaptionSpan(segment, range.first, range.last)
            if (span in observedWords || observedWords.size < MAX_CAPTION_WORDS) observedWords.add(span) else violations++
        }
    }

    @Synchronized fun cancelRequested() {
        if (stoppedAt != null) return
        if (cancellationRequested == null) cancellationRequested = elapsed()
    }

    @Synchronized override fun terminal(underruns: Int?, bufferRestarts: Int, longestRefillMs: Long, clockBasis: String, route: String) {
        if (stoppedAt != null) return
        this.underruns = underruns?.coerceAtLeast(0)
        restarts = bufferRestarts.coerceAtLeast(0)
        refill = longestRefillMs.coerceAtLeast(0)
        clock = clockBasis.takeIf { it in setOf("audio_timestamp", "playback_head") } ?: clock
        this.route = safeRoute(route) ?: this.route
        stoppedAt = elapsed()
    }

    @Synchronized fun result(state: VoiceSpeechPlaybackState, thermalAfter: Int?, failure: VoiceSpeechFailureCode? = null, stage: VoiceSpeechStage? = null): VoiceSpeechPlaybackResult {
        val ended = stoppedAt ?: elapsed()
        return VoiceSpeechPlaybackResult(
            backend, state, firstPcm, firstQueued, firstPresented, ended, synthesis,
            audioFrames * 1_000 / audioRate, chunks, underruns, restarts, refill,
            cancellationRequested?.let { requested -> stoppedAt?.let { (it - requested).coerceAtLeast(0) } },
            captions, regressions, violations + observedWords.count { it !in expectedWords }, clock, route, provider, accelerated,
            thermalBefore, thermalAfter, failure, stage, validatedChunks,
            expectedWords.size, observedWords.count { it in expectedWords }
        )
    }

    private fun elapsed() = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000)
    private fun safeRoute(value: String): String? = when {
        value.isBlank() || value == "unknown" -> null
        value in setOf("speaker", "earpiece", "bluetooth", "usb", "wired") -> value
        else -> "other"
    }

    private companion object {
        const val MAX_CAPTION_WORDS = 256
    }
}
