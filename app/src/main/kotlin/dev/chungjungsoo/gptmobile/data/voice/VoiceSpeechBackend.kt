package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechBackend(val label: String) {
    AUTO("Auto · measured on this phone"),
    CPU("On-device CPU"),
    QNN_GPU("Adreno GPU"),
    QNN_HTP("Hexagon NPU"),
    GATEWAY_CUDA("Gateway CUDA · optional"),
    LITERT_KOKORO_CPU("Kokoro streaming · CPU"),
    LITERT_KOKORO_GPU("Kokoro streaming · GPU requested"),
    PHONON_CPU("Phonon streaming · CPU"),
    POCKET_CPU("Pocket streaming · CPU");

    val isStreamingLocal: Boolean
        get() = this in setOf(LITERT_KOKORO_CPU, LITERT_KOKORO_GPU, PHONON_CPU, POCKET_CPU)

    val supportsCpuThreadCalibration: Boolean
        get() = this == CPU || this == AUTO
}
