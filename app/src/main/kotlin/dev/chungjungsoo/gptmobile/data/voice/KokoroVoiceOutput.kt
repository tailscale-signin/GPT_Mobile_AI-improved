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
import dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareGovernor
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private var threads = VoiceCpuSchedulingPolicy.AUTO
    private var pause = VoicePauseDuration.SHORT
    private var performance = VoicePerformanceMode.BALANCED
    private var replyId: String? = null
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    private var spokenRange: SpokenRangeObserver? = null
    private var timedSpokenRange: TimedSpokenRangeObserver? = null
    private var playbackStarted: (() -> Unit)? = null
    private var delegate: KokoroOutput? = null
    private var retiring: KokoroOutput? = null
    private var closeFailed = false
    private var pronunciations: Map<String, String> = emptyMap()
    private var pronunciationLanguage = "en"
    private var captionOffset = 0
    private var captionAlignmentEnabled = true
    private var pendingCaptionCalibration: Int? = null
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
                output.configurePronunciation(pronunciations, pronunciationLanguage)
                output.configureCaptionOffset(captionOffset)
                output.configureCaptionAlignment(captionAlignmentEnabled)
                pendingCaptionCalibration?.let(output::calibrateCaptionOffset)
                pendingCaptionCalibration = null
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
        val bounded = VoiceCpuSchedulingPolicy.choice(value)
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

    override fun configurePronunciation(overrides: Map<String, String>, language: String) {
        synchronized(lock) {
            pronunciations = VoicePronunciationOverrides.clean(overrides)
            pronunciationLanguage = language
            delegate?.configurePronunciation(pronunciations, language)
        }
    }

    override fun configureCaptionOffset(milliseconds: Int) {
        synchronized(lock) {
            captionOffset = milliseconds.coerceIn(-500, 500)
            if (pendingCaptionCalibration != captionOffset) pendingCaptionCalibration = null
            delegate?.configureCaptionOffset(captionOffset)
        }
    }

    override fun configureCaptionAlignment(enabled: Boolean) {
        synchronized(lock) {
            if (closed) return
            captionAlignmentEnabled = enabled
            delegate?.configureCaptionAlignment(enabled)
        }
    }

    override fun calibrateCaptionOffset(milliseconds: Int) {
        synchronized(lock) {
            if (closed) return
            captionOffset = milliseconds.coerceIn(-500, 500)
            val output = delegate
            if (output == null) pendingCaptionCalibration = captionOffset else output.calibrateCaptionOffset(captionOffset)
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

    override fun prewarm(voice: KokoroVoice, assetsOnly: Boolean) {
        output()?.prewarm(voice, assetsOnly)
    }

    override fun cancelPrewarm() {
        existing()?.cancelPrewarm()
    }

    override fun releaseWarmModel() {
        existing()?.releaseWarmModel()
    }

    override fun releaseWarmModel(completed: () -> Unit) {
        val current = synchronized(lock) {
            if (closed || closeFailed) return
            delegate ?: run {
                // A never-created owner has nothing to retire. A failed factory
                // attempt is not proof that partially initialized work retired.
                if (!attempted && retiring == null) completed()
                return
            }
        }
        current.releaseWarmModel {
            synchronized(lock) {
                if (!closed && !closeFailed && delegate === current && retiring == null) completed()
            }
        }
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
            delegate.also {
                retiring = it
                delegate = null
            }
        }
        runCatching { current?.close() }.onFailure { synchronized(lock) { closeFailed = true } }
    }

    override fun awaitClosed(timeoutMs: Long): Boolean {
        val current = synchronized(lock) {
            check(closed)
            if (closeFailed) return false
            retiring
        }
        if (current?.awaitClosed(timeoutMs) == false) return false
        synchronized(lock) { if (retiring === current) retiring = null }
        return true
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
    private val calibration by lazy { application.getSharedPreferences(VoiceCpuCalibrationProtocol.PREFERENCES, Context.MODE_PRIVATE) }

    @Volatile private var calibrationLoaded = false
    override val synthesisFeedback: kotlinx.coroutines.flow.StateFlow<VoiceSynthesisFeedback> = measuredSynthesis
    private val main = Handler(Looper.getMainLooper())
    private val synthesis = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroSynthesis").apply { isDaemon = true } }
    private val output = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroPlayback").apply { isDaemon = true } }

    @Volatile private var closed = false

    @Volatile private var warmupGeneration = 0L

    @Volatile private var loaded: LoadedModel? = null

    @Volatile private var quarantinedModel: LoadedModel? = null

    @Volatile private var quarantinedPreparation: VoicePhraseEngineFactory? = null

    @Volatile private var speechFactory: VoicePhraseEngineFactory = TimedKokoroPhraseEngineFactory

    @Volatile private var pronunciations = VoicePronunciationDictionary.EMPTY

    @Volatile private var captionOffset = 0

    @Volatile private var captionAlignmentEnabled = true
    private val captionCalibration = VoiceCaptionCalibration()
    private val routeCalibration = VoiceCaptionRouteCalibration()

    @Volatile private var calibrationTicket = 0L

    @Volatile private var calibrating = false

    @Volatile private var active: Playback? = null

    @Volatile private var warmupRequest: WarmupKey? = null

    // Main-thread handoff from an unheard acknowledgment to the immediately following answer.
    private var preparingForAnswer: ModelKey? = null

    private val silentWarmup = VoiceSilentWarmup()

    private var threadChoice = VoiceCpuSchedulingPolicy.AUTO
    private val threadCount get() = VoiceCpuSchedulingPolicy.initial(threadChoice)
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
        val bounded = VoiceCpuSchedulingPolicy.choice(value)
        if (threadChoice == bounded) return
        threadChoice = bounded
        releaseWarmModel()
    }

    override fun configurePronunciation(overrides: Map<String, String>, language: String) {
        pronunciations = VoicePronunciationDictionary(overrides, language)
    }

    /** Capture this preference for the next playback without resetting an active word or engine. */
    override fun configureCaptionAlignment(enabled: Boolean) {
        captionAlignmentEnabled = enabled
    }

    /** Hydrates the global default without changing any device-specific saved calibration. */
    override fun configureCaptionOffset(milliseconds: Int) {
        captionCalibration.useSavedRoutes()
        val bounded = milliseconds.coerceIn(-500, 500)
        captionOffset = bounded
        routeCalibration.configureDefault(bounded)
        val playback = active
        if (playback?.captionRouteKey == null) playback?.captionOffsetMs = bounded
        measuredSynthesis.update { it.copy(captionOffsetMs = playback?.captionOffsetMs ?: bounded) }
    }

    /** The next AudioTrack observation owns this edit, even if the device just changed while idle. */
    override fun calibrateCaptionOffset(milliseconds: Int) {
        if (closed) return
        val bounded = milliseconds.coerceIn(-500, 500)
        captionCalibration.useSavedRoutes()
        captionOffset = bounded
        routeCalibration.request(bounded)
        active?.captionOffsetMs = bounded
        measuredSynthesis.update { it.copy(captionOffsetMs = bounded) }
    }

    private fun persistCaptionCalibration(write: VoiceCaptionRouteCalibration.Write?) {
        if (write == null || closed) return
        runCatching { synthesis.execute { calibration.edit().putInt(write.routeKey, write.offsetMs).apply() } }
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
        val residencyChanged = factory.residentIdentity != speechFactory.residentIdentity
        speechFactory = factory
        if (residencyChanged) releaseWarmModel()
    }

    private fun calibrationKey(voice: KokoroVoice, speed: Float) = VoiceCpuCalibrationProtocol.key(speechFactory.cpuCalibrationFactory.identity, voice, speed)

    private fun effectiveThreads(voice: KokoroVoice, speed: Float): Int {
        val adaptive = threadChoice == VoiceCpuSchedulingPolicy.AUTO && speechFactory.selectedBackend.supportsCpuThreadCalibration
        val hardware = if (adaptive) DeviceHardwareGovernor.inspectHardwareState(application) else null
        return VoiceCpuSchedulingPolicy.threads(
            threadCount,
            if (adaptive && calibrationLoaded) calibration.getInt(calibrationKey(voice, speed), 0).takeIf { it in VoiceCpuSchedulingPolicy.supported } else null,
            adaptive,
            hardware?.isModeratePressure == true,
            hardware?.isThrottlingRequired == true
        )
    }

    override fun benchmark(voice: KokoroVoice, completed: (String) -> Unit) {
        if (closed || !models.isInstalled()) return completed("Install the voice model before calibration.")
        if (active != null || calibrating) return completed("Pause playback and finish the current calibration first.")
        if (!speechFactory.selectedBackend.supportsCpuThreadCalibration) return completed("Select on-device CPU or Auto speech to calibrate CPU threads.")
        if ((powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE) return completed("Let the phone cool before calibrating speech.")
        cancelPrewarm()
        calibrating = true
        val ticket = ++calibrationTicket
        val selectionIdentity = speechFactory.identity
        val factory = speechFactory.cpuCalibrationFactory
        val speed = VoiceCpuCalibrationProtocol.rate(rate())
        val key = VoiceCpuCalibrationProtocol.key(factory.identity, voice, speed)
        val capturedPrivacy = privacy()
        synthesis.execute {
            var result = "Speech calibration was cancelled."
            try {
                ConversationPrivacyContext.withCaptured(capturedPrivacy) {
                    val seed = calibration.getInt(VoiceCpuCalibrationProtocol.ORDER_KEY, 0)
                    val processors = VoiceCpuSchedulingPolicy.deviceLimit()
                    val cycle = VoiceCpuCalibrationProtocol.candidates(processors).size * 2
                    calibration.edit().putInt(VoiceCpuCalibrationProtocol.ORDER_KEY, (Math.floorMod(seed, cycle) + 1) % cycle).apply()
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
                            nanoTime = SystemClock::elapsedRealtimeNanos,
                            processors = processors
                        )
                    }
                    if (!closed && calibrationTicket == ticket && active == null && speechFactory.identity == selectionIdentity && powerManager?.currentThermalStatus in 0..1) {
                        val selected = VoiceCpuCalibration.select(samples)
                        calibration.edit().putInt(key, selected).apply()
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

    internal data class ModelKey(val lexicon: String, val threads: Int, val factory: VoicePhraseEngineFactory) {
        fun sameResident(other: ModelKey): Boolean = factory.residentIdentity == other.factory.residentIdentity &&
            factory.requiresKokoroAssets == other.factory.requiresKokoroAssets &&
            (!factory.selectedBackend.isCompiledPocket || threads == other.threads) &&
            (!factory.requiresKokoroAssets || lexicon == other.lexicon && threads == other.threads)
    }
    private data class WarmupKey(val model: ModelKey, val speaker: Int, val rateBits: Int, val assetsOnly: Boolean, val performance: VoicePerformanceMode)
    private data class NativePhraseInFlight(val startedAt: Long, val words: Int)

    // The pack and its pinned digest belong to this output. Accessed only on the synthesis worker.
    private var preparedLexicon: Pair<String, Set<String>>? = null
    private class LexiconUnavailable : IllegalStateException()
    private class LoadedModel(val key: ModelKey, val engine: VoicePhraseEngine, val words: Set<String>, val leasedKokoroAssets: Boolean) {
        val warmupIdentity = Any()
    }

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
        val captionAlignmentEnabled: Boolean,
        val pause: VoicePauseDuration,
        val performance: VoicePerformanceMode,
        val rate: Float,
        val privacy: ConversationPrivacyContext?,
        val pronunciations: VoicePronunciationDictionary,
        val requestedThreads: Int,
        val measurements: VoicePlaybackMeasurementObserver?
    ) {
        val id = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val captions = VoicePcmCaptions()
        val segments = VoiceReplyTextQueue(MAX_SEGMENTS)
        val pcm = ArrayBlockingQueue<VoicePcmSlice>(KokoroPcmBatches.QUEUE_BATCHES)
        val firstPcmOrTerminal = CountDownLatch(1)
        val adaptive = VoiceAdaptivePlayback(performance)

        @Volatile var nativePhrase: NativePhraseInFlight? = null
        val enqueuedFrames = AtomicLong()
        val consumedFrames = AtomicLong()
        var presentedFrames = 0L
        var lastCaptionFrame = 0L
        var lastCaptionUpdate = 0L
        var captionPlaying: Boolean? = null
        val clockInterrupted = AtomicBoolean()
        var timestampPlaying: Boolean? = null
        var hasTimestamp = false
        var nextTimestampAt = 0L
        val timestamp = AudioTimestamp()
        val clock = VoicePlaybackClock(VOICE_PCM_SAMPLE_RATE)
        var routeId = -1

        @Volatile var route = "unknown"

        @Volatile var captionRouteKey: String? = null
        var clockBasis = "unknown"

        @Volatile var captionOffsetMs = 0
        val drain = VoicePlaybackDrain()

        @Volatile var cancelledAt: Long? = null

        // D8 can emit invalid monitor regions for inlined synchronized blocks in playback cleanup.
        val trackLock = ReentrantLock()

        @Volatile var lastProgress = startedAt

        @Volatile var sealed = false

        @Volatile var synthesizing = true

        @Volatile var awaitingIncrementalText = false

        @Volatile var incrementalText: VoiceReplyStreamText? = null

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
            calibration.contains(VoiceCpuCalibrationProtocol.ORDER_KEY)
            calibrationLoaded = true
        }
    }

    private fun trimMemory() {
        main.post {
            active?.let { playback -> fail(playback, "memory_pressure", "Kokoro released its model to free memory. You can replay the reply.") }
            releaseWarmModel()
        }
    }

    override fun cancelPrewarm() {
        preparingForAnswer = null
        warmupGeneration++
        warmupRequest = null
        silentWarmup.cancel()
        if (active == null && !calibrating) speechFactory.cancelPending()
    }

    override fun prewarm(voice: KokoroVoice, assetsOnly: Boolean) = prepareWarmup(voice, assetsOnly, true, null)

    internal fun prewarmForBenchmark(voice: KokoroVoice, mode: VoiceSpeechPrewarmMode, done: (Boolean) -> Unit) =
        prepareWarmup(voice, false, mode == VoiceSpeechPrewarmMode.FIRST_INFERENCE, done)

    private fun prepareWarmup(voice: KokoroVoice, assetsOnly: Boolean, infer: Boolean, done: ((Boolean) -> Unit)?) {
        val factory = speechFactory
        if (closed || active != null || calibrating || !factory.isReady() || factory.requiresKokoroAssets && !models.isInstalled()) {
            done?.invoke(false)
            return
        }
        val speed = VoiceCpuCalibrationProtocol.rate(rate())
        val key = WarmupKey(ModelKey(voice.lexicon, threadCount, factory), voice.speakerId, speed.toBits(), assetsOnly, performance)
        if (warmupRequest == key) {
            done?.invoke(false)
            return
        }
        warmupRequest = key
        val generation = ++warmupGeneration
        val capturedPrivacy = privacy()
        synthesis.execute {
            var prepared = false
            try {
                if (warmupRequest != key || warmupGeneration != generation) return@execute
                ConversationPrivacyContext.withCaptured(capturedPrivacy) {
                    // Parse the large verified pronunciation dictionary before waiting behind Moonshine.
                    // The real model still acquires its own validated installation lease before JNI opens it.
                    if (factory.requiresKokoroAssets && reusableModel(key.model) == null && preparedLexicon?.first != voice.lexicon) {
                        if (!models.acquireUse()) return@withCaptured
                        try {
                            readLexicon(voice.lexicon)
                        } finally {
                            models.releaseUse()
                        }
                    }
                    if (assetsOnly) {
                        if (reusableModel(key.model) == null) {
                            fun current() = warmupGeneration == generation && !closed
                            if (!factory.requiresKokoroAssets) factory.prefetchAssets(::current)
                            if (current()) factory.preloadRuntime()
                        }
                        return@withCaptured
                    }
                    if (warmupGeneration != generation) return@withCaptured
                    VoiceNativeEngineGate.exclusive {
                        if (!closed && active == null && !calibrating && warmupRequest == key && warmupGeneration == generation) {
                            // Preference hydration precedes this task on the same worker. Resolve
                            // its measured thread choice now, instead of warming the startup fallback.
                            val resolved = key.model.copy(threads = effectiveThreads(voice, speed))
                            val model = loadModelExclusive(resolved)
                            prepared = !infer || warmFirstInference(model, voice, speed, generation, factory.identity)
                        }
                    }
                }
            } catch (_: Exception) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } catch (_: LinkageError) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } catch (_: OutOfMemoryError) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } finally {
                done?.invoke(prepared && warmupGeneration == generation)
            }
        }
    }

    private fun warmFirstInference(model: LoadedModel, voice: KokoroVoice, speed: Float, generation: Long, configurationId: String): Boolean {
        val selected = model.key.factory.selectedBackend
        val nativeCpu = (selected == VoiceSpeechBackend.CPU || selected == VoiceSpeechBackend.AUTO) && model.engine.executionProvider == "cpu"
        if ((!nativeCpu && !selected.isLiteRtKokoro) || powerManager?.currentThermalStatus !in 0..1) return false
        val hardware = DeviceHardwareGovernor.inspectHardwareState(application)
        if (hardware.isModeratePressure || hardware.isThrottlingRequired) return false
        fun authorized() = !closed && active == null && !calibrating && warmupGeneration == generation && loaded === model
        val attempt = silentWarmup.begin(model.warmupIdentity, voice.speakerId, speed, configurationId, ::authorized, model.engine::resetCancellation, model.engine::cancel) ?: return false
        val deadline = Runnable { silentWarmup.cancel(attempt) }
        main.postDelayed(deadline, VoiceSilentWarmup.CANCELLATION_DEADLINE_MS)
        var succeeded = false
        try {
            if (silentWarmup.isCurrent(attempt, ::authorized)) succeeded = model.engine.warmFirstInference(voice.speakerId, speed)
        } finally {
            silentWarmup.finish(attempt, succeeded, ::authorized)
            main.removeCallbacks(deadline)
        }
        return succeeded
    }

    override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) =
        speakWithFailure(text, voice, complete) { failure, canFallback -> error(failure.message, canFallback) }

    override fun speakWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit) {
        openReplyWithFailure(voice, complete, error)?.let {
            VoiceSpeechBuffer(preserveLiteralTerms = true).append(text, final = true).orEmpty().forEach(it::enqueue)
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
        // A queued or active optional inference yields to the actual reply. Its
        // worker still owns the model until native cancellation has returned.
        val warming = warmupRequest
        val requestKey = ModelKey(voice.lexicon, threadCount, speechFactory)
        val handoff = preparingForAnswer
        preparingForAnswer = null
        if (handoff != null && handoff != requestKey) handoff.factory.cancelPending()
        if (handoff == requestKey || warming != null && !warming.assetsOnly && warming.model.sameResident(requestKey)) {
            warmupGeneration++
            warmupRequest = null
            silentWarmup.cancel()
        } else {
            cancelPrewarm()
        }
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
            // Android can allocate the output track while the synthesis worker loads the model.
            output.execute { play(playback, playbackAttributes, VOICE_PCM_SAMPLE_RATE) }
            synthesis.execute {
                ConversationPrivacyContext.withCaptured(playback.privacy) { synthesize(playback, speed) }
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
                                playback.clockInterrupted.set(true)
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
                val progress = runCatching { loaded?.takeIf { it.key.sameResident(playback.key) }?.engine?.executionProgress }.getOrNull()
                progress?.let { runCatching { playback.measurements?.executionProgress(it) } }
                val expired = VoicePlaybackDeadlines.exceeded(
                    progress,
                    SystemClock.elapsedRealtime() - playback.lastProgress,
                    playback.preparing,
                    playback.key.factory.preparationTimeoutMs
                )
                // Once buffered audio has drained, an open reply may legitimately wait for
                // more LLM text. First PCM and every sealed reply keep their normal deadlines.
                val waitingForText = playback.awaitingIncrementalText &&
                    !playback.sealed &&
                    playback.queuedAudio &&
                    playback.enqueuedFrames.get() <= playback.consumedFrames.get() &&
                    playback.segments.isEmpty()
                if (!waitingForText && (playback.synthesizing || playback.sealed || !playback.segments.isEmpty()) && expired != null) {
                    fail(playback, "timeout", "Speech playback timed out. Your answer remains in chat.", cause = expired)
                } else {
                    main.postDelayed(this, 1_000)
                }
            }
        }
        playback = Playback(replyId, voice, ModelKey(voice.lexicon, effectiveThreads(voice, speed), speechFactory), focus, watchdog, complete, error, diagnosticProbe, playbackStarted, spokenRange, timedSpokenRange, captionAlignmentEnabled, pause, performance, speed, privacy(), pronunciations, threadCount, measurements?.forPlayback())
        active = playback
        playback.warmAtRequest = reusableModel(playback.key) != null
        measuredSynthesis.value = VoiceSynthesisFeedback(threads = playback.key.threads, warmAtRequest = playback.warmAtRequest, bufferState = "preparing")
        playback.diagnostics?.engine(
            when {
                playback.key.factory.selectedBackend.isCompiledPocket -> VoiceDiagnosticTrace.Engine.COMPILED_POCKET
                playback.key.factory.selectedBackend.isStreamingLocal -> VoiceDiagnosticTrace.Engine.MEDIAPIPE
                else -> VoiceDiagnosticTrace.Engine.KOKORO
            }
        )
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
                            if (mode == AudioManager.MODE_NORMAL) {
                                "Android denied audio focus. When audio is available, tap replay on this reply."
                            } else {
                                "Android denied audio focus. When the call or other audio ends, return to Voice and tap replay on this reply."
                            }
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
                    playback.segments.finish()
                    timing("input_complete", playback)
                }
            }
        }
    }

    private fun synthesize(playback: Playback, speed: Float) {
        if (active !== playback) return
        val queueWait = SystemClock.elapsedRealtime() - playback.startedAt
        var performanceHint: VoiceSynthesisPerformanceHint? = null
        try {
            val wasWarm = reusableModel(playback.key) != null
            playback.diagnostics?.warmupStarted()
            val gateStarted = SystemClock.elapsedRealtime()
            val model = VoiceNativeEngineGate.exclusive {
                if (active !== playback) return@exclusive null
                val gateWait = SystemClock.elapsedRealtime() - gateStarted
                playback.diagnostics?.preparation("GATE_WAIT", gateWait)
                runCatching { playback.measurements?.preparationPhase("GATE_WAIT", gateWait) }
                val loadStarted = SystemClock.elapsedRealtime()
                loadModelExclusive(playback.key).also {
                    val loadMs = SystemClock.elapsedRealtime() - loadStarted
                    playback.diagnostics?.preparation("MODEL_LOAD", loadMs)
                    runCatching { playback.measurements?.preparationPhase("MODEL_LOAD", loadMs) }
                }
            } ?: return
            if (active !== playback) return
            model.engine.resetCancellation()
            if (active !== playback) return
            model.engine.setCaptionAlignmentEnabled(playback.captionAlignmentEnabled && (playback.onRange != null || playback.onTimedRange != null))
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
            if (playback.performance == VoicePerformanceMode.AUTO && playback.key.factory.selectedBackend == VoiceSpeechBackend.CPU && !model.engine.supportsStreaming) {
                performanceHint = VoiceSynthesisPerformanceHint.open(application)
            }
            if (model.engine.supportsIncrementalInput && !playback.key.factory.requiresKokoroAssets) {
                if (streamReplyInput(playback, model.engine, speed, silence)) playback.synthesisDone = true
                return
            }
            var segmentIndex = -1
            // This worker consumes one playback's ordered queue. Its bounded pronunciation
            // context dies with that playback, including cancellation or a failed phrase.
            val speechContext = KokoroSpeechContext()
            val joins = VoiceSpeechJoinTracker()
            var waitingForTextMs = 0L
            while (active === playback) {
                playback.synthesizing = false
                val waitingSince = SystemClock.elapsedRealtime()
                val segment = playback.segments.next() ?: break
                waitingForTextMs += SystemClock.elapsedRealtime() - waitingSince
                playback.synthesizing = true
                if (model.engine.supportsStreaming && !playback.key.factory.requiresKokoroAssets) {
                    segmentIndex++
                    val plan = VoiceStreamingPhrasePlan(segment, playback.pronunciations, speed, englishFallback = true)
                    while (active === playback) {
                        val phrase = plan.next(playback.adaptive.phraseWords(bufferedAudioMs(playback))) ?: break
                        if (!streamPhrase(playback, model.engine, segmentIndex, segment, phrase.text, null, speed, silence, phrase.sourceCharacters)) return
                    }
                    if (active !== playback) return
                    if (playback.sealed && playback.segments.isEmpty()) break
                    continue
                }
                val projection = speechContext.project(
                    segment,
                    model.words::contains,
                    playback.pronunciations,
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
                        fail(playback, "pronunciation_unavailable", "A word in this reply is not supported by the installed voice. The complete answer is available in chat.")
                    }
                    return
                }
                segmentIndex++
                val plan = KokoroPhrasePlan(phrases)
                val tokenRetries = ArrayDeque<KokoroPhrase>()
                while (true) {
                    if (active !== playback) return
                    val firstPhrase = !playback.firstPcm
                    val phrase = tokenRetries.removeFirstOrNull() ?: plan.next(
                        if (firstPhrase) 4 else playback.adaptive.phraseWords(bufferedAudioMs(playback)),
                        finishSlack = if (firstPhrase) 0 else 3
                    ) ?: break
                    if (model.engine.supportsStreaming) {
                        val ranges = phrase.wordRanges.takeIf { playback.onRange != null || playback.onTimedRange != null }
                        if (!streamPhrase(playback, model.engine, segmentIndex, segment, phrase.speech, ranges, speed, silence)) return
                        continue
                    }
                    playback.diagnostics?.synthesisStarted()
                    val reserveAtStartMs = bufferedAudioMs(playback)
                    val synthesisStarted = SystemClock.elapsedRealtime()
                    playback.nativePhrase = NativePhraseInFlight(synthesisStarted, phrase.groups.size)
                    val encoded = try {
                        model.engine.preparePhrase(phrase.speech)
                    } catch (failure: dev.melo.kokoro.TimedKokoro.Failure) {
                        playback.nativePhrase = null
                        if (failure.code != "FRONTEND_TOKEN_LIMIT") throw failure
                        val parts = phrase.splitAtGroup() ?: throw failure
                        tokenRetries.addFirst(parts.second)
                        tokenRetries.addFirst(parts.first)
                        continue
                    }
                    val generate = { model.engine.generatePrepared(phrase.speech, encoded, playback.voice.speakerId, speed) }
                    val generated = performanceHint?.measure(VoiceCpuSchedulingPolicy.targetNanos(!playback.queuedAudio, bufferedAudioMs(playback)), generate) ?: generate()
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
                    val join = joins.observe(
                        phrase.speech.last(),
                        phrase.endBoundary,
                        generated.samples,
                        samples,
                        playback.enqueuedFrames.get(),
                        reserveAtStartMs,
                        waitingForTextMs,
                        sampleRate
                    )
                    waitingForTextMs = 0
                    if (join != null) {
                        playback.measurements?.phraseJoin(join)
                        timing("phrase_join", playback, "boundary=${join.boundary} · punctuation=${join.punctuation} · rawQuietMs=${join.rawQuietMs} · retainedQuietMs=${join.retainedQuietMs} · rawTailFrames=${join.rawTailFrames} · rawHeadFrames=${join.rawHeadFrames} · retainedTailFrames=${join.retainedTailFrames} · retainedHeadFrames=${join.retainedHeadFrames} · headroomMs=${join.headroomMs} · textWaitMs=${join.textWaitMs}")
                    }
                    runCatching { playback.measurements?.pcmReady(synthesisMs, samples.size, sampleRate, model.engine.executionEvidence) }
                    // Reserve against the retained PCM duration, since trimming silence also
                    // removes time during which the following phrase could be synthesized.
                    playback.nativePhrase = null
                    playback.adaptive.observe(synthesisMs, audioMs, recurringSpeechCostMs(model.engine.executionEvidence, synthesisMs), generated.wordTokens.size / 2)
                    measuredSynthesis.update {
                        it.copy(synthesisMs = synthesisMs, generatedAudioMs = audioMs, warm = it.warm && it.executionProvider == provider, engineReused = it.engineReused && it.executionProvider == provider, executionProvider = provider)
                    }
                    if (timedCaptions) {
                        playback.captions.append(segmentIndex, segment, phrase.wordRanges, boundaries, samples.size)
                        runCatching {
                            playback.measurements?.captionsPrepared(segmentIndex, phrase.wordRanges)
                            phrase.wordRanges.forEach { playback.measurements?.captionTimingObserved(segmentIndex, it, VoiceWordCueProvenance.NATIVE_DURATION) }
                        }
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
                loaded?.takeIf { it.key.sameResident(playback.key) }?.engine?.let { publishExecution(playback, it) }
            }
            val message = GatewayVoiceCapabilityUnavailable.find(error)?.userMessage
                ?: "The selected voice could not synthesize this reply. Your answer remains in chat."
            main.post { fail(playback, "synthesis_failed", message, cause = error) }
        } catch (error: LinkageError) {
            releaseModel()
            main.post { fail(playback, "engine_failed", "Voice playback is unavailable. Your answer remains in chat.", cause = error) }
        } catch (error: OutOfMemoryError) {
            releaseModel()
            main.post { fail(playback, "memory_pressure", "Voice playback could not allocate audio. Your answer remains in chat.", cause = error) }
        } finally {
            playback.firstPcmOrTerminal.countDown()
            performanceHint?.close()
            playback.synthesizing = false
            playback.cancelledAt?.let { stopped -> measuredSynthesis.update { it.copy(cancellationMs = SystemClock.elapsedRealtime() - stopped) } }
        }
    }

    /** One incremental SDK stream spans stable text arrivals and keeps original segment captions. */
    private fun streamReplyInput(playback: Playback, engine: VoicePhraseEngine, speed: Float, silence: VoiceQuietLimiter?): Boolean {
        val text = VoiceReplyStreamText(SystemClock::elapsedRealtime)
        playback.incrementalText = text
        val frames = VoiceStreamFrames.incremental(text.projection)
        val stream = VoiceStreamPause(frames, silence)
        val streamStart = playback.captions.streamStart()
        val timedCaptions = playback.onRange != null || playback.onTimedRange != null
        var segmentIndex = -1
        var segment = ""
        var plan: VoiceStreamingPhrasePlan? = null
        val started = SystemClock.elapsedRealtime()
        var callbackMs = 0L
        var lastProducedAt = started
        var lastDelivery = text.deliveryClockMs()
        playback.adaptive.beginStream(incrementalInput = true)
        playback.synthesizing = true
        playback.diagnostics?.synthesisStarted()
        consumeVoiceInputStream(engine, {
            var next: String? = null
            while (active === playback && next == null) {
                val phrase = plan?.next(playback.adaptive.phraseWords(bufferedAudioMs(playback)))
                if (phrase != null) {
                    playback.awaitingIncrementalText = false
                    next = text.append(segmentIndex, segment, phrase)
                } else {
                    playback.awaitingIncrementalText = true
                    val queued = playback.segments.next() ?: break
                    playback.awaitingIncrementalText = false
                    playback.lastProgress = SystemClock.elapsedRealtime()
                    segmentIndex++
                    segment = queued
                    plan = VoiceStreamingPhrasePlan(segment, playback.pronunciations, speed, englishFallback = true)
                }
            }
            if (playback.sealed) playback.awaitingIncrementalText = false
            next
        }, playback.voice.speakerId, speed, { active === playback }) { chunk ->
            val callbackStarted = SystemClock.elapsedRealtime()
            val productionMs = (callbackStarted - lastProducedAt).coerceAtLeast(0)
            val delivery = text.deliveryClockMs()
            val retained = stream.accept(chunk)
            text.observeRenderedText(chunk.wordCues, frames.frames)
            playback.adaptive.observeStreamDelivery((delivery - lastDelivery).coerceAtLeast(0), retained.samples.size)
            val captions = if (timedCaptions) text.captions(retained.cues) else emptyList()
            if (captions.isEmpty()) {
                playback.captions.appendStream(0, "", streamStart, emptyList(), retained.samples.size)
            } else {
                captions.forEachIndexed { index, caption ->
                    playback.captions.appendStream(caption.segment, caption.text, streamStart, listOf(caption.cue), if (index == 0) retained.samples.size else 0)
                    runCatching {
                        playback.measurements?.captionsPrepared(caption.segment, listOf(caption.cue.range))
                        playback.measurements?.captionTimingObserved(caption.segment, caption.cue.range, caption.cue.provenance, caption.cue.revision)
                    }
                }
            }
            measuredSynthesis.update {
                it.copy(synthesisMs = (callbackStarted - started - callbackMs).coerceAtLeast(0), generatedAudioMs = stream.retainedFrames * 1_000 / chunk.sampleRate, executionProvider = engine.executionProvider)
            }
            val accepted = if (retained.samples.isNotEmpty()) {
                runCatching { playback.measurements?.pcmReady(productionMs, retained.samples.size, chunk.sampleRate, engine.executionEvidence) }
                queuePcm(playback, retained.samples, chunk.sampleRate, playback.inputChars)
            } else {
                active === playback
            }
            val returnedAt = SystemClock.elapsedRealtime()
            callbackMs += returnedAt - callbackStarted
            lastProducedAt = returnedAt
            lastDelivery = text.deliveryClockMs()
            accepted
        }
        if (active !== playback) return false
        stream.finish()
        val finished = SystemClock.elapsedRealtime()
        playback.adaptive.endStream((text.deliveryClockMs() - lastDelivery).coerceAtLeast(0))
        playback.adaptive.observe((finished - started - callbackMs).coerceAtLeast(1), stream.retainedFrames * 1_000 / VOICE_PCM_SAMPLE_RATE)
        publishExecution(playback, engine, completedPhrase = true)
        publishFeedback(playback)
        return true
    }

    /** Pull-style engines cannot outrun the bounded PCM queue; no complete streamed waveform is retained. */
    private fun streamPhrase(playback: Playback, engine: VoicePhraseEngine, segmentIndex: Int, segment: String, text: String, ranges: List<IntRange>?, speed: Float, silence: VoiceQuietLimiter?, sourceCharacters: List<IntRange>? = null): Boolean {
        val stream = VoiceStreamPause(VoiceStreamFrames(text, ranges, sourceCharacters), silence)
        val streamStart = playback.captions.streamStart()
        val timedCaptions = playback.onRange != null || playback.onTimedRange != null
        val started = SystemClock.elapsedRealtime()
        var callbackMs = 0L
        var lastProducedAt = started
        playback.adaptive.beginStream()
        playback.diagnostics?.synthesisStarted()
        consumeVoiceStream(engine, text, playback.voice.speakerId, speed, { active === playback }) { chunk ->
            val callbackStarted = SystemClock.elapsedRealtime()
            val productionMs = (callbackStarted - lastProducedAt).coerceAtLeast(0)
            val retained = stream.accept(chunk)
            playback.adaptive.observeStreamDelivery(productionMs, retained.samples.size)
            // Native text projection omits source ranges when captions are disabled. Its
            // expanded speech offsets then belong to the normalized input, not the segment.
            val captions = if (timedCaptions) retained.cues.map { it.lexicalRange(segment) } else emptyList()
            playback.captions.appendStream(segmentIndex, segment, streamStart, captions, retained.samples.size)
            if (timedCaptions) {
                runCatching {
                    playback.measurements?.captionsPrepared(segmentIndex, captions.map { it.range })
                    captions.forEach { playback.measurements?.captionTimingObserved(segmentIndex, it.range, it.provenance, it.revision) }
                }
            }
            measuredSynthesis.update {
                it.copy(synthesisMs = (callbackStarted - started - callbackMs).coerceAtLeast(0), generatedAudioMs = stream.retainedFrames * 1_000 / chunk.sampleRate, executionProvider = engine.executionProvider)
            }
            val accepted = if (retained.samples.isNotEmpty()) {
                runCatching { playback.measurements?.pcmReady(productionMs, retained.samples.size, chunk.sampleRate, engine.executionEvidence) }
                queuePcm(playback, retained.samples, chunk.sampleRate, text.length)
            } else {
                active === playback
            }
            val returnedAt = SystemClock.elapsedRealtime()
            callbackMs += returnedAt - callbackStarted
            lastProducedAt = returnedAt
            accepted
        }
        if (active !== playback) return false
        stream.finish()
        val finished = SystemClock.elapsedRealtime()
        playback.adaptive.endStream((finished - lastProducedAt).coerceAtLeast(0))
        val synthesisMs = (finished - started - callbackMs).coerceAtLeast(1)
        val audioMs = stream.retainedFrames * 1_000 / VOICE_PCM_SAMPLE_RATE
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
                        playback.firstPcmOrTerminal.countDown()
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
            val setupStarted = SystemClock.elapsedRealtime()
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
                val setupMs = SystemClock.elapsedRealtime() - setupStarted
                playback.diagnostics?.preparation("AUDIO_TRACK_SETUP", setupMs)
                runCatching { playback.measurements?.preparationPhase("AUDIO_TRACK_SETUP", setupMs) }
                playback.firstPcmOrTerminal.await()
                if (active !== playback) return
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
                    sourceProvider = evidence.failedProvider,
                    requestedBackend = evidence.selectedBackend.name,
                    stage = if (evidence.failureStage == VoiceSpeechStage.SYNTHESIS) VoicePlaybackFailureStage.SYNTHESIS else VoicePlaybackFailureStage.PREPARATION
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
        var drainedFrame = 0L
        while (active === playback) {
            val first = playback.pcm.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (first == null) {
                val consumed = playedFrames(track, playback)
                if (playback.synthesisDone && playback.pcm.isEmpty()) {
                    val complete = playback.drain.complete(framesWritten, consumed, playback.presentedFrames, SystemClock.elapsedRealtime(), playback.captionOffsetMs) {
                        track.setStartThresholdInFrames(1)
                    }
                    if (complete) {
                        playedFrames(track, playback)
                        return
                    }
                    continue
                }
                if (playing && VoicePlaybackClock.drained(framesWritten, consumed, playback.presentedFrames)) {
                    // A hardware timestamp can trail the consumed head. Keep its tail
                    // moving before pausing, otherwise a late end-of-input seals a
                    // paused clock that can never reach the completion boundary.
                    observeUnderruns(track, playback, "active")
                    playback.trackLock.withLock {
                        if (active === playback) track.pause()
                        playback.clockInterrupted.set(true)
                        playback.trackPrimed = false
                    }
                    playing = false
                    playback.refilling = true
                    drainedAt = SystemClock.elapsedRealtime()
                    drainedFrame = framesWritten
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
            val batches = if (playing) listOf(first) else KokoroPcmBatches.primeReady(playback.pcm, first, primeFrames)
            var startThreshold = minOf(primeFrames, batches.sumOf { it.size })
            var primeWritten = 0
            if (!playing) {
                val effectiveBuffer = track.setBufferSizeInFrames(minOf(track.bufferCapacityInFrames, maxOf(startThreshold * 2, requestedFrames)))
                    .takeIf { it > 0 } ?: track.bufferSizeInFrames
                check(effectiveBuffer > 0)
                // A route change can shrink the writable buffer. Waiting to prime its
                // previous capacity would block the first play request indefinitely.
                startThreshold = minOf(startThreshold, effectiveBuffer)
                startThreshold = minOf(startThreshold, track.setStartThresholdInFrames(startThreshold))
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
                                    val refillMs = SystemClock.elapsedRealtime() - drained
                                    playback.longestRefillMs = maxOf(playback.longestRefillMs, refillMs)
                                    if (!playback.focusState.paused) playback.measurements?.refillAt(drainedFrame, refillMs)
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
        val proposedWait = playback.adaptive.primeWaitMs(startup)
        val waitMs = if (startup) VoiceAdaptivePlayback.startupWaitMs(started - playback.startedAt, proposedWait) else proposedWait
        val deadline = started + waitMs
        val target = sampleRate.toLong() * playback.adaptive.bufferMs(startup) / 1_000
        val exit = KokoroPcmBatches.awaitReserve(
            queue = playback.pcm,
            targetFrames = target,
            acceptedFrames = { playback.enqueuedFrames.get() - playback.consumedFrames.get() },
            canWait = { active === playback && !playback.synthesisDone },
            producerPending = {
                val waitingForWords = playback.awaitingIncrementalText && playback.incrementalText?.allSubmittedTextRendered() == true
                !playback.segments.isEmpty() || playback.synthesizing && !waitingForWords
            },
            deadlineReached = { SystemClock.elapsedRealtime() >= deadline },
            nativeReserveExit = {
                val now = SystemClock.elapsedRealtime()
                playback.nativePhrase?.let { phrase ->
                    playback.adaptive.nativeReserveExit(bufferedAudioMs(playback), deadline - now, now - phrase.startedAt, phrase.words)
                }
            },
            poll = {
                playedFrames(track, playback)
                Thread.sleep(POLL_MS)
            }
        )
        val reason = when (exit) {
            VoicePcmReserveExit.STOPPED -> if (playback.synthesisDone) "producer_done" else "cancelled"
            VoicePcmReserveExit.DEADLINE -> if (waitMs < proposedWait) "first_audio_budget" else "deadline"
            else -> exit.name.lowercase(java.util.Locale.ROOT)
        }
        runCatching {
            playback.measurements?.reserveCompleted(phase, SystemClock.elapsedRealtime() - started, target * 1_000 / sampleRate, bufferedAudioMs(playback), reason)
        }
    }

    private fun loadModel(key: ModelKey): LoadedModel = VoiceNativeEngineGate.exclusive { loadModelExclusive(key) }

    private fun reusableModel(key: ModelKey): LoadedModel? = loaded?.takeIf {
        it.key.sameResident(key) && (it.engine as? VoiceFailoverPhraseEngine)?.canReuse != false
    }

    private fun readLexicon(name: String): Set<String> {
        preparedLexicon?.takeIf { it.first == name }?.let { return it.second }
        val words = File(models.modelDirectory(), name).useLines(block = KokoroLexicon::words)
        val missing = KokoroLexicon.missingRequiredWords(words)
        AppLogRecorder.record("VoiceEngine", "kokoro_lexicon · lexicon=$name · entries=${words.size} · missingRequiredWords=$missing · projectionVersion=2")
        if (missing != 0) throw LexiconUnavailable()
        preparedLexicon = name to words
        return words
    }

    private fun loadModelExclusive(key: ModelKey): LoadedModel {
        check(!closed)
        check(quarantinedModel == null && quarantinedPreparation == null) { "Previous native speech retirement failed; restart the app before opening another model." }
        reusableModel(key)?.let {
            key.factory.prepareForPlayback(it.engine)
            return it
        }
        releaseModel()
        check(quarantinedModel == null && quarantinedPreparation == null) { "Previous native speech retirement failed; restart the app before opening another model." }
        check(key.factory.isReady()) { "Speech model is not ready" }
        val leasedKokoroAssets = key.factory.requiresKokoroAssets
        if (leasedKokoroAssets) check(models.acquireUse())
        val engineId = java.util.UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        AppLogRecorder.record("VoiceEngine", "kokoro_start · engineId=$engineId · threads=${key.threads}")
        try {
            val directory = if (leasedKokoroAssets) models.modelDirectory() else application.noBackupFilesDir
            val words = if (leasedKokoroAssets) readLexicon(key.lexicon) else emptySet()
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
            if (loaded == null) {
                if (key.factory.nativeCleanupPending) {
                    // A factory may have opened native weights before preparation failed.
                    // Keep both the factory's failed owner and the surrounding asset lease.
                    quarantinedPreparation = key.factory
                } else if (leasedKokoroAssets) {
                    models.releaseUse()
                }
            }
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
        if (route == null && playback.routeId != -1) {
            playback.routeId = -1
            playback.route = "unknown"
            playback.captionRouteKey = null
            playback.clock.routeChanged()
            playback.hasTimestamp = false
            playback.nextTimestampAt = 0
            runCatching { playback.measurements?.outputRouteIdentity(null) }
            publishFeedback(playback)
        } else if (route != null && (route.id != playback.routeId || captionCalibration.usesSavedRoutes && routeCalibration.hasPending)) {
            val changed = route.id != playback.routeId
            playback.routeId = route.id
            playback.route = routeName(route.type)
            val address = runCatching { route.address }.getOrDefault("")
            val product = runCatching { route.productName.toString() }.getOrDefault("")
            val routeKey = VoiceCaptionRouteIdentity.key(playback.route, route.type, address, product, VOICE_PCM_SAMPLE_RATE)
            playback.captionRouteKey = routeKey
            if (changed) {
                playback.clock.routeChanged()
                playback.hasTimestamp = false
                playback.nextTimestampAt = 0
            }
            playback.captionOffsetMs = captionCalibration.resolve(captionOffset) {
                val resolved = routeCalibration.observed(routeKey) {
                    calibration.getInt(routeKey, calibration.getInt("caption:${playback.route}", captionOffset))
                }
                persistCaptionCalibration(resolved.write)
                resolved.offsetMs
            }
            runCatching {
                playback.measurements?.calibration(playback.route, playback.captionOffsetMs, playback.clock.routeEpoch)
                playback.measurements?.outputRouteIdentity(VoiceCaptionRouteIdentity.observedRoute(routeKey, address, product))
            }
            measuredSynthesis.update { it.copy(captionOffsetMs = playback.captionOffsetMs) }
            timing(if (changed) "route_changed" else "caption_calibrated", playback, "route=${playback.route} · captionOffsetMs=${playback.captionOffsetMs}")
            publishFeedback(playback)
        }
        val trackPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
        val interrupted = playback.clockInterrupted.getAndSet(false)
        if (interrupted || playback.timestampPlaying != null && playback.timestampPlaying != trackPlaying) {
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
            trackPlaying,
            interrupted
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
        val advancing = position.presenting || playback.drain.startedAt != null
        val captionFrames = if (advancing) {
            maxOf(
                playback.lastCaptionFrame,
                VoicePlaybackClock.captionFrames(
                    presented,
                    VOICE_PCM_SAMPLE_RATE,
                    playback.captionOffsetMs,
                    playback.drain.startedAt?.let { now - it } ?: 0,
                    accepted
                )
            )
        } else {
            playback.lastCaptionFrame
        }
        playback.lastCaptionFrame = captionFrames
        // Neither an ahead calibration offset nor newly generated PCM may move a
        // paused word. A refill may only clear an already-presented finalized word;
        // an explicit focus pause retains its current caption until focus returns.
        val changedCue = playback.captions.consumed(
            captionFrames,
            advancing,
            completedThrough = minOf(captionFrames, presented).takeIf { playback.refilling && !playback.focusState.paused },
            onWordCrossed = playback.measurements?.let { observer -> { cue -> observer.captionClockCrossed(cue.segment, cue.range) } }
        )
        val canAdvance = VoicePlaybackClock.captionsAdvancing(position.presenting, presented, captionFrames, accepted, playback.drain.startedAt != null)
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
        // The synthesis worker is the single owner. An empty teardown must not queue behind capture.
        if (loaded == null) return
        VoiceNativeEngineGate.exclusive {
            val model = loaded ?: return@exclusive
            loaded = null
            val failure = runCatching { model.engine.close() }.exceptionOrNull()
            if (failure == null) {
                if (model.leasedKokoroAssets) models.releaseUse()
            } else {
                quarantinedModel = model
                AppLogRecorder.record("VoiceEngine", "kokoro_release_failed · type=${failure.javaClass.simpleName}", "W")
            }
        }
    }

    override fun releaseWarmModel() = releaseWarmModelWhenRetired(null)

    override fun releaseWarmModel(completed: () -> Unit) = releaseWarmModelWhenRetired(completed)

    private fun releaseWarmModelWhenRetired(completed: (() -> Unit)?) {
        if (closed) return
        preparingForAnswer = null
        val generation = ++warmupGeneration
        warmupRequest = null
        silentWarmup.cancel()
        speechFactory.cancelPending()
        if (calibrating) {
            calibrationTicket++
            runCatching { loaded?.engine?.cancel() }
        }
        try {
            synthesis.execute {
                preparedLexicon = null
                releaseModel()
                if (completed != null) {
                    try {
                        // The microphone/benchmark owner also needs the previous
                        // AudioTrack worker to leave its finally block first.
                        output.execute {
                            main.post {
                                if (!closed &&
                                    warmupGeneration == generation &&
                                    active == null &&
                                    !calibrating &&
                                    loaded == null &&
                                    quarantinedModel == null &&
                                    quarantinedPreparation == null &&
                                    !speechFactory.nativeCleanupPending
                                ) {
                                    completed()
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        // A concurrent close owns retirement; it cannot acknowledge this handoff.
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A concurrent close owns retirement; the caller retains its handoff lease.
        }
    }

    private fun end(playback: Playback, reason: String, cancellationOrigin: String? = null, cancelPreparation: Boolean = true) {
        if (active !== playback) return
        playback.cancelledAt = SystemClock.elapsedRealtime()
        active = null
        playback.firstPcmOrTerminal.countDown()
        measuredOutput.value = null
        measuredSynthesis.update { it.copy(bufferState = "idle") }
        main.removeCallbacks(playback.watchdog)
        playback.segments.cancel()
        playback.pcm.clear()
        val underruns = playback.trackLock.withLock {
            playback.track?.let { track ->
                observeUnderruns(track, playback, if (reason == "complete") "draining" else "cancelled")
                silence(track)
            }
            playback.underruns
        }
        runCatching { playback.measurements?.terminal(underruns, playback.bufferRestarts, playback.longestRefillMs, playback.clockBasis, playback.route) }
        if (cancelPreparation) playback.key.factory.cancelPending()
        runCatching { loaded?.engine?.cancel() }
        runCatching { audioManager?.abandonAudioFocusRequest(playback.focus) }
        timing("terminal", playback, "reason=$reason · cancellationOrigin=${cancellationOrigin ?: "none"} · textChars=${playback.inputChars} · firstPcm=${playback.firstPcm} · firstOutputFrame=${playback.firstFrame} · inputComplete=${playback.sealed} · drainComplete=${reason == "complete"} · underruns=${underruns ?: "unmeasured"} · bufferRestarts=${playback.bufferRestarts} · longestRefillMs=${playback.longestRefillMs} · rate=${playback.rate} · performance=${playback.performance.name} · pause=${playback.pause.name} · knownWords=${playback.knownWords} · initialisms=${playback.initialisms} · unknownWords=${playback.unknownWords} · spelledLetters=${playback.spelledLetters} · normalizedFormats=${playback.removedFormatCharacters}")
    }

    private fun fail(playback: Playback, reason: String, message: String, canFallback: Boolean = true, cause: Throwable? = null) {
        if (active !== playback) return
        val currentFailure = VoicePlaybackFailure(
            message,
            cause,
            reason.uppercase(java.util.Locale.ROOT),
            loaded?.takeIf { it.key.sameResident(playback.key) }?.engine?.executionProvider,
            requestedBackend = playback.key.factory.selectedBackend.name,
            stage = when {
                reason.startsWith("audio_focus") -> VoicePlaybackFailureStage.AUDIO_FOCUS
                playback.preparing -> VoicePlaybackFailureStage.PREPARATION
                reason == "audio_failed" -> VoicePlaybackFailureStage.PLAYBACK
                else -> VoicePlaybackFailureStage.SYNTHESIS
            }
        )
        val failure = playback.nativeFallback?.takeUnless { playback.nativeFallbackSucceeded }?.afterFallback(currentFailure) ?: currentFailure
        playback.diagnostics?.playbackFailure(failure)
        playback.nativeFallback?.takeUnless { playback.nativeFallbackSucceeded }?.let {
            playback.diagnostics?.playbackFallback(failure, "FAILED", "KOKORO_CPU")
        }
        end(playback, reason)
        // end() waits for an in-flight track write/play to finish. A fallback decision
        // taken before that barrier could replay audio that started during the failure.
        playback.error(failure, canFallback && !playback.queuedAudio)
    }

    override fun stop() = stop("audio_stop")

    override fun stop(reason: String) {
        warmupGeneration++
        warmupRequest = null
        silentWarmup.cancel()
        val playback = active
        // Retire all optional PCM immediately. A local graph already opening for these same
        // weights can finish on the synthesis worker and serve the answer without a second cold load.
        preparingForAnswer = playback?.takeIf { reason == "answer_ready" && it.preparing && it.key.factory.selectedBackend != VoiceSpeechBackend.GATEWAY_CUDA }?.key
        if (preparingForAnswer == null) speechFactory.cancelPending()
        calibrationTicket++
        if (calibrating) runCatching { loaded?.engine?.cancel() }
        val origin = reason.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "unspecified"
        if (playback != null) end(playback, "interrupted", origin, cancelPreparation = preparingForAnswer == null)
    }

    override fun close() {
        if (closed) return
        active?.let { end(it, "session_closed") }
        releaseWarmModel()
        closed = true
        try {
            application.unregisterComponentCallbacks(memoryCallbacks)
        } finally {
            synthesis.shutdown()
            output.shutdown()
        }
    }

    /** A benchmark keeps its audio lease until both native synthesis and playback have actually retired. */
    override fun awaitClosed(timeoutMs: Long): Boolean {
        check(closed)
        val synthesisStopped = synthesis.awaitTermination(timeoutMs.coerceIn(1, 1_000), TimeUnit.MILLISECONDS)
        val playbackStopped = output.awaitTermination(timeoutMs.coerceIn(1, 1_000), TimeUnit.MILLISECONDS)
        return synthesisStopped && playbackStopped && quarantinedModel == null && quarantinedPreparation == null
    }

    private companion object {
        const val MAX_REPLY_CHARS = 12_100
        const val MAX_SEGMENTS = MAX_REPLY_CHARS
        const val POLL_MS = 12L
    }
}
