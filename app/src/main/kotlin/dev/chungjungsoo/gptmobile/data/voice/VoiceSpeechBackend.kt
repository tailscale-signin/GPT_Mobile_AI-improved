package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechBackend(val label: String) {
    AUTO("Auto · measured on this phone"),
    CPU("Native Kokoro · CPU"),
    QNN_GPU("Adreno GPU · experimental"),
    QNN_HTP("Hexagon NPU · experimental"),
    GATEWAY_CUDA("Gateway CUDA · optional"),
    LITERT_KOKORO_CPU("Kokoro LiteRT streaming · CPU"),
    LITERT_KOKORO_GPU("Kokoro LiteRT streaming · GPU requested"),
    LITERT_KOKORO_NPU("Kokoro LiteRT streaming · NPU requested (experimental)"),
    LITERT_KOKORO_GPU_OPTIMIZED("Kokoro LiteRT · GPU-optimized vocoder"),
    LITERT_KOKORO_SM8750("Kokoro LiteRT · Snapdragon 8 Elite NPU vocoder"),
    PHONON_CPU("Phonon streaming · CPU"),
    POCKET_CPU("Pocket streaming · English CPU (default)"),
    POCKET_GPU("Pocket streaming · English GPU requested (experimental)"),
    POCKET_NPU("Pocket streaming · English Qualcomm NPU requested (experimental)"),
    POCKET_MULTILINGUAL_CPU("Pocket streaming · 7 languages CPU");

    val isLiteRtKokoro: Boolean
        get() = this in setOf(LITERT_KOKORO_CPU, LITERT_KOKORO_GPU, LITERT_KOKORO_NPU, LITERT_KOKORO_GPU_OPTIMIZED, LITERT_KOKORO_SM8750)

    val isMediaPipeAccelerator: Boolean
        get() = when (this) {
            LITERT_KOKORO_GPU, LITERT_KOKORO_GPU_OPTIMIZED, LITERT_KOKORO_NPU, LITERT_KOKORO_SM8750 -> true
            else -> false
        }

    val isStreamingLocal: Boolean
        get() = isLiteRtKokoro || isCompiledPocket || this == PHONON_CPU || this == POCKET_CPU || this == POCKET_MULTILINGUAL_CPU

    val isCompiledPocket: Boolean
        get() = this == POCKET_GPU || this == POCKET_NPU

    val supportsCpuThreadCalibration: Boolean
        get() = this == CPU || this == AUTO

    companion object {
        val DEFAULT = POCKET_CPU
    }
}
