package dev.chungjungsoo.gptmobile.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ConfigBackupDto(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val theme: ThemeBackupDto? = null,
    val platforms: List<PlatformBackupDto> = emptyList()
)

@Serializable
data class ThemeBackupDto(
    val dynamicTheme: Boolean = true,
    val themeMode: Int = 0
)

@Serializable
data class PlatformBackupDto(
    val name: String,
    val compatibleType: Int,
    val enabled: Boolean = true,
    val apiUrl: String = "",
    val token: String = "",
    val model: String = "",
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val topK: Int = 40,
    val maxTokens: Int = 4096,
    val accelerator: Int = 0,
    val systemPrompt: String = "",
    val stream: Boolean = true,
    val reasoning: Boolean = false,
    val timeout: Int = 30,
    val geminiHarassmentThreshold: Int = 0,
    val geminiHateSpeechThreshold: Int = 0,
    val geminiSexuallyExplicitThreshold: Int = 0,
    val geminiDangerousContentThreshold: Int = 0,
    val geminiCivicIntegrityThreshold: Int = 0
)
