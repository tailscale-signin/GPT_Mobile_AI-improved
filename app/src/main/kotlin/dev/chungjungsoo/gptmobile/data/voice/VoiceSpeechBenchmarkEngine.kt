package dev.chungjungsoo.gptmobile.data.voice

/** Engine identity and its executable processor choices remain independent in the report. */
internal enum class VoiceSpeechBenchmarkEngine(val label: String, val supportedBackends: List<VoiceSpeechBackend>) {
    NATIVE_KOKORO("Native Kokoro", listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)),
    MEDIAPIPE_KOKORO("Kokoro · LiteRT", listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU)),
    MEDIAPIPE_KOKORO_GPU("Kokoro · optimized GPU pack", listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED)),
    MEDIAPIPE_KOKORO_SM8750("Kokoro · compiled SM8750 pack", listOf(VoiceSpeechBackend.LITERT_KOKORO_SM8750)),
    PHONON("Phonon · English", listOf(VoiceSpeechBackend.PHONON_CPU)),
    POCKET("Pocket TTS · English", listOf(VoiceSpeechBackend.POCKET_CPU)),
    POCKET_MULTILINGUAL("Pocket TTS · 7 languages", listOf(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU))
}
