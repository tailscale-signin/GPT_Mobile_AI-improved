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
    fun stop()
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

    override fun listen(listener: VoiceAudio.Listener) {
        if (closed) return
        playbackGeneration++
        kokoro?.stop()
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

    override fun stop() {
        if (closed) return
        playbackGeneration++
        local.stop()
        kokoro?.stop()
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
