package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceRecognitionBackend(val title: String) {
    // Retain the saved key from installations that only offered Moonshine Tiny.
    LOCAL_MOONSHINE("On this phone"),
    ANDROID_SPEECH_SERVICE("Android speech service");

    companion object {
        fun fromSaved(value: String?): VoiceRecognitionBackend = entries.firstOrNull { it.name == value } ?: LOCAL_MOONSHINE
    }
}
