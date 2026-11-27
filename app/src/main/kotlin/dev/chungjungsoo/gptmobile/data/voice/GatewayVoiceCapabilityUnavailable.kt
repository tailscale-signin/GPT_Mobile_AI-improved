package dev.chungjungsoo.gptmobile.data.voice

/** Closed readiness reasons from the selected Gateway; never display an arbitrary remote message. */
internal class GatewayVoiceCapabilityUnavailable private constructor(val reason: String, val userMessage: String) : IllegalStateException(userMessage) {
    companion object {
        fun from(reason: String?): GatewayVoiceCapabilityUnavailable {
            val observed = reason?.takeIf { it in reasons } ?: "VOICE_UNAVAILABLE"
            val message = when (observed) {
                "VOICE_DISABLED" -> "Gateway CUDA speech is disabled on the selected PC. Enable it there or select on-device CPU speech."
                "VOICE_WARMING" -> "Gateway CUDA speech is still warming up. Wait for it to become ready, then retry, or select on-device CPU speech."
                "VOICE_PACK_INVALID", "VOICE_PACK_HASH_MISMATCH", "VOICE_FRONTEND_MISMATCH" -> "The selected PC's speech files are unavailable or incompatible. Repair its speech installation or select on-device CPU speech."
                "VOICE_CUDA_UNAVAILABLE", "VOICE_CUDA_NOT_EXECUTED" -> "The selected PC could not run CUDA speech. Check its CUDA speech setup or select on-device CPU speech."
                else -> "Gateway CUDA speech is unavailable on the selected PC. Reconnect and check its speech service, or select on-device CPU speech."
            }
            return GatewayVoiceCapabilityUnavailable(observed, message)
        }

        fun find(error: Throwable): GatewayVoiceCapabilityUnavailable? =
            generateSequence(error) { it.cause }.take(8).filterIsInstance<GatewayVoiceCapabilityUnavailable>().firstOrNull()

        private val reasons = setOf(
            "VOICE_DISABLED", "VOICE_WARMING", "VOICE_PACK_INVALID", "VOICE_PACK_HASH_MISMATCH", "VOICE_FRONTEND_MISMATCH",
            "VOICE_CUDA_UNAVAILABLE", "VOICE_CUDA_NOT_EXECUTED", "VOICE_WORKER_UNAVAILABLE", "VOICE_UNAVAILABLE"
        )
    }
}
