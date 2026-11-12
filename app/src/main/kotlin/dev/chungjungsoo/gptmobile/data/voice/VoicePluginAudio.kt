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
    fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit)
    fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, canFallback: Boolean) -> Unit): VoiceReplyPlayback? = null
    fun configureThreads(value: Int) = Unit
    fun prewarm(voice: KokoroVoice) = Unit
    fun releaseWarmModel() = Unit
    fun stop()
    fun stop(reason: String) = stop()
    fun bindReply(id: String) = Unit
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
    private var playbackGeneration = 0L
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
        fun current() = !closed && playbackGeneration == ticket
        fun finishFallback() {
            offlineSpeech(fallbackText.toString(), { if (current()) complete() }, { if (current()) error(it) })
        }
        val reply = output.openReply(voice, complete = { if (current()) complete() }, error = { message, canFallback ->
            if (current()) {
                if (canFallback) {
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
        val voice = kokoroVoice
        if (voice == null) {
            system.speak(text, complete, error)
            return
        }
        if (kokoro == null || !LocalVoiceModelStore.supportsLanguage(local.languageTag)) {
            offlineSpeech(text, complete, error)
            return
        }
        system.stop()
        kokoro.speak(text, voice, complete = {
            if (!closed && ticket == playbackGeneration) {
                playbackGeneration++
                complete()
            }
        }, error = { message, canFallback ->
            if (!closed && ticket == playbackGeneration) {
                playbackGeneration++
                if (canFallback) offlineSpeech(text, complete, error) else error(message)
            }
        })
    }

    override fun bindReply(id: String) {
        kokoro?.bindReply(id)
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
