package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechBackend(val label: String) {
    AUTO("Auto · measured on this phone"),
    CPU("On-device CPU"),
    QNN_GPU("Adreno GPU"),
    QNN_HTP("Hexagon NPU"),
    GATEWAY_CUDA("Gateway CUDA · optional")
}
