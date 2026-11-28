package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType

/** Configuration checks shared by queue controls and the authenticated execution boundary. */
internal fun benchmarkProfileConfigurationIssue(profile: PlatformV2, checkCredentials: Boolean = false): String? = when {
    !profile.enabled -> "Enable this AI profile before starting a benchmark."
    profile.model.isBlank() -> "Select a model for this profile before starting a benchmark."
    checkCredentials && profile.compatibleType in setOf(ClientType.OPENAI, ClientType.GOOGLE, ClientType.ANTHROPIC, ClientType.GROQ, ClientType.OPENROUTER, ClientType.NVIDIA) && profile.token.isNullOrBlank() -> "Configure credentials for this AI profile before starting a benchmark."
    else -> null
}
