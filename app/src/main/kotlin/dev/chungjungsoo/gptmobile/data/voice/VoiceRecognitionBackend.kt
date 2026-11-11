package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceRecognitionBackend(val title: String) {
    LOCAL_MOONSHINE("On this phone · Moonshine"),
    ANDROID_SPEECH_SERVICE("Android speech service");

    companion object {
        fun fromSaved(value: String?): VoiceRecognitionBackend = entries.firstOrNull { it.name == value } ?: LOCAL_MOONSHINE
    }
}
