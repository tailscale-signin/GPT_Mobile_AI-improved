package dev.chungjungsoo.gptmobile.data.voice

/** Recognition has a separate lifecycle from reply playback and the selected chat model. */
internal interface VoiceRecognitionInput {
    var languageTag: String
    fun listen(listener: VoiceAudio.Listener)
    fun listenWithFeedback(listener: VoiceAudio.Listener, feedback: (() -> Boolean)?) = listen(listener.withCaptureFeedback(feedback))
    fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) = Unit
    fun stop()
    fun close()
}
