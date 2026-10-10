package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ResearchIntensity { QUICK, RESEARCH, EXHAUSTIVE }

/** Android limits are independent of the inference model's location. */
@Serializable
data class DeepResearchSettings(
    val enabled: Boolean = true,
    val intensity: ResearchIntensity = ResearchIntensity.RESEARCH,
    val maxPages: Int = 12,
    val maxRounds: Int = 2,
    val linkDepth: Int = 1,
    val concurrency: Int = 2,
    val pageTimeoutSeconds: Int = 15,
    val allowExternalLinks: Boolean = false,
    val allowRemoteReaders: Boolean = false,
    val reviewEvidence: Boolean = true,
    val academicSources: Boolean = false,
    val includeDomains: String = "",
    val excludeDomains: String = "",
    val recencyDays: Int = 0
) {
    fun normalized() = copy(
        maxPages = maxPages.coerceIn(0, 30),
        maxRounds = maxRounds.coerceIn(1, 3),
        linkDepth = linkDepth.coerceIn(0, 2),
        concurrency = concurrency.coerceIn(1, 3),
        pageTimeoutSeconds = pageTimeoutSeconds.coerceIn(5, 30),
        recencyDays = recencyDays.coerceIn(0, 365),
        includeDomains = includeDomains.take(1000),
        excludeDomains = excludeDomains.take(1000)
    )

    fun preset(value: ResearchIntensity) = when (value) {
        ResearchIntensity.QUICK -> copy(intensity = value, maxPages = 0, maxRounds = 1, linkDepth = 0)
        ResearchIntensity.RESEARCH -> copy(intensity = value, maxPages = 12, maxRounds = 2, linkDepth = 1)
        ResearchIntensity.EXHAUSTIVE -> copy(intensity = value, maxPages = 24, maxRounds = 3, linkDepth = 2)
    }
}
