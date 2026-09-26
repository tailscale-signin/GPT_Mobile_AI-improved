package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelDelegationSettings(
    val enabled: Boolean = false,
    val targetProfileUid: String = "",
    val localPlatformsOnly: Boolean = true,
    val maxInputCharacters: Int = 8000,
    val maxOutputTokens: Int = 512,
    val timeoutSeconds: Int = 30,
    val maxCallsPerTurn: Int = 1
) {
    fun normalized() = copy(
        maxInputCharacters = maxInputCharacters.coerceIn(500, 16000),
        maxOutputTokens = maxOutputTokens.coerceIn(64, 2048),
        timeoutSeconds = timeoutSeconds.coerceIn(5, 40),
        maxCallsPerTurn = maxCallsPerTurn.coerceIn(1, 3)
    )
}

fun ClientType.isLocalPlatform(): Boolean = this in setOf(ClientType.LITERT_LM, ClientType.LLAMA, ClientType.OLLAMA)
