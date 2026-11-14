package dev.chungjungsoo.gptmobile.data.voice

internal enum class KokoroVoice(val speakerId: Int, val label: String, val lexicon: String) {
    HEART(3, "Heart · US English", KokoroModelPack.US_LEXICON),
    ADAM(11, "Adam · US English", KokoroModelPack.US_LEXICON),
    EMMA(21, "Emma · UK English", KokoroModelPack.GB_LEXICON);

    companion object {
        fun fromSaved(value: String?) = entries.firstOrNull { it.name == value }
    }
}

internal interface KokoroOutput {
    val reportsPlaybackStart: Boolean get() = false
    fun bindPlaybackStarted(started: (() -> Unit)?) = Unit
    fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit)
    fun speakProjected(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit) = speak(text, voice, complete, error)
    fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit): VoiceReplyPlayback? = null
    fun configureThreads(value: Int) = Unit
    fun prewarm(voice: KokoroVoice) = Unit
    fun releaseWarmModel() = Unit
    fun stop()
    fun stop(reason: String) = stop()
    fun bindReply(id: String) = Unit
    fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) = Unit
    fun close()
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
    override fun bindPlaybackStarted(started: (() -> Unit)?) {
        playbackStarted = started
    }

    override fun bindSpokenRange(spoken: ((Int, String, IntRange) -> Unit)?) {
        system.bindSpokenRange(spoken)
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

    override fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback? {
        if (!streamsReplies) return null
        val output = kokoro ?: return null
        val voice = kokoroVoice ?: return null
        local.stop()
        system.stop()
        val ticket = ++playbackGeneration
        val fallbackText = StringBuilder()
        var useFallback = false
        var finished = false
        val started = playbackStarted
        fun current() = !closed && playbackGeneration == ticket
        output.bindPlaybackStarted { if (current() && !useFallback) started?.invoke() }
        system.bindPlaybackStarted { if (current() && useFallback) started?.invoke() }
        fun finishFallback() {
            offlineSpeech(fallbackText.toString(), { if (current()) complete() }, { if (current()) error(it) })
        }
        val reply = output.openReply(voice, complete = { if (current()) complete() }, error = { message, canFallback ->
            if (current()) {
                if (canFallback && !requireLocalOutput) {
                    useFallback = true
                    if (finished) finishFallback()
                } else {
                    error(message)
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
        playbackGeneration++
        kokoro?.stop("microphone_started")
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

    override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
        if (closed) return
        local.stop()
        val ticket = ++playbackGeneration
        val started = playbackStarted
        fun current() = !closed && ticket == playbackGeneration
        system.bindPlaybackStarted { if (current()) started?.invoke() }
        val voice = kokoroVoice
        if (voice == null) {
            if (requireLocalOutput) {
                error("Choose a Kokoro voice in Voice settings to read this response.")
                return
            }
            system.speak(text, { if (current()) complete() }, { if (current()) error(it) })
            return
        }
        if (kokoro == null || !LocalVoiceModelStore.supportsLanguage(local.languageTag)) {
            if (requireLocalOutput) {
                error("Local voice playback is unavailable. Check Kokoro in Voice settings; your response is kept in chat.")
                return
            }
            offlineSpeech(text, { if (current()) complete() }, { if (current()) error(it) })
            return
        }
        system.stop()
        kokoro.bindPlaybackStarted { if (current()) started?.invoke() }
        kokoro.speakProjected(text, voice, complete = {
            if (!closed && ticket == playbackGeneration) {
                playbackGeneration++
                complete()
            }
        }, error = { message, canFallback ->
            if (!closed && ticket == playbackGeneration) {
                val fallbackTicket = ++playbackGeneration
                fun fallbackCurrent() = !closed && fallbackTicket == playbackGeneration
                if (canFallback && !requireLocalOutput) {
                    system.bindPlaybackStarted { if (fallbackCurrent()) started?.invoke() }
                    offlineSpeech(text, { if (fallbackCurrent()) complete() }, { if (fallbackCurrent()) error(it) })
                } else {
                    error(message)
                }
            }
        })
    }

    override fun bindReply(id: String) {
        kokoro?.bindReply(id)
    }

    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        system.bindDiagnostics(probe)
        local.bindDiagnostics(probe)
        kokoro?.bindDiagnostics(probe)
    }

    override fun stop() = stop("audio_stop")

    override fun stop(reason: String) {
        if (closed) return
        playbackGeneration++
        local.stop()
        kokoro?.stop(reason)
        system.stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        local.close()
        kokoro?.close()
        system.close()
    }
}
