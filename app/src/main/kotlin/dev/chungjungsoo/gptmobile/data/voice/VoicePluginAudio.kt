package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class KokoroVoice(val speakerId: Int, val label: String, val lexicon: String) {
    HEART(3, "Heart · US English", KokoroModelPack.US_LEXICON),
    ADAM(11, "Adam · US English", KokoroModelPack.US_LEXICON),
    EMMA(21, "Emma · UK English", KokoroModelPack.GB_LEXICON);

    companion object {
        fun fromSaved(value: String?) = entries.firstOrNull { it.name == value }
    }
}

internal interface KokoroOutput {
    val outputLevel: kotlinx.coroutines.flow.StateFlow<Float?>? get() = null
    val synthesisFeedback: kotlinx.coroutines.flow.StateFlow<VoiceSynthesisFeedback>? get() = null
    val reportsPlaybackStart: Boolean get() = false
    fun bindPlaybackStarted(started: (() -> Unit)?) = Unit
    fun bindSpokenRange(spoken: SpokenRangeObserver?) = Unit
    fun bindTimedSpokenRange(spoken: TimedSpokenRangeObserver?) = Unit
    fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit)
    fun speakProjected(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit) = speak(text, voice, complete, error)
    fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit): VoiceReplyPlayback? = null
    fun speakWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, canFallback: Boolean) -> Unit) =
        speak(text, voice, complete) { message, canFallback -> error(VoicePlaybackFailure(message), canFallback) }
    fun speakProjectedWithFailure(text: String, voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, canFallback: Boolean) -> Unit) =
        speakProjected(text, voice, complete) { message, canFallback -> error(VoicePlaybackFailure(message), canFallback) }
    fun openReplyWithFailure(voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, canFallback: Boolean) -> Unit): VoiceReplyPlayback? =
        openReply(voice, complete) { message, canFallback -> error(VoicePlaybackFailure(message), canFallback) }
    fun configureThreads(value: Int) = Unit
    fun configurePacing(pause: VoicePauseDuration, performance: VoicePerformanceMode) = Unit
    fun configurePronunciation(overrides: Map<String, String>) = Unit
    fun configureCaptionOffset(milliseconds: Int) = Unit
    fun configureEngine(factory: VoicePhraseEngineFactory) = Unit
    fun benchmark(voice: KokoroVoice, completed: (String) -> Unit) = completed("This speech engine does not support calibration.")
    fun prewarm(voice: KokoroVoice) = Unit
    fun cancelPrewarm() = Unit
    fun releaseWarmModel() = Unit
    fun stop()
    fun stop(reason: String) = stop()
    fun bindReply(id: String) = Unit
    fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) = Unit
    fun close()

    /** Call off main after close. A native retirement failure never authorizes replacement. */
    fun awaitClosed(timeoutMs: Long): Boolean = true
}

/** Owns the microphone and playback backend for the visible voice session. */
internal class VoicePluginAudio(
    private val system: VoiceAudio,
    private val local: VoiceRecognitionInput,
    private val kokoro: KokoroOutput? = null,
    private val offlineSpeech: (String, () -> Unit, (String) -> Unit) -> Unit = { _, _, error -> error("No offline Android voice is available.") },
    private val releaseSystemRecognition: () -> Unit
) : VoiceAudio {
    private var closed = false
    var requireLocalOutput: Boolean = false
    private var playbackGeneration = 0L
    private var playbackStarted: (() -> Unit)? = null
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    private val interruption = local as? VoiceInterruptionInput
    private val unavailableInterruption = MutableStateFlow(VoiceInterruptionStatus.UNAVAILABLE)
    val interruptionState: StateFlow<VoiceInterruptionStatus>
        get() = if (recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) interruption?.interruptionState ?: unavailableInterruption else unavailableInterruption
    private var interruptionEnabled = false
    private var interrupted: (() -> Unit)? = null
    private var monitoringTicket: Long? = null
    private var interruptionEpoch = 0L
    private var playingTicket: Long? = null
    private var playbackLevel: () -> Float? = { null }
    var listeningStyle: VoiceListeningStyle
        get() = (local as? MoonshineVoiceRecognizer)?.listeningStyle ?: VoiceListeningStyle.NATURAL
        set(value) {
            (local as? MoonshineVoiceRecognizer)?.listeningStyle = value
        }
    var captureBatchMs: Int
        get() = (local as? MoonshineVoiceRecognizer)?.captureBatchMs ?: 40
        set(value) {
            (local as? MoonshineVoiceRecognizer)?.captureBatchMs = value
        }

    override fun configureInterruption(enabled: Boolean, interrupted: (() -> Unit)?) {
        interruptionEnabled = enabled
        this.interrupted = interrupted
        if (!enabled) stopMonitoring(clearPlayback = false) else playingTicket?.let(::startMonitoring)
    }

    private fun playbackBegan(ticket: Long, level: () -> Float? = { null }) {
        if (closed || playbackGeneration != ticket) return
        playingTicket = ticket
        playbackLevel = level
        playbackStarted?.invoke()
        startMonitoring(ticket)
    }

    private fun startMonitoring(ticket: Long) {
        if (closed || !interruptionEnabled || recognitionBackend != VoiceRecognitionBackend.LOCAL_MOONSHINE || monitoringTicket == ticket || playbackGeneration != ticket) return
        monitoringTicket = ticket
        val epoch = ++interruptionEpoch
        interruption?.startInterruptionMonitoring(playbackLevel) {
            if (!closed && interruptionEnabled && playbackGeneration == ticket && interruptionEpoch == epoch) {
                interruptionEpoch++
                monitoringTicket = null
                interrupted?.invoke()
            }
        }
    }

    private fun stopMonitoring(clearPlayback: Boolean = true) {
        interruptionEpoch++
        monitoringTicket = null
        if (clearPlayback) playingTicket = null
        interruption?.stopInterruptionMonitoring()
    }

    private fun playbackFinished(ticket: Long, action: () -> Unit) {
        if (closed || playbackGeneration != ticket) return
        stopMonitoring()
        playbackGeneration++
        action()
    }
    override fun bindPlaybackStarted(started: (() -> Unit)?) {
        playbackStarted = started
    }

    override fun bindSpokenRange(spoken: ((Int, String, IntRange) -> Unit)?) {
        system.bindSpokenRange(spoken)
        kokoro?.bindSpokenRange(spoken)
    }

    override fun bindTimedSpokenRange(spoken: ((Int, String, IntRange, VoiceCaptionTiming) -> Unit)?) {
        kokoro?.bindTimedSpokenRange(spoken)
    }

    // offlineSpeech belongs to the same Android output and shares its start observer.
    override val reportsPlaybackStart: Boolean
        get() = system.reportsPlaybackStart && (kokoroVoice == null || kokoro == null || kokoro.reportsPlaybackStart)
    var kokoroVoice: KokoroVoice? = null
        set(value) {
            if (closed || field == value) return
            stop()
            if (value == null || value.lexicon != field?.lexicon) kokoro?.releaseWarmModel()
            field = value
        }
    var recognitionBackend: VoiceRecognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE
        set(value) {
            if (closed || field == value) return
            stop()
            releaseSystemRecognition()
            field = value
        }

    override fun retryAfterMillis(): Long = if (recognitionBackend == VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE) system.retryAfterMillis() else 0

    override val streamsReplies: Boolean get() = !closed && kokoro != null && kokoroVoice != null && LocalVoiceModelStore.supportsLanguage(local.languageTag)

    override fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback? =
        openReplyWithFailure(complete) { error(it.message) }

    override fun openReplyWithFailure(complete: () -> Unit, error: (VoicePlaybackFailure) -> Unit): VoiceReplyPlayback? {
        if (!streamsReplies) return null
        val output = kokoro ?: return null
        val voice = kokoroVoice ?: return null
        stopMonitoring()
        local.stop()
        system.stop()
        val ticket = ++playbackGeneration
        val fallbackText = StringBuilder()
        var useFallback = false
        var fallbackStarted = false
        var fallbackFailure: VoicePlaybackFailure? = null
        val origin = diagnosticProbe
        var finished = false
        fun current() = !closed && playbackGeneration == ticket
        output.bindPlaybackStarted { if (current() && !useFallback) playbackBegan(ticket) { output.outputLevel?.value } }
        system.bindPlaybackStarted { if (current() && useFallback) playbackBegan(ticket) }
        fun finishFallback() {
            if (!current() || fallbackStarted) return
            val original = fallbackFailure ?: return
            fallbackStarted = true
            origin?.playbackFallback(original, "STARTED")
            offlineSpeech(fallbackText.toString(), {
                if (current()) origin?.playbackFallback(original, "SUCCEEDED")
                playbackFinished(ticket, complete)
            }, {
                if (current()) origin?.playbackFallback(original, "FAILED")
                playbackFinished(ticket) { error(VoicePlaybackFailure(it, original.cause, original.code, original.sourceProvider)) }
            })
        }
        val reply = output.openReplyWithFailure(voice, complete = {
            // The original producer no longer owns completion once recovery is selected.
            if (!useFallback) playbackFinished(ticket, complete)
        }, error = { failure, canFallback ->
            if (current() && !useFallback) {
                stopMonitoring()
                if (canFallback && !requireLocalOutput) {
                    useFallback = true
                    fallbackFailure = failure
                    if (finished) finishFallback()
                } else {
                    playbackFinished(ticket) { error(failure) }
                }
            }
        }) ?: return null
        return object : VoiceReplyPlayback {
            override fun enqueue(text: String) {
                if (finished || !current()) return
                fallbackText.append(text)
                if (!useFallback) reply.enqueue(text)
            }

            override fun finish() {
                if (finished || !current()) return
                finished = true
                if (useFallback) finishFallback() else reply.finish()
            }
        }
    }

    override fun listen(listener: VoiceAudio.Listener) {
        if (closed) return
        stopMonitoring()
        val ticket = ++playbackGeneration
        kokoro?.stop("microphone_started")
        fun startRecognition() {
            if (closed || playbackGeneration != ticket) return
            when (recognitionBackend) {
                VoiceRecognitionBackend.LOCAL_MOONSHINE -> {
                    system.stop()
                    releaseSystemRecognition()
                    local.listen(listener)
                }
                VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE -> {
                    local.stop()
                    system.listen(listener)
                }
            }
        }
        if (interruption == null) startRecognition() else interruption.afterInterruptionStopped(::startRecognition)
    }

    override fun finishListening() {
        if (closed) return
        when (recognitionBackend) {
            VoiceRecognitionBackend.LOCAL_MOONSHINE -> (local as? MoonshineVoiceRecognizer)?.finishListening()
            VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE -> system.finishListening()
        }
    }

    override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) =
        speakWithFailure(text, complete) { error(it.message) }

    override fun speakWithFailure(text: String, complete: () -> Unit, error: (VoicePlaybackFailure) -> Unit) {
        if (closed) return
        stopMonitoring()
        local.stop()
        val ticket = ++playbackGeneration
        val origin = diagnosticProbe
        fun current() = !closed && ticket == playbackGeneration
        system.bindPlaybackStarted { if (current()) playbackBegan(ticket) }
        val voice = kokoroVoice
        if (voice == null) {
            if (requireLocalOutput) {
                error(VoicePlaybackFailure("Choose a Kokoro voice in Voice settings to read this response."))
                return
            }
            system.speakWithFailure(text, { playbackFinished(ticket, complete) }, { playbackFinished(ticket) { error(it) } })
            return
        }
        if (kokoro == null || !LocalVoiceModelStore.supportsLanguage(local.languageTag)) {
            if (requireLocalOutput) {
                error(VoicePlaybackFailure("Local voice playback is unavailable. Check Kokoro in Voice settings; your response is kept in chat."))
                return
            }
            offlineSpeech(text, { playbackFinished(ticket, complete) }, { playbackFinished(ticket) { error(VoicePlaybackFailure(it)) } })
            return
        }
        system.stop()
        kokoro.bindPlaybackStarted { if (current()) playbackBegan(ticket) { kokoro.outputLevel?.value } }
        kokoro.speakProjectedWithFailure(text, voice, complete = {
            if (!closed && ticket == playbackGeneration) {
                stopMonitoring()
                playbackGeneration++
                complete()
            }
        }, error = { failure, canFallback ->
            if (!closed && ticket == playbackGeneration) {
                stopMonitoring()
                val fallbackTicket = ++playbackGeneration
                fun fallbackCurrent() = !closed && fallbackTicket == playbackGeneration
                if (canFallback && !requireLocalOutput) {
                    origin?.playbackFallback(failure, "STARTED")
                    system.bindPlaybackStarted { if (fallbackCurrent()) playbackBegan(fallbackTicket) }
                    offlineSpeech(text, {
                        if (fallbackCurrent()) origin?.playbackFallback(failure, "SUCCEEDED")
                        playbackFinished(fallbackTicket, complete)
                    }, {
                        if (fallbackCurrent()) origin?.playbackFallback(failure, "FAILED")
                        playbackFinished(fallbackTicket) { error(VoicePlaybackFailure(it, failure.cause, failure.code, failure.sourceProvider)) }
                    })
                } else {
                    error(failure)
                }
            }
        })
    }

    override fun bindReply(id: String) {
        kokoro?.bindReply(id)
    }

    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        diagnosticProbe = probe
        system.bindDiagnostics(probe)
        local.bindDiagnostics(probe)
        kokoro?.bindDiagnostics(probe)
    }

    override fun stop() = stop("audio_stop")

    override fun stop(reason: String) {
        if (closed) return
        stopMonitoring()
        playbackGeneration++
        local.stop()
        kokoro?.stop(reason)
        system.stop()
    }

    override fun close() {
        if (closed) return
        stopMonitoring()
        closed = true
        local.close()
        kokoro?.close()
        system.close()
    }
}
