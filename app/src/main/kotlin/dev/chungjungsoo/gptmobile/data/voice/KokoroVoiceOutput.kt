package dev.chungjungsoo.gptmobile.data.voice

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.update

internal fun createKokoroVoiceOutput(
    context: Context,
    models: LocalVoiceModelStore,
    attributes: () -> AudioAttributes,
    rate: () -> Float,
    privacy: () -> ConversationPrivacyContext? = ConversationPrivacyContext::capture
): KokoroOutput {
    val levels = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    val feedback = kotlinx.coroutines.flow.MutableStateFlow(VoiceSynthesisFeedback())
    return DeferredKokoroOutput(levels, feedback) {
        try {
            KokoroVoiceOutput(context, models, attributes, rate, levels, feedback, privacy)
        } catch (error: LinkageError) {
            AppLogRecorder.record("VoiceEngine", "kokoro_startup_failed · type=${error.javaClass.simpleName}", "W")
            null
        }
    }
}

/**
 * Keeps sherpa-onnx/JNI completely out of Voice screen startup. Configuration and disposal are
 * cheap until Kokoro is actually selected for warmup or playback.
 */
internal class DeferredKokoroOutput(
    override val outputLevel: kotlinx.coroutines.flow.StateFlow<Float?>? = null,
    override val synthesisFeedback: kotlinx.coroutines.flow.StateFlow<VoiceSynthesisFeedback>? = null,
    private val factory: () -> KokoroOutput?
) : KokoroOutput {
    private val lock = Any()
    private var attempted = false
    private var closed = false
    private var threads = 2
    private var pause = VoicePauseDuration.SHORT
    private var performance = VoicePerformanceMode.BALANCED
    private var replyId: String? = null
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    private var spokenRange: SpokenRangeObserver? = null
    private var timedSpokenRange: TimedSpokenRangeObserver? = null
    private var playbackStarted: (() -> Unit)? = null
    private var delegate: KokoroOutput? = null
    private var pronunciations: Map<String, String> = emptyMap()
    private var captionOffset = 0
    private var speechFactory: VoicePhraseEngineFactory = TimedKokoroPhraseEngineFactory

    // The deferred native output reports playback-head consumption without loading JNI here.
    override val reportsPlaybackStart: Boolean get() = existing()?.reportsPlaybackStart ?: true
    override fun bindPlaybackStarted(started: (() -> Unit)?) {
        synchronized(lock) {
            if (closed) return
            playbackStarted = started
            delegate?.bindPlaybackStarted(started)
        }
    }

    override fun bindSpokenRange(spoken: SpokenRangeObserver?) {
        synchronized(lock) {
            if (closed) return
            spokenRange = spoken
            delegate?.bindSpokenRange(spoken)
        }
    }

    override fun bindTimedSpokenRange(spoken: TimedSpokenRangeObserver?) {
        synchronized(lock) {
            if (closed) return
            timedSpokenRange = spoken
            delegate?.bindTimedSpokenRange(spoken)
        }
    }

    private fun existing(): KokoroOutput? = synchronized(lock) { delegate }

    private fun output(): KokoroOutput? = synchronized(lock) {
        if (closed) return@synchronized null
        if (!attempted) {
            attempted = true
            delegate = factory()?.also { output ->
                output.configureThreads(threads)
                output.configurePacing(pause, performance)
                output.configurePronunciation(pronunciations)
                output.configureCaptionOffset(captionOffset)
                output.configureEngine(speechFactory)
                replyId?.let(output::bindReply)
                output.bindDiagnostics(diagnosticProbe)
                output.bindPlaybackStarted(playbackStarted)
                output.bindSpokenRange(spokenRange)
                output.bindTimedSpokenRange(timedSpokenRange)
            }
        }
        delegate
    }

    override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) =
        speakWithFailure(text, voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun speakWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit) {
        output()?.speakWithFailure(text, voice, complete, error)
            ?: error(VoicePlaybackFailure("Kokoro playback is unavailable. Check the downloaded voice files and try again."), true)
    }

    override fun speakProjected(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) =
        speakProjectedWithFailure(text, voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun speakProjectedWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit) {
        output()?.speakProjectedWithFailure(text, voice, complete, error)
            ?: error(VoicePlaybackFailure("Kokoro playback is unavailable. Check the downloaded voice files and try again."), true)
    }

    override fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit): VoiceReplyPlayback? =
        openReplyWithFailure(voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun openReplyWithFailure(voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit): VoiceReplyPlayback? =
        output()?.openReplyWithFailure(voice, complete, error)

    override fun configureThreads(value: Int) {
        val bounded = value.coerceIn(1, 4)
        synchronized(lock) { threads = bounded }
        existing()?.configureThreads(bounded)
    }

    override fun configurePacing(pause: VoicePauseDuration, performance: VoicePerformanceMode) {
        synchronized(lock) {
            this.pause = pause
            this.performance = performance
            delegate?.configurePacing(pause, performance)
        }
    }

    override fun configurePronunciation(overrides: Map<String, String>) {
        synchronized(lock) {
            pronunciations = VoicePronunciationOverrides.clean(overrides)
            delegate?.configurePronunciation(pronunciations)
        }
    }

    override fun configureCaptionOffset(milliseconds: Int) {
        synchronized(lock) {
            captionOffset = milliseconds.coerceIn(-500, 500)
            delegate?.configureCaptionOffset(captionOffset)
        }
    }

    override fun configureEngine(factory: VoicePhraseEngineFactory) {
        synchronized(lock) {
            speechFactory = factory
            delegate?.configureEngine(factory)
        }
    }

    override fun benchmark(voice: KokoroVoice, completed: (String) -> Unit) {
        val current = output()
        if (current == null) completed("Install the voice model before calibration.") else current.benchmark(voice, completed)
    }

    override fun prewarm(voice: KokoroVoice) {
        output()?.prewarm(voice)
    }

    override fun cancelPrewarm() {
        existing()?.cancelPrewarm()
    }

    override fun releaseWarmModel() {
        existing()?.releaseWarmModel()
    }

    override fun stop() = stop("audio_stop")

    override fun stop(reason: String) {
        existing()?.stop(reason)
    }

    override fun bindReply(id: String) {
        synchronized(lock) {
            if (closed) return
            replyId = id
            delegate?.bindReply(id)
        }
    }

    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        synchronized(lock) {
            if (closed) return
            diagnosticProbe = probe
            delegate?.bindDiagnostics(probe)
        }
    }

    override fun close() {
        val current = synchronized(lock) {
            if (closed) return
            closed = true
            delegate.also { delegate = null }
        }
        current?.close()
    }
}

/** The synthesis worker owns the JNI model. The output worker drains one track per reply. */
@Suppress("DEPRECATION")
internal class KokoroVoiceOutput(
    context: Context,
    private val models: LocalVoiceModelStore,
    private val attributes: () -> AudioAttributes,
    private val rate: () -> Float,
    private val measuredOutput: kotlinx.coroutines.flow.MutableStateFlow<Float?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    private val measuredSynthesis: kotlinx.coroutines.flow.MutableStateFlow<VoiceSynthesisFeedback> = kotlinx.coroutines.flow.MutableStateFlow(VoiceSynthesisFeedback()),
    private val privacy: () -> ConversationPrivacyContext? = ConversationPrivacyContext::capture,
    private val measurements: VoicePlaybackMeasurementObserver? = null
) : KokoroOutput {
    private val application = context.applicationContext
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val calibration by lazy { application.getSharedPreferences("voice_synthesis_calibration_v2", Context.MODE_PRIVATE) }

    @Volatile private var calibratedThreads: Map<String, Int> = emptyMap()
    override val synthesisFeedback: kotlinx.coroutines.flow.StateFlow<VoiceSynthesisFeedback> = measuredSynthesis
    private val main = Handler(Looper.getMainLooper())
    private val synthesis = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroSynthesis").apply { isDaemon = true } }
    private val output = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroPlayback").apply { isDaemon = true } }

    @Volatile private var closed = false

    @Volatile private var warmupGeneration = 0L

    @Volatile private var loaded: LoadedModel? = null

    @Volatile private var speechFactory: VoicePhraseEngineFactory = TimedKokoroPhraseEngineFactory

    @Volatile private var pronunciations: Map<String, String> = emptyMap()

    @Volatile private var captionOffset = 0
    private val captionCalibration = VoiceCaptionCalibration()

    @Volatile private var calibrationTicket = 0L

    @Volatile private var calibrating = false

    @Volatile private var active: Playback? = null

    @Volatile private var warmupRequest: ModelKey? = null

    private var threadCount = 2
    private var pause = VoicePauseDuration.SHORT
    private var performance = VoicePerformanceMode.BALANCED

    override fun configurePacing(pause: VoicePauseDuration, performance: VoicePerformanceMode) {
        this.pause = pause
        this.performance = performance
    }
    private var replyId: String? = null
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    private var spokenRange: SpokenRangeObserver? = null
    private var timedSpokenRange: TimedSpokenRangeObserver? = null
    private var playbackStarted: (() -> Unit)? = null
    override fun bindSpokenRange(spoken: SpokenRangeObserver?) {
        spokenRange = spoken
    }

    override fun bindTimedSpokenRange(spoken: TimedSpokenRangeObserver?) {
        timedSpokenRange = spoken
    }

    override val reportsPlaybackStart: Boolean get() = true
    override fun bindPlaybackStarted(started: (() -> Unit)?) {
        playbackStarted = started
    }

    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        diagnosticProbe = probe
    }

    override fun bindReply(id: String) {
        replyId = id.takeIf { it.matches(Regex("[A-Za-z0-9:_-]{1,128}")) }
    }

    override fun configureThreads(value: Int) {
        val bounded = value.coerceIn(1, 4)
        if (threadCount == bounded) return
        threadCount = bounded
        releaseWarmModel()
    }

    override fun configurePronunciation(overrides: Map<String, String>) {
        pronunciations = VoicePronunciationOverrides.clean(overrides)
    }

    override fun configureCaptionOffset(milliseconds: Int) {
        captionCalibration.useSavedRoutes()
        val bounded = milliseconds.coerceIn(-500, 500)
        if (captionOffset == bounded && (active?.captionOffsetMs ?: measuredSynthesis.value.captionOffsetMs) == bounded) return
        captionOffset = bounded
        active?.captionOffsetMs = captionOffset
        val route = active?.route ?: measuredSynthesis.value.route
        if (!closed && route != "unknown") synthesis.execute { calibration.edit().putInt("caption:$route", bounded).apply() }
        measuredSynthesis.update { it.copy(captionOffsetMs = bounded) }
    }

    /** Isolates a benchmark from saved route settings without changing those preferences. */
    internal fun configureFixedCaptionOffset(milliseconds: Int) {
        val bounded = milliseconds.coerceIn(-500, 500)
        captionCalibration.fixed(bounded)
        captionOffset = bounded
        active?.captionOffsetMs = bounded
        measuredSynthesis.update { it.copy(captionOffsetMs = bounded) }
    }

    /** Captures the user's default and observes saved route overrides without persisting a benchmark setting. */
    internal fun configureSavedRouteCaptionOffset(milliseconds: Int) {
        check(active == null)
        captionCalibration.useSavedRoutes()
        captionOffset = milliseconds.coerceIn(-500, 500)
        measuredSynthesis.update { it.copy(captionOffsetMs = captionOffset) }
    }

    override fun configureEngine(factory: VoicePhraseEngineFactory) {
        if (factory.identity == speechFactory.identity) return
        stop("engine_changed")
        speechFactory = factory
        releaseWarmModel()
    }

    private fun calibrationKey(voice: KokoroVoice, speed: Float) = VoiceCpuCalibrationProtocol.key(TimedKokoroPhraseEngineFactory.identity, voice, speed)

    private fun effectiveThreads(voice: KokoroVoice, speed: Float): Int {
        if (performance != VoicePerformanceMode.AUTO || !speechFactory.selectedBackend.supportsCpuThreadCalibration) return threadCount
        if ((powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE) return 2
        return (calibratedThreads[calibrationKey(voice, speed)] ?: 2).coerceIn(1, 4)
    }

    override fun benchmark(voice: KokoroVoice, completed: (String) -> Unit) {
        if (closed || !models.isInstalled()) return completed("Install the voice model before calibration.")
        if (active != null || calibrating) return completed("Pause playback and finish the current calibration first.")
        if (!speechFactory.selectedBackend.supportsCpuThreadCalibration) return completed("Select on-device CPU or Auto speech to calibrate CPU threads.")
        if ((powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE) return completed("Let the phone cool before calibrating speech.")
        calibrating = true
        val ticket = ++calibrationTicket
        val selectionIdentity = speechFactory.identity
        val factory = TimedKokoroPhraseEngineFactory
        val speed = VoiceCpuCalibrationProtocol.rate(rate())
        val key = VoiceCpuCalibrationProtocol.key(factory.identity, voice, speed)
        val capturedPrivacy = privacy()
        synthesis.execute {
            var result = "Speech calibration was cancelled."
            try {
                ConversationPrivacyContext.withCaptured(capturedPrivacy) {
                    val seed = calibration.getInt(VoiceCpuCalibrationProtocol.ORDER_KEY, 0)
                    calibration.edit().putInt(VoiceCpuCalibrationProtocol.ORDER_KEY, (Math.floorMod(seed, 8) + 1) % 8).apply()
                    val samples = VoiceNativeEngineGate.exclusive {
                        var candidate: VoicePhraseEngine? = null
                        VoiceCpuCalibrationProtocol.measure(
                            seed,
                            speed,
                            active = { !closed && calibrationTicket == ticket && active == null && speechFactory.identity == selectionIdentity },
                            thermalStatus = { powerManager?.currentThermalStatus },
                            prepare = { threads ->
                                candidate = loadModel(ModelKey(voice.lexicon, threads, factory)).engine.also { it.resetCancellation() }
                            },
                            generate = { text, selectedRate ->
                                val audio = checkNotNull(candidate).generate(text, voice.speakerId, selectedRate)
                                require(audio.samples.isNotEmpty() && audio.samples.all(Float::isFinite) && audio.samples.any { it != 0f })
                                kokoroWordFrames(audio.durations, audio.wordTokens, audio.wordTokens.size / 2, audio.samples.size)
                            },
                            nanoTime = SystemClock::elapsedRealtimeNanos
                        )
                    }
                    if (!closed && calibrationTicket == ticket && active == null && speechFactory.identity == selectionIdentity && powerManager?.currentThermalStatus in 0..1) {
                        val selected = VoiceCpuCalibration.select(samples)
                        calibration.edit().putInt(key, selected).apply()
                        calibratedThreads = calibratedThreads + (key to selected)
                        measuredSynthesis.update { it.copy(threads = selected) }
                        result = "Calibration complete at $speed×: Auto uses $selected CPU threads for ${voice.label}. Slowest measured warm phrase: ${samples.getValue(selected).max()} ms."
                    }
                }
            } catch (stopped: VoiceCalibrationInterrupted) {
                result = when (stopped.reason) {
                    VoiceCalibrationStop.CANCELLED -> "Speech calibration was cancelled; the previous setting is retained."
                    VoiceCalibrationStop.THERMAL -> "The phone warmed up during calibration; the previous setting is retained. Let it cool and try again."
                    VoiceCalibrationStop.THERMAL_UNAVAILABLE -> "Temperature could not be verified; the previous calibration is retained."
                }
            } catch (_: Exception) {
                result = if (calibrationTicket != ticket || closed) "Speech calibration was cancelled." else "Speech calibration failed; the previous setting is retained."
            } finally {
                calibrating = false
                releaseModel()
                main.post { completed(result) }
            }
        }
    }

    private data class ModelKey(val lexicon: String, val threads: Int, val factory: VoicePhraseEngineFactory)
    private class LexiconUnavailable : IllegalStateException()
    private class LoadedModel(val key: ModelKey, val engine: VoicePhraseEngine, val words: Set<String>, val leasedKokoroAssets: Boolean)

    private class Playback(
        val runId: String?,
        val voice: KokoroVoice,
        val key: ModelKey,
        val focus: AudioFocusRequest,
        val watchdog: Runnable,
        val complete: () -> Unit,
        val error: (VoicePlaybackFailure, Boolean) -> Unit,
        val diagnostics: VoiceDiagnosticTrace.Probe?,
        val audioStarted: (() -> Unit)?,
        val onRange: SpokenRangeObserver?,
        val onTimedRange: TimedSpokenRangeObserver?,
        val pause: VoicePauseDuration,
        val performance: VoicePerformanceMode,
        val rate: Float,
        val privacy: ConversationPrivacyContext?,
        val requestedThreads: Int,
        val measurements: VoicePlaybackMeasurementObserver?
    ) {
        val id = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val captions = VoicePcmCaptions()
        val segments = ArrayBlockingQueue<String>(MAX_SEGMENTS)
        val pcm = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
        val adaptive = VoiceAdaptivePlayback(performance)
        val enqueuedFrames = AtomicLong()
        val consumedFrames = AtomicLong()
        var presentedFrames = 0L
        var lastCaptionFrame = 0L
        var lastCaptionUpdate = 0L
        var captionPlaying: Boolean? = null
        var timestampPlaying: Boolean? = null
        var hasTimestamp = false
        var nextTimestampAt = 0L
        val timestamp = AudioTimestamp()
        val clock = VoicePlaybackClock(VOICE_PCM_SAMPLE_RATE)
        var routeId = -1

        @Volatile var route = "unknown"
        var clockBasis = "unknown"

        @Volatile var captionOffsetMs = 0
        var drainAt: Long? = null

        @Volatile var cancelledAt: Long? = null

        // D8 can emit invalid monitor regions for inlined synchronized blocks in playback cleanup.
        val trackLock = ReentrantLock()

        @Volatile var lastProgress = startedAt

        @Volatile var sealed = false

        @Volatile var synthesizing = true

        @Volatile var preparing = true

        @Volatile var synthesisDone = false

        @Volatile var queuedAudio = false

        @Volatile var focusState = KokoroFocusState()

        // Guarded with trackLock; queuedAudio records historical output, not current readiness.
        var trackPrimed = false

        var track: AudioTrack? = null
        var underruns: Int? = null
        val underrunCounter = VoicePlaybackUnderruns()
        var underrunsAvailable = false
        var lastUnderrunObservation = 0L

        @Volatile var refilling = false

        @Volatile var bufferRestarts = 0

        @Volatile var longestRefillMs = 0L
        var firstPcm = false
        var firstTrackWrite = false
        var firstFrame = false
        var warmAtRequest = false

        @Volatile var nativeFallback: VoicePlaybackFailure? = null

        @Volatile var nativeFallbackSucceeded = false
        val energyWindows = ArrayDeque<PcmEnergy>()
        var lastEnergyUpdate = 0L
        var lastFeedbackUpdate = 0L
        var playbackHeadWraps = 0L
        var lastPlaybackHead = 0L
        var inputChars = 0

        @Volatile var knownWords = 0

        @Volatile var initialisms = 0

        @Volatile var unknownWords = 0

        @Volatile var spelledLetters = 0

        @Volatile var removedFormatCharacters = 0
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = trimMemory()
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimMemory()
        }
    }

    init {
        application.registerComponentCallbacks(memoryCallbacks)
        // SharedPreferences may touch disk on its first access. The screen can construct
        // and prewarm the deferred output on main without doing that read there.
        synthesis.execute {
            calibratedThreads = calibration.all.mapNotNull { (key, value) ->
                (value as? Int)?.takeIf { key.startsWith("threads:") }?.let { key to it }
            }.toMap()
        }
    }

    private fun trimMemory() {
        main.post {
            active?.let { playback -> fail(playback, "memory_pressure", "Kokoro released its model to free memory. You can replay the reply.") }
            releaseWarmModel()
        }
    }

    override fun cancelPrewarm() {
        warmupGeneration++
        warmupRequest = null
        if (active == null && !calibrating) speechFactory.cancelPending()
    }

    override fun prewarm(voice: KokoroVoice) {
        val factory = speechFactory
        if (closed || active != null || calibrating || !factory.isReady() || factory.requiresKokoroAssets && !models.isInstalled()) return
        val speed = VoiceCpuCalibrationProtocol.rate(rate())
        val key = ModelKey(voice.lexicon, effectiveThreads(voice, speed), factory)
        if (warmupRequest == key) return
        warmupRequest = key
        val generation = ++warmupGeneration
        val capturedPrivacy = privacy()
        synthesis.execute {
            if (warmupRequest != key || warmupGeneration != generation) return@execute
            try {
                ConversationPrivacyContext.withCaptured(capturedPrivacy) {
                    VoiceNativeEngineGate.exclusive {
                        if (!closed && active == null && !calibrating && warmupRequest == key && warmupGeneration == generation) {
                            // Preference hydration precedes this task on the same worker. Resolve
                            // its measured thread choice now, instead of warming the startup fallback.
                            val resolved = key.copy(threads = effectiveThreads(voice, speed))
                            loadModelExclusive(resolved)
                            if (warmupGeneration == generation) warmupRequest = resolved
                        }
                    }
                }
            } catch (_: Exception) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } catch (_: LinkageError) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } catch (_: OutOfMemoryError) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            }
        }
    }

    override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) =
        speakWithFailure(text, voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun speakWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit) {
        openReplyWithFailure(voice, complete, error)?.let {
            VoiceSpeechBuffer().append(text, final = true).orEmpty().forEach(it::enqueue)
            it.finish()
        }
    }

    override fun speakProjected(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) =
        speakProjectedWithFailure(text, voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun speakProjectedWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit) {
        openReplyWithFailure(voice, complete, error)?.let {
            it.enqueue(text)
            it.finish()
        }
    }

    override fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit): VoiceReplyPlayback? =
        openReplyWithFailure(voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun openReplyWithFailure(voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit): VoiceReplyPlayback? {
        if (closed) return null
        if (calibrating) {
            calibrationTicket++
            runCatching { loaded?.engine?.cancel() }
        }
        active?.let { end(it, "replaced") }
        val playbackAttributes = attributes()
        val speed = rate().takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f
        lateinit var playback: Playback
        var delayedAttempt: Int? = null
        fun startSynthesis(attempt: Int) {
            delayedAttempt = null
            playback.diagnostics?.playbackFocus(VoiceDiagnosticTrace.Focus.GRANTED, attempt)
            playback.lastProgress = SystemClock.elapsedRealtime()
            main.removeCallbacks(playback.watchdog)
            main.postDelayed(playback.watchdog, 1_000)
            synthesis.execute {
                ConversationPrivacyContext.withCaptured(playback.privacy) { synthesize(playback, speed, playbackAttributes) }
            }
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change ->
                if (active === playback) {
                    val focusAction = kokoroFocusAction(change)
                    playback.diagnostics?.playbackFocusTransition(focusAction.name)
                    when (focusAction) {
                        KokoroFocusAction.RESTORE -> {
                            val failed = runCatching {
                                playback.trackLock.withLock {
                                    val resume = playback.focusState.resumeOnGain(playback.trackPrimed)
                                    playback.focusState = playback.focusState.transition(focusAction, SystemClock.elapsedRealtime())
                                    playback.lastProgress = SystemClock.elapsedRealtime()
                                    playback.track?.setVolume(1f)
                                    if (resume) {
                                        playback.track?.play()
                                        runCatching { playback.measurements?.trackPlayRequested() }
                                    }
                                }
                            }.exceptionOrNull()
                            if (failed != null) {
                                fail(playback, "audio_focus_resume_failed", "Audio could not resume. Your reply is available in chat.", canFallback = false, cause = failed)
                            } else {
                                delayedAttempt?.let(::startSynthesis)
                            }
                        }
                        KokoroFocusAction.PAUSE -> {
                            playback.trackLock.withLock {
                                playback.focusState = playback.focusState.transition(focusAction, SystemClock.elapsedRealtime())
                                runCatching { playback.track?.pause() }
                            }
                            measuredOutput.value = 0f
                            timing("audio_focus_paused", playback)
                        }
                        KokoroFocusAction.DUCK -> {
                            playback.trackLock.withLock {
                                playback.focusState = playback.focusState.transition(focusAction, SystemClock.elapsedRealtime())
                                runCatching { playback.track?.setVolume(0.2f) }
                            }
                            timing("audio_focus_ducked", playback)
                        }
                        KokoroFocusAction.STOP -> fail(
                            playback,
                            "audio_focus_lost",
                            "Audio stopped because another app needs the speaker. Your reply is available in chat.",
                            canFallback = false
                        )
                        KokoroFocusAction.IGNORE -> Unit
                    }
                }
            }, main).build()
        val watchdog = object : Runnable {
            override fun run() {
                if (active !== playback) return
                if (delayedAttempt != null) {
                    fail(playback, "audio_focus_timeout", "Audio output is still in use. You can replay the reply.", canFallback = false)
                    return
                }
                if (playback.focusState.paused) {
                    if (playback.focusState.pauseExpired(SystemClock.elapsedRealtime())) {
                        fail(playback, "audio_focus_timeout", "Playback was interrupted by another app. You can replay the reply.", canFallback = false)
                    } else {
                        main.postDelayed(this, 1_000)
                    }
                    return
                }
                val progress = runCatching { loaded?.takeIf { it.key == playback.key }?.engine?.executionProgress }.getOrNull()
                progress?.let { runCatching { playback.measurements?.executionProgress(it) } }
                val expired = VoicePlaybackDeadlines.exceeded(
                    progress,
                    SystemClock.elapsedRealtime() - playback.lastProgress,
                    playback.preparing,
                    playback.key.factory.preparationTimeoutMs
                )
                if ((playback.synthesizing || playback.sealed || playback.segments.isNotEmpty()) && expired != null) {
                    fail(playback, "timeout", "Speech playback timed out. Your answer remains in chat.", cause = expired)
                } else {
                    main.postDelayed(this, 1_000)
                }
            }
        }
        playback = Playback(replyId, voice, ModelKey(voice.lexicon, effectiveThreads(voice, speed), speechFactory), focus, watchdog, complete, error, diagnosticProbe, playbackStarted, spokenRange, timedSpokenRange, pause, performance, speed, privacy(), threadCount, measurements?.forPlayback())
        active = playback
        playback.warmAtRequest = reusableModel(playback.key) != null
        measuredSynthesis.value = VoiceSynthesisFeedback(threads = playback.key.threads, warmAtRequest = playback.warmAtRequest, bufferState = "preparing")
        playback.diagnostics?.engine(if (playback.key.factory.selectedBackend.isStreamingLocal) VoiceDiagnosticTrace.Engine.MEDIAPIPE else VoiceDiagnosticTrace.Engine.KOKORO)
        playback.diagnostics?.selectedSpeechBackend(playback.key.factory.selectedBackend.name)
        fun acquireFocus(attempt: Int) {
            if (active !== playback) return
            val focusRequest = runCatching { audioManager?.requestAudioFocus(focus) }
            val focusResult = focusRequest.getOrNull()
            when (focusResult) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> startSynthesis(attempt)
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    delayedAttempt = attempt
                    playback.diagnostics?.playbackFocus(null, attempt)
                    main.postDelayed(watchdog, KOKORO_FOCUS_PAUSE_MAX_MS)
                }
                else -> {
                    val mode = runCatching { audioManager?.mode }.getOrNull()
                    playback.diagnostics?.playbackFocus(
                        if (focusResult == null) VoiceDiagnosticTrace.Focus.ERROR else VoiceDiagnosticTrace.Focus.DENIED,
                        attempt,
                        VoiceDiagnosticTrace.PlaybackAudioMode.observed(mode)
                    )
                    val retryDelay = kokoroFocusRetryDelay(attempt, mode)
                    if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_FAILED && retryDelay != null) {
                        main.postDelayed({ acquireFocus(attempt + 1) }, retryDelay)
                    } else {
                        val message = if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                            "Android denied audio focus. When the call or other audio ends, return to Voice and tap replay on this reply."
                        } else {
                            "Android audio focus is unavailable. Reopen Voice to replay this reply."
                        }
                        main.post { fail(playback, "audio_focus_denied", message, canFallback = false, cause = focusRequest.exceptionOrNull()) }
                    }
                }
            }
        }
        acquireFocus(1)
        return object : VoiceReplyPlayback {
            override fun enqueue(text: String) {
                if (active !== playback || playback.sealed || text.isBlank()) return
                val remaining = MAX_REPLY_CHARS - playback.inputChars
                if (remaining <= 0) return
                val bounded = text.take(remaining).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                if (bounded.isEmpty()) return
                playback.inputChars += bounded.length
                playback.lastProgress = SystemClock.elapsedRealtime()
                if (!playback.segments.offer(bounded)) {
                    fail(playback, "text_queue_full", "The voice playback queue is full. Your answer remains in chat.")
                }
            }

            override fun finish() {
                if (active === playback && !playback.sealed) {
                    playback.sealed = true
                    timing("input_complete", playback)
                }
            }
        }
    }

    private fun synthesize(playback: Playback, speed: Float, playbackAttributes: AudioAttributes) {
        if (active !== playback) return
        val queueWait = SystemClock.elapsedRealtime() - playback.startedAt
        try {
            val wasWarm = reusableModel(playback.key) != null
            playback.diagnostics?.warmupStarted()
            val gateStarted = SystemClock.elapsedRealtime()
            val model = VoiceNativeEngineGate.exclusive {
                val gateWait = SystemClock.elapsedRealtime() - gateStarted
                playback.diagnostics?.preparation("GATE_WAIT", gateWait)
                runCatching { playback.measurements?.preparationPhase("GATE_WAIT", gateWait) }
                val loadStarted = SystemClock.elapsedRealtime()
                loadModelExclusive(playback.key).also {
                    val loadMs = SystemClock.elapsedRealtime() - loadStarted
                    playback.diagnostics?.preparation("MODEL_LOAD", loadMs)
                    runCatching { playback.measurements?.preparationPhase("MODEL_LOAD", loadMs) }
                }
            }
            model.engine.resetCancellation()
            if (active !== playback) return
            playback.preparing = false
            measuredSynthesis.value = VoiceSynthesisFeedback(threads = playback.key.threads, warm = wasWarm, warmAtRequest = playback.warmAtRequest, engineReused = wasWarm, executionProvider = model.engine.executionProvider, bufferState = "preparing")
            playback.diagnostics?.speechProvider(model.engine.executionProvider)
            playback.diagnostics?.engineReady(wasWarm)
            runCatching { playback.measurements?.nativePreparation(SystemClock.elapsedRealtime() - gateStarted, playback.requestedThreads, playback.key.threads) }
            runCatching { playback.measurements?.engineReady(wasWarm) }
            publishExecution(playback, model.engine)
            playback.lastProgress = SystemClock.elapsedRealtime()
            timing("model_ready", playback, "warm=$wasWarm · warmAtRequest=${playback.warmAtRequest} · engineReused=$wasWarm · queueWaitMs=$queueWait")
            val sampleRate = VOICE_PCM_SAMPLE_RATE
            val silence = playback.pause.maxQuietMs?.let { VoiceQuietLimiter(sampleRate, it) }
            output.execute { play(playback, playbackAttributes, sampleRate) }
            var segmentIndex = -1
            // This worker consumes one playback's ordered queue. Its bounded pronunciation
            // context dies with that playback, including cancellation or a failed phrase.
            val speechContext = KokoroSpeechContext()
            while (active === playback) {
                playback.synthesizing = false
                val segment = playback.segments.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
                if (segment == null) {
                    if (playback.sealed && playback.segments.isEmpty()) break
                    continue
                }
                playback.synthesizing = true
                if (model.engine.supportsStreaming && !playback.key.factory.requiresKokoroAssets) {
                    segmentIndex++
                    val plan = VoiceStreamingPhrasePlan(segment, pronunciations, speed)
                    while (active === playback) {
                        val phrase = plan.next(playback.adaptive.phraseWords(bufferedAudioMs(playback))) ?: break
                        if (!streamPhrase(playback, model.engine, segmentIndex, segment, phrase.text, null, speed, phrase.sourceCharacters)) return
                    }
                    if (active !== playback) return
                    if (playback.sealed && playback.segments.isEmpty()) break
                    continue
                }
                val projection = speechContext.project(
                    segment,
                    model.words::contains,
                    pronunciations,
                    playback.performance.followUpWords,
                    playback.onRange != null || playback.onTimedRange != null
                )
                val observation = projection.observation
                playback.knownWords += observation.knownWords
                playback.initialisms += observation.initialisms
                playback.unknownWords += observation.unknownWords
                playback.spelledLetters += observation.spelledLetters
                playback.removedFormatCharacters += observation.removedFormatCharacters
                val phrases = projection.phrases
                if (phrases == null) {
                    playback.diagnostics?.pronunciationFailure(
                        playback.unknownWords,
                        playback.initialisms,
                        playback.queuedAudio,
                        playback.firstFrame,
                        observation.failure,
                        observation.unknownWordLength
                    )
                    timing("pronunciation_unavailable", playback, "reason=${observation.failure?.name ?: "UNKNOWN"} · unknownWords=${playback.unknownWords} · normalizedFormats=${playback.removedFormatCharacters}")
                    main.post {
                        // A verified on-device speech fallback may read the complete buffered reply
                        // only before the Kokoro output head has started. Replaying an
                        // already audible phrase would duplicate private speech.
                        fail(playback, "pronunciation_unavailable", "A word in this reply is not supported by the installed voice. The complete answer is available in chat.", canFallback = !playback.queuedAudio)
                    }
                    return
                }
                segmentIndex++
                val plan = KokoroPhrasePlan(phrases)
                while (true) {
                    if (active !== playback) return
                    val phrase = plan.next(playback.adaptive.phraseWords(bufferedAudioMs(playback))) ?: break
                    if (model.engine.supportsStreaming) {
                        val ranges = phrase.wordRanges.takeIf { playback.onRange != null || playback.onTimedRange != null }
                        if (!streamPhrase(playback, model.engine, segmentIndex, segment, phrase.speech, ranges, speed)) return
                        continue
                    }
                    playback.diagnostics?.synthesisStarted()
                    val synthesisStarted = SystemClock.elapsedRealtime()
                    val generated = model.engine.generate(phrase.speech, playback.voice.speakerId, speed)
                    if (active !== playback) return
                    val provider = model.engine.executionProvider
                    playback.diagnostics?.speechProvider(provider)
                    require(generated.samples.isNotEmpty() && generated.samples.size <= sampleRate * 30 && generated.samples.all(Float::isFinite))
                    require(generated.wordTokens.isNotEmpty() && generated.wordTokens.size % 2 == 0)
                    val synthesisMs = SystemClock.elapsedRealtime() - synthesisStarted
                    val measuredBoundaries = kokoroWordFrames(generated.durations, generated.wordTokens, generated.wordTokens.size / 2, generated.samples.size)
                    val timedCaptions = playback.onRange != null || playback.onTimedRange != null
                    if (timedCaptions) require(phrase.wordRanges.size * 2 == measuredBoundaries.size)
                    publishExecution(playback, model.engine, completedPhrase = true)
                    val boundaries = if (timedCaptions) measuredBoundaries else IntArray(0)
                    val samples = silence?.filter(generated.samples, boundaries) ?: generated.samples
                    val audioMs = samples.size.toLong() * 1_000 / sampleRate
                    runCatching { playback.measurements?.pcmReady(synthesisMs, samples.size, sampleRate, model.engine.executionEvidence) }
                    // Reserve against the retained PCM duration, since trimming silence also
                    // removes time during which the following phrase could be synthesized.
                    playback.adaptive.observe(synthesisMs, audioMs, recurringSpeechCostMs(model.engine.executionEvidence, synthesisMs), generated.wordTokens.size / 2)
                    measuredSynthesis.update {
                        it.copy(synthesisMs = synthesisMs, generatedAudioMs = audioMs, warm = it.warm && it.executionProvider == provider, engineReused = it.engineReused && it.executionProvider == provider, executionProvider = provider)
                    }
                    if (timedCaptions) {
                        playback.captions.append(segmentIndex, segment, phrase.wordRanges, boundaries, samples.size)
                        runCatching { playback.measurements?.captionsPrepared(segmentIndex, phrase.wordRanges) }
                    }
                    publishFeedback(playback)
                    if (!queuePcm(playback, samples, sampleRate, phrase.speech.length)) return
                }
                // A sealed reply has no later input. Publish completion immediately so the
                // final hardware drain cannot be mistaken for an active producer stall.
                if (playback.sealed && playback.segments.isEmpty()) break
            }
            playback.synthesisDone = true
        } catch (_: java.util.concurrent.CancellationException) {
            playback.cancelledAt?.let { stopped -> measuredSynthesis.update { it.copy(cancellationMs = SystemClock.elapsedRealtime() - stopped) } }
        } catch (error: LexiconUnavailable) {
            main.post { fail(playback, "lexicon_unavailable", "The installed voice dictionary is incomplete. Repair the Kokoro voice pack in Voice settings and retry.", canFallback = false, cause = error) }
        } catch (error: Exception) {
            if (error is VoiceSpeechPreparationFailure) {
                publishExecutionEvidence(playback, error.executionEvidence)
            } else {
                loaded?.takeIf { it.key == playback.key }?.engine?.let { publishExecution(playback, it) }
            }
            main.post { fail(playback, "synthesis_failed", "The selected voice could not synthesize this reply. Your answer remains in chat.", cause = error) }
        } catch (error: LinkageError) {
            releaseModel()
            main.post { fail(playback, "engine_failed", "Voice playback is unavailable. Your answer remains in chat.", cause = error) }
        } catch (error: OutOfMemoryError) {
            releaseModel()
            main.post { fail(playback, "memory_pressure", "Voice playback could not allocate audio. Your answer remains in chat.", cause = error) }
        } finally {
            playback.synthesizing = false
            playback.cancelledAt?.let { stopped -> measuredSynthesis.update { it.copy(cancellationMs = SystemClock.elapsedRealtime() - stopped) } }
        }
    }

    /** Pull-style engines cannot outrun the bounded PCM queue; no complete streamed waveform is retained. */
    private fun streamPhrase(playback: Playback, engine: VoicePhraseEngine, segmentIndex: Int, segment: String, text: String, ranges: List<IntRange>?, speed: Float, sourceCharacters: List<IntRange>? = null): Boolean {
        val frames = VoiceStreamFrames(text, ranges, sourceCharacters)
        val streamStart = playback.captions.streamStart()
        val timedCaptions = playback.onRange != null || playback.onTimedRange != null
        val started = SystemClock.elapsedRealtime()
        var callbackMs = 0L
        var lastProducedAt = started
        playback.diagnostics?.synthesisStarted()
        consumeVoiceStream(engine, text, playback.voice.speakerId, speed, { active === playback }) { chunk ->
            val callbackStarted = SystemClock.elapsedRealtime()
            val productionMs = (callbackStarted - lastProducedAt).coerceAtLeast(0)
            val cues = frames.accept(chunk)
            // Native text projection omits source ranges when captions are disabled. Its
            // expanded speech offsets then belong to the normalized input, not the segment.
            playback.captions.appendStream(segmentIndex, segment, streamStart, if (timedCaptions) cues else emptyList(), chunk.samples.size)
            val accepted = if (chunk.samples.isNotEmpty()) {
                runCatching { playback.measurements?.pcmReady(productionMs, chunk.samples.size, chunk.sampleRate, engine.executionEvidence) }
                measuredSynthesis.update {
                    it.copy(synthesisMs = (callbackStarted - started - callbackMs).coerceAtLeast(0), generatedAudioMs = frames.frames * 1_000 / chunk.sampleRate, executionProvider = engine.executionProvider)
                }
                queuePcm(playback, chunk.samples, chunk.sampleRate, text.length)
            } else {
                active === playback
            }
            val returnedAt = SystemClock.elapsedRealtime()
            callbackMs += returnedAt - callbackStarted
            lastProducedAt = returnedAt
            accepted
        }
        if (active !== playback) return false
        frames.finish()
        val synthesisMs = (SystemClock.elapsedRealtime() - started - callbackMs).coerceAtLeast(1)
        val audioMs = frames.frames * 1_000 / VOICE_PCM_SAMPLE_RATE
        playback.adaptive.observe(synthesisMs, audioMs, spokenWords = Regex("\\S+").findAll(text).count())
        publishExecution(playback, engine, completedPhrase = true)
        publishFeedback(playback)
        return true
    }

    /** The synthesis worker owns the phrase. A full queue applies backpressure off main. */
    private fun queuePcm(playback: Playback, samples: FloatArray, sampleRate: Int, segmentChars: Int): Boolean {
        fun offer(batch: VoicePcmSlice): Boolean {
            while (active === playback) {
                if (playback.pcm.offer(batch, POLL_MS, TimeUnit.MILLISECONDS)) {
                    playback.enqueuedFrames.addAndGet(batch.size.toLong())
                    if (active !== playback) {
                        playback.pcm.remove(batch)
                        return false
                    }
                    playback.lastProgress = SystemClock.elapsedRealtime()
                    if (!playback.firstPcm) {
                        playback.firstPcm = true
                        runCatching { playback.measurements?.pcmQueued() }
                        playback.diagnostics?.firstPcm()
                        timing("first_pcm", playback, "segmentChars=$segmentChars · pcmQueueLimitMs=${KokoroPcmBatches.QUEUE_LIMIT_MS}")
                    }
                    return true
                }
            }
            return false
        }
        return KokoroPcmBatches.slices(samples, sampleRate, ::offer)
    }

    private fun play(playback: Playback, playbackAttributes: AudioAttributes, sampleRate: Int) {
        if (active !== playback) return
        try {
            val format = AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minBytes > 0)
            val track = AudioTrack.Builder().setAudioAttributes(playbackAttributes).setAudioFormat(format)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minBytes, sampleRate * 2)).build()
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED)
                val registered = playback.trackLock.withLock {
                    if (active !== playback) return@withLock false
                    playback.track = track
                    if (playback.focusState.ducked) track.setVolume(0.2f)
                    true
                }
                if (!registered) return
                observeUnderruns(track, playback, "startup")
                writeReply(track, playback, sampleRate)
            } finally {
                playback.trackLock.withLock {
                    observeUnderruns(track, playback, force = true)
                    playback.track = null
                    playback.trackPrimed = false
                    silence(track)
                    track.release()
                }
            }
            main.post {
                if (active === playback) {
                    end(playback, "complete")
                    playback.complete()
                }
            }
        } catch (error: Exception) {
            main.post { fail(playback, "audio_failed", "Voice audio output stopped. Your answer remains in chat.", cause = error) }
        }
    }

    private data class PcmEnergy(val startFrame: Long, val endFrame: Long, val level: Float)

    private fun publishExecution(playback: Playback, engine: VoicePhraseEngine, completedPhrase: Boolean = false) {
        val evidence = engine.executionEvidence ?: return
        publishExecutionEvidence(playback, evidence, completedPhrase)
    }

    private fun publishExecutionEvidence(playback: Playback, evidence: VoiceSpeechExecutionEvidence, completedPhrase: Boolean = false) {
        if (active !== playback) return
        playback.diagnostics?.selectedSpeechBackend(evidence.selectedBackend.name)
        measuredSynthesis.update { it.copy(executionEvidence = evidence) }
        timing("execution", playback, evidence.diagnostics().entries.joinToString(" · ") { (key, value) -> "$key=$value" })
        if (evidence.failure != null && evidence.effectiveProvider == "cpu" && evidence.failedProvider in setOf("qnn-gpu", "qnn-htp")) {
            if (playback.nativeFallback == null) {
                val failure = VoicePlaybackFailure(
                    "The selected accelerator could not prepare or execute this phrase.",
                    code = if (evidence.failureStage == VoiceSpeechStage.SYNTHESIS) "SYNTHESIS_FAILED" else "ENGINE_FAILED",
                    sourceProvider = evidence.failedProvider
                )
                playback.nativeFallback = failure
                playback.diagnostics?.playbackFallback(failure, "STARTED", "KOKORO_CPU")
            }
            if (completedPhrase && evidence.verifiedAudio && !playback.nativeFallbackSucceeded) {
                playback.nativeFallbackSucceeded = true
                playback.diagnostics?.playbackFallback(requireNotNull(playback.nativeFallback), "SUCCEEDED", "KOKORO_CPU")
            }
        }
    }

    private fun bufferedAudioMs(playback: Playback): Long =
        (playback.enqueuedFrames.get() - playback.consumedFrames.get()).coerceAtLeast(0) * 1_000 / VOICE_PCM_SAMPLE_RATE

    private fun publishFeedback(playback: Playback) {
        if (active !== playback) return
        val buffered = bufferedAudioMs(playback)
        measuredSynthesis.update {
            it.copy(
                bufferedAudioMs = buffered,
                suggestedFollowUpWords = playback.adaptive.phraseWords(buffered),
                bufferMs = playback.adaptive.bufferMs(startup = !playback.firstFrame),
                productionRatio = playback.adaptive.ratio(),
                predictedNextSynthesisMs = playback.adaptive.predictedSynthesisMs(startup = !playback.firstFrame),
                route = playback.route,
                clockBasis = playback.clockBasis,
                underruns = playback.underruns ?: playback.bufferRestarts
            )
        }
    }

    private fun writeReply(track: AudioTrack, playback: Playback, sampleRate: Int) {
        var framesWritten = 0L
        var playing = false
        var drainedAt: Long? = null
        while (active === playback) {
            val first = playback.pcm.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (first == null) {
                val consumed = playedFrames(track, playback)
                if (playback.synthesisDone && playback.pcm.isEmpty() && consumed >= framesWritten) {
                    if (playback.presentedFrames < framesWritten) continue
                    if (playback.drainAt == null) playback.drainAt = SystemClock.elapsedRealtime()
                    playedFrames(track, playback)
                    val elapsed = SystemClock.elapsedRealtime() - requireNotNull(playback.drainAt)
                    if (elapsed >= playback.captionOffsetMs.coerceAtLeast(0)) return
                    continue
                }
                if (playing && VoicePlaybackClock.drained(framesWritten, consumed, playback.presentedFrames)) {
                    // A hardware timestamp can trail the consumed head. Keep its tail
                    // moving before pausing, otherwise a late end-of-input seals a
                    // paused clock that can never reach the completion boundary.
                    observeUnderruns(track, playback, "active")
                    playback.trackLock.withLock {
                        if (active === playback) track.pause()
                        playback.trackPrimed = false
                    }
                    playing = false
                    playback.refilling = true
                    drainedAt = SystemClock.elapsedRealtime()
                    measuredSynthesis.update { it.copy(bufferState = "refilling") }
                    playback.adaptive.underrun()
                    publishFeedback(playback)
                }
                continue
            }
            if (!playing) awaitReserve(playback, track, sampleRate)
            // Software reserves cover synthesis latency; hardware buffering remains small
            // enough for prompt interruption and cannot exceed this track's capacity.
            val requestedFrames = (sampleRate * playback.performance.bufferMs / 1_000).coerceAtLeast(1)
            val primeFrames = minOf(track.bufferCapacityInFrames, requestedFrames)
            val batches = if (playing) listOf(first) else primePlayback(playback, first, primeFrames)
            val startThreshold = minOf(primeFrames, batches.sumOf { it.size })
            var primeWritten = 0
            if (!playing) {
                track.setBufferSizeInFrames(minOf(track.bufferCapacityInFrames, maxOf(startThreshold * 2, requestedFrames)))
                track.setStartThresholdInFrames(startThreshold)
            }
            for (batch in batches) {
                var offset = 0
                while (offset < batch.size) {
                    val written = playback.trackLock.withLock {
                        if (active !== playback) return
                        track.write(batch.samples, batch.offset + offset, batch.size - offset, AudioTrack.WRITE_NON_BLOCKING).also { count ->
                            if (count > 0 && !playback.firstTrackWrite) {
                                playback.firstTrackWrite = true
                                runCatching { playback.measurements?.firstAudioTrackWrite() }
                            }
                            if (!playing && primeWritten + count >= startThreshold) {
                                playback.trackPrimed = true
                                if (!playback.focusState.paused) {
                                    track.play()
                                    runCatching { playback.measurements?.trackPlayRequested() }
                                }
                                drainedAt?.let { drained ->
                                    playback.bufferRestarts++
                                    playback.longestRefillMs = maxOf(playback.longestRefillMs, SystemClock.elapsedRealtime() - drained)
                                }
                                drainedAt = null
                                playback.queuedAudio = true
                                playing = true
                                playback.refilling = false
                                measuredSynthesis.update { it.copy(bufferState = "playing") }
                            }
                        }
                    }
                    check(written >= 0)
                    if (written == 0) {
                        Thread.sleep(POLL_MS)
                    } else {
                        var squared = 0.0
                        var measured = 0
                        val stride = (written / 64).coerceAtLeast(1)
                        for (i in batch.offset + offset until batch.offset + offset + written step stride) {
                            val sample = batch.samples[i].takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
                            squared += sample * sample
                            measured++
                        }
                        val startFrame = framesWritten
                        offset += written
                        primeWritten += written
                        framesWritten += written
                        playback.energyWindows.addLast(PcmEnergy(startFrame, framesWritten, kotlin.math.sqrt(squared / measured).toFloat()))
                        if (playback.energyWindows.size > 512) playback.energyWindows.removeFirst()
                        playback.lastProgress = SystemClock.elapsedRealtime()
                    }
                    playedFrames(track, playback)
                }
            }
        }
    }

    /** Wait with the bounded queue intact. Moving it into a second list would double its limit. */
    private fun awaitReserve(playback: Playback, track: AudioTrack, sampleRate: Int) {
        val startup = !playback.firstFrame
        val phase = if (startup) "startup" else "refill"
        measuredSynthesis.update { it.copy(bufferState = if (startup) "preparing" else "refilling") }
        val started = SystemClock.elapsedRealtime()
        val deadline = started + playback.adaptive.primeWaitMs(startup)
        val target = sampleRate.toLong() * playback.adaptive.bufferMs(startup) / 1_000
        val exit = KokoroPcmBatches.awaitReserve(
            queue = playback.pcm,
            targetFrames = target,
            acceptedFrames = { playback.enqueuedFrames.get() - playback.consumedFrames.get() },
            canWait = { active === playback && !playback.synthesisDone },
            producerPending = { playback.synthesizing || playback.segments.isNotEmpty() },
            deadlineReached = { SystemClock.elapsedRealtime() >= deadline },
            poll = {
                playedFrames(track, playback)
                Thread.sleep(POLL_MS)
            }
        )
        val reason = if (exit == VoicePcmReserveExit.STOPPED) {
            if (playback.synthesisDone) "producer_done" else "cancelled"
        } else {
            exit.name.lowercase(java.util.Locale.ROOT)
        }
        runCatching {
            playback.measurements?.reserveCompleted(phase, SystemClock.elapsedRealtime() - started, target * 1_000 / sampleRate, bufferedAudioMs(playback), reason)
        }
    }

    /** Priming keeps views of the existing phrase; it never joins/copies PCM arrays. */
    private fun primePlayback(playback: Playback, first: VoicePcmSlice, targetFrames: Int): List<VoicePcmSlice> {
        val batches = mutableListOf(first)
        var frames = first.size
        val waitBudget = if (playback.performance == VoicePerformanceMode.AUTO) 80L else playback.performance.refillWaitMs
        val deadline = SystemClock.elapsedRealtime() + waitBudget
        while (frames < targetFrames && active === playback) {
            var next = playback.pcm.poll()
            if (next == null) {
                val wait = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(POLL_MS)
                if (wait <= 0 || playback.synthesisDone) break
                next = playback.pcm.poll(wait, TimeUnit.MILLISECONDS) ?: continue
            }
            batches += next
            frames += next.size
        }
        return batches
    }

    private fun loadModel(key: ModelKey): LoadedModel = VoiceNativeEngineGate.exclusive { loadModelExclusive(key) }

    private fun reusableModel(key: ModelKey): LoadedModel? = loaded?.takeIf {
        it.key == key && (it.engine as? VoiceFailoverPhraseEngine)?.canReuse != false
    }

    private fun loadModelExclusive(key: ModelKey): LoadedModel {
        check(!closed)
        reusableModel(key)?.let { return it }
        releaseModel()
        check(key.factory.isReady()) { "Speech model is not ready" }
        val leasedKokoroAssets = key.factory.requiresKokoroAssets
        if (leasedKokoroAssets) check(models.acquireUse())
        val engineId = java.util.UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        AppLogRecorder.record("VoiceEngine", "kokoro_start · engineId=$engineId · threads=${key.threads}")
        try {
            val directory = if (leasedKokoroAssets) models.modelDirectory() else application.noBackupFilesDir
            val words = if (leasedKokoroAssets) {
                val lexicon = File(directory, key.lexicon)
                lexicon.useLines(block = KokoroLexicon::words).also {
                    val missingRequiredWords = KokoroLexicon.missingRequiredWords(it)
                    AppLogRecorder.record("VoiceEngine", "kokoro_lexicon · engineId=$engineId · lexicon=${key.lexicon} · entries=${it.size} · missingRequiredWords=$missingRequiredWords · projectionVersion=2")
                    if (missingRequiredWords != 0) throw LexiconUnavailable()
                }
            } else {
                emptySet()
            }
            val engine = key.factory.create(directory, key.lexicon, key.threads)
            AppLogRecorder.record("VoiceEngine", "kokoro_ready · engineId=$engineId · threads=${key.threads} · durationMs=${SystemClock.elapsedRealtime() - started}")
            return LoadedModel(key, engine, words, leasedKokoroAssets).also { loaded = it }
        } catch (error: Throwable) {
            // Exception text may contain speech or paths. Retain finite cause identity
            // here; the typed throwable remains attached to the originating failure.
            val kind = error.javaClass.simpleName.take(80).filter { it.isLetterOrDigit() || it == '_' }
            val cause = error.cause?.javaClass?.simpleName?.take(80)?.filter { it.isLetterOrDigit() || it == '_' } ?: "none"
            AppLogRecorder.record("VoiceEngine", "kokoro_preparation_failed · engineId=$engineId · stage=MODEL_PREPARATION · type=$kind · causeType=$cause", "W")
            throw error
        } finally {
            if (loaded == null) AppLogRecorder.record("VoiceEngine", "kokoro_failure · engineId=$engineId · durationMs=${SystemClock.elapsedRealtime() - started}", "W")
            if (loaded == null && leasedKokoroAssets) models.releaseUse()
        }
    }

    private fun playedFrames(track: AudioTrack, playback: Playback): Long {
        val head = Integer.toUnsignedLong(track.playbackHeadPosition)
        if (head < playback.lastPlaybackHead && playback.lastPlaybackHead - head > (1L shl 31)) playback.playbackHeadWraps++
        playback.lastPlaybackHead = head
        val frames = (playback.playbackHeadWraps shl 32) + head
        playback.consumedFrames.set(frames)
        val now = SystemClock.elapsedRealtime()
        val route = track.routedDevice
        if (route != null && route.id != playback.routeId) {
            playback.routeId = route.id
            playback.route = routeName(route.type)
            playback.clock.routeChanged()
            playback.hasTimestamp = false
            playback.nextTimestampAt = 0
            playback.captionOffsetMs = captionCalibration.resolve(captionOffset) { calibration.getInt("caption:${playback.route}", captionOffset) }
            runCatching { playback.measurements?.calibration(playback.route, playback.captionOffsetMs, playback.clock.routeEpoch) }
            measuredSynthesis.update { it.copy(captionOffsetMs = playback.captionOffsetMs) }
            timing("route_changed", playback, "route=${playback.route} · captionOffsetMs=${playback.captionOffsetMs}")
            publishFeedback(playback)
        }
        val trackPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
        if (playback.timestampPlaying != null && playback.timestampPlaying != trackPlaying) {
            playback.hasTimestamp = false
            playback.nextTimestampAt = 0
        }
        playback.timestampPlaying = trackPlaying
        // Android recommends occasional timestamp sampling plus monotonic interpolation.
        // Probe startup/route changes until a timestamp exists; a stable route needs only
        // one binder/native timestamp read per ten seconds, not one per output write.
        if (trackPlaying && now >= playback.nextTimestampAt) {
            playback.hasTimestamp = runCatching { track.getTimestamp(playback.timestamp) }.getOrDefault(false)
            playback.nextTimestampAt = now + if (playback.hasTimestamp) 10_000 else 100
        }
        val observedAtNanos = System.nanoTime()
        val position = playback.clock.position(
            frames,
            playback.timestamp.framePosition.takeIf { playback.hasTimestamp },
            playback.timestamp.nanoTime.takeIf { playback.hasTimestamp },
            observedAtNanos,
            trackPlaying
        )
        playback.clockBasis = position.basis
        val presented = position.frames
        playback.presentedFrames = presented
        observeUnderruns(track, playback)
        if (now - playback.lastFeedbackUpdate >= 100) {
            playback.lastFeedbackUpdate = now
            publishFeedback(playback)
        }
        if (presented > 0 && now - playback.lastEnergyUpdate >= 34 && active === playback) {
            while (playback.energyWindows.firstOrNull()?.let { it.endFrame <= presented } == true) playback.energyWindows.removeFirst()
            val level = playback.energyWindows.firstOrNull()?.takeIf { presented >= it.startFrame }?.level ?: 0f
            playback.lastEnergyUpdate = now
            main.post { if (active === playback) measuredOutput.value = playback.focusState.outputLevel(level) }
        }
        if (presented > 0 && !playback.firstFrame) {
            playback.firstFrame = true
            runCatching { playback.measurements?.firstPresented(position.basis, playback.route) }
            playback.diagnostics?.firstAudio(if (position.basis == "audio_timestamp") VoiceDiagnosticTrace.FirstAudio.AUDIO_TIMESTAMP else VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
            measuredSynthesis.update { it.copy(firstAudioMs = now - playback.startedAt, clockBasis = position.basis, route = playback.route) }
            timing("first_audio", playback, "clockBasis=${position.basis}")
            main.post {
                if (!closed && active === playback) playback.audioStarted?.invoke()
            }
        }
        val accepted = playback.enqueuedFrames.get()
        val advancing = trackPlaying && presented > 0 || playback.drainAt != null
        val captionFrames = if (advancing) {
            maxOf(
                playback.lastCaptionFrame,
                VoicePlaybackClock.captionFrames(
                    presented,
                    VOICE_PCM_SAMPLE_RATE,
                    playback.captionOffsetMs,
                    playback.drainAt?.let { now - it } ?: 0,
                    accepted
                )
            )
        } else {
            playback.lastCaptionFrame
        }
        playback.lastCaptionFrame = captionFrames
        // Neither an ahead calibration offset nor newly generated PCM may move a
        // paused word. Do not consume the first cue before the actual first frame.
        val changedCue = playback.captions.consumed(captionFrames, advancing)
        val canAdvance = VoicePlaybackClock.captionsAdvancing(trackPlaying, presented, captionFrames, accepted, playback.drainAt != null)
        val refresh = changedCue != null || playback.captionPlaying != canAdvance || canAdvance && now - playback.lastCaptionUpdate >= 100
        playback.captionPlaying = canAdvance
        if (refresh) {
            playback.captions.active()?.let { cue ->
                playback.lastCaptionUpdate = now
                val snapshot = VoiceCaptionTiming(
                    playback.id, cue.frame, cue.endFrame, captionFrames, VOICE_PCM_SAMPLE_RATE,
                    observedAtNanos, canAdvance, playback.clock.routeEpoch,
                    maxOf(captionFrames, accepted), cue.provenance
                )
                main.post {
                    if (active === playback && !closed) {
                        val timed = playback.onTimedRange
                        if (timed != null) {
                            timed(cue.segment, cue.text, cue.range, snapshot)
                        } else if (changedCue != null) {
                            playback.onRange?.invoke(cue.segment, cue.text, cue.range)
                        }
                    }
                }
            }
        }
        return frames
    }

    private fun routeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        else -> "type_$type"
    }

    private fun timing(stage: String, playback: Playback, detail: String = "") {
        val fields = if (detail.isBlank()) "" else " · $detail"
        AppLogRecorder.record(
            "VoiceTiming",
            "kokoro_$stage · playbackId=${playback.id} · nativeOperationId=${playback.id} · runId=${playback.runId ?: "not_recorded"} · voice=${playback.voice.name} · threads=${playback.key.threads} · durationMs=${SystemClock.elapsedRealtime() - playback.startedAt}$fields"
        )
    }

    private fun silence(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
    }

    private fun observeUnderruns(track: AudioTrack, playback: Playback, observedPhase: String? = null, force: Boolean = false) = playback.trackLock.withLock {
        val now = SystemClock.elapsedRealtime()
        if (observedPhase == null && !force && (playback.measurements == null || now - playback.lastUnderrunObservation < 100)) return@withLock
        playback.lastUnderrunObservation = now
        val total = runCatching { track.underrunCount }.getOrNull()?.takeIf { it >= 0 }
        playback.underruns = total?.let { maxOf(it, playback.underruns ?: 0) } ?: playback.underruns
        if (total == null) return@withLock
        if (!playback.underrunsAvailable) {
            playback.underrunsAvailable = true
            for (phase in listOf("startup", "active", "refill", "draining", "paused", "cancelled")) {
                runCatching { playback.measurements?.underrunObserved(0, phase) }
            }
        }
        val delta = playback.underrunCounter.observe(total)
        if (delta > 0) {
            val phase = observedPhase ?: VoicePlaybackUnderruns.phase(
                playback.firstFrame,
                playback.synthesisDone,
                playback.enqueuedFrames.get(),
                playback.consumedFrames.get(),
                playback.refilling,
                playback.focusState.paused
            )
            runCatching { playback.measurements?.underrunObserved(delta, phase) }
        }
    }

    private fun releaseModel() {
        VoiceNativeEngineGate.exclusive {
            val model = loaded ?: return@exclusive
            loaded = null
            try {
                runCatching { model.engine.close() }.onFailure {
                    AppLogRecorder.record("VoiceEngine", "kokoro_release_failed · type=${it.javaClass.simpleName}", "W")
                }
            } finally {
                if (model.leasedKokoroAssets) models.releaseUse()
            }
        }
    }

    override fun releaseWarmModel() {
        if (closed) return
        speechFactory.cancelPending()
        if (calibrating) {
            calibrationTicket++
            runCatching { loaded?.engine?.cancel() }
        }
        warmupGeneration++
        warmupRequest = null
        synthesis.execute(::releaseModel)
    }

    private fun end(playback: Playback, reason: String, cancellationOrigin: String? = null) {
        if (active !== playback) return
        playback.cancelledAt = SystemClock.elapsedRealtime()
        playback.key.factory.cancelPending()
        active = null
        runCatching { loaded?.engine?.cancel() }
        measuredOutput.value = null
        measuredSynthesis.update { it.copy(bufferState = "idle") }
        main.removeCallbacks(playback.watchdog)
        playback.segments.clear()
        playback.pcm.clear()
        val underruns = playback.trackLock.withLock {
            playback.track?.let { track ->
                observeUnderruns(track, playback, if (reason == "complete") "draining" else "cancelled")
                silence(track)
            }
            playback.underruns
        }
        runCatching { audioManager?.abandonAudioFocusRequest(playback.focus) }
        runCatching { playback.measurements?.terminal(underruns, playback.bufferRestarts, playback.longestRefillMs, playback.clockBasis, playback.route) }
        timing("terminal", playback, "reason=$reason · cancellationOrigin=${cancellationOrigin ?: "none"} · textChars=${playback.inputChars} · firstPcm=${playback.firstPcm} · firstOutputFrame=${playback.firstFrame} · inputComplete=${playback.sealed} · drainComplete=${reason == "complete"} · underruns=${underruns ?: "unmeasured"} · bufferRestarts=${playback.bufferRestarts} · longestRefillMs=${playback.longestRefillMs} · rate=${playback.rate} · performance=${playback.performance.name} · pause=${playback.pause.name} · knownWords=${playback.knownWords} · initialisms=${playback.initialisms} · unknownWords=${playback.unknownWords} · spelledLetters=${playback.spelledLetters} · normalizedFormats=${playback.removedFormatCharacters}")
    }

    private fun fail(playback: Playback, reason: String, message: String, canFallback: Boolean = !playback.queuedAudio, cause: Throwable? = null) {
        if (active !== playback) return
        playback.diagnostics?.playbackFailure(reason.uppercase(java.util.Locale.ROOT))
        playback.nativeFallback?.takeUnless { playback.nativeFallbackSucceeded }?.let {
            playback.diagnostics?.playbackFallback(it, "FAILED", "KOKORO_CPU")
        }
        end(playback, reason)
        playback.error(VoicePlaybackFailure(message, cause, reason.uppercase(java.util.Locale.ROOT), loaded?.engine?.executionProvider), canFallback)
    }

    override fun stop() = stop("audio_stop")

    override fun stop(reason: String) {
        speechFactory.cancelPending()
        calibrationTicket++
        if (calibrating) runCatching { loaded?.engine?.cancel() }
        warmupGeneration++
        warmupRequest = null
        val origin = reason.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "unspecified"
        active?.let { end(it, "interrupted", origin) }
    }

    override fun close() {
        if (closed) return
        active?.let { end(it, "session_closed") }
        releaseWarmModel()
        closed = true
        application.unregisterComponentCallbacks(memoryCallbacks)
        synthesis.shutdown()
        output.shutdown()
    }

    /** A benchmark keeps its audio lease until both native synthesis and playback have actually retired. */
    internal fun awaitClosed(timeoutMs: Long): Boolean {
        check(closed)
        val synthesisStopped = synthesis.awaitTermination(timeoutMs.coerceIn(1, 1_000), TimeUnit.MILLISECONDS)
        val playbackStopped = output.awaitTermination(timeoutMs.coerceIn(1, 1_000), TimeUnit.MILLISECONDS)
        return synthesisStopped && playbackStopped
    }

    private companion object {
        const val MAX_REPLY_CHARS = 12_100
        const val MAX_SEGMENTS = MAX_REPLY_CHARS
        const val POLL_MS = 12L
        const val INPUT_WAIT_MS = 100L
    }
}
