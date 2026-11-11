package dev.chungjungsoo.gptmobile.data.voice

/** Recognition has a separate lifecycle from reply playback and the selected chat model. */
internal interface VoiceRecognitionInput {
    var languageTag: String
    fun listen(listener: VoiceAudio.Listener)
    fun stop()
    fun close()
}
