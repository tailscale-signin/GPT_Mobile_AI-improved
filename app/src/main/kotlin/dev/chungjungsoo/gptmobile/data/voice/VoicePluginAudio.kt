package dev.chungjungsoo.gptmobile.data.voice

/** Explicit engine selection: missing local assets never send microphone audio to a provider. */
internal class VoicePluginAudio(
    private val system: VoiceAudio,
    private val local: VoiceRecognitionInput,
    private val releaseSystemRecognition: () -> Unit
) : VoiceAudio {
    private var closed = false
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
        system.speak(text, complete, error)
    }

    override fun stop() {
        if (closed) return
        local.stop()
        system.stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        local.close()
        system.close()
    }
}
