package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechBackend(val label: String) {
    AUTO("Auto · measured on this phone"),
    CPU("Native Kokoro · CPU"),
    QNN_GPU("Adreno GPU · experimental"),
    QNN_HTP("Hexagon NPU · experimental"),
    GATEWAY_CUDA("Gateway CUDA · optional"),
    LITERT_KOKORO_CPU("Kokoro LiteRT streaming · CPU (default)"),
    LITERT_KOKORO_GPU("Kokoro LiteRT streaming · GPU requested"),
    LITERT_KOKORO_NPU("Kokoro LiteRT streaming · NPU requested (experimental)"),
    LITERT_KOKORO_GPU_OPTIMIZED("Kokoro LiteRT · GPU-optimized vocoder"),
    LITERT_KOKORO_SM8750("Kokoro LiteRT · Snapdragon 8 Elite NPU vocoder"),
    PHONON_CPU("Phonon streaming · CPU"),
    POCKET_CPU("Pocket streaming · CPU");

    val isLiteRtKokoro: Boolean
        get() = this in setOf(LITERT_KOKORO_CPU, LITERT_KOKORO_GPU, LITERT_KOKORO_NPU, LITERT_KOKORO_GPU_OPTIMIZED, LITERT_KOKORO_SM8750)

    val isStreamingLocal: Boolean
        get() = isLiteRtKokoro || this == PHONON_CPU || this == POCKET_CPU

    val supportsCpuThreadCalibration: Boolean
        get() = this == CPU || this == AUTO

    companion object {
        val DEFAULT = LITERT_KOKORO_CPU
    }
}
