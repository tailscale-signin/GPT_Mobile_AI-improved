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
    val contentScope: ResearchContentScope = ResearchContentScope.WEB_AND_PAPERS,
    val includeDomains: String = "",
    val excludeDomains: String = "",
    val recencyDays: Int = 0,
    val schemaVersion: Int = 1,
    val effort: Int = 50,
    val sourceSafetyCap: Int? = null,
    val strictNoSignup: Boolean = true,
    val requestCeiling: Int = 2400,
    val activeRunSeconds: Int = 3600,
    val downloadLimitMiB: Int = 512,
    val corpusLimitMiB: Int = 128,
    val migrationNotice: String = "",
    val customSourceTarget: Int? = null,
    val userLimits: ResearchUserLimits = ResearchUserLimits(),
    val disabledEngineIds: Set<String> = emptySet()
) {
    fun normalized() = copy(
        maxPages = if (schemaVersion == 3) {
            if (enabled && effort > 0) customSourceTarget ?: targetForEffort(effort) else 0
        } else {
            maxPages.coerceIn(0, 300)
        },
        effort = effort.coerceIn(0, 100),
        sourceSafetyCap = sourceSafetyCap?.coerceIn(0, 300),
        requestCeiling = requestCeiling.coerceIn(1, 2400),
        activeRunSeconds = activeRunSeconds.coerceIn(30, 3600),
        downloadLimitMiB = downloadLimitMiB.coerceIn(1, 512),
        corpusLimitMiB = corpusLimitMiB.coerceIn(1, 128),
        maxRounds = maxRounds.coerceIn(1, 8),
        linkDepth = linkDepth.coerceIn(0, 2),
        concurrency = concurrency.coerceIn(1, 6),
        pageTimeoutSeconds = pageTimeoutSeconds.coerceIn(5, 30),
        recencyDays = recencyDays.coerceIn(0, 365),
        includeDomains = includeDomains.take(1000),
        excludeDomains = excludeDomains.take(1000)
    )

    fun preset(value: ResearchIntensity) = when (value) {
        ResearchIntensity.QUICK -> copy(intensity = value, schemaVersion = 3, effort = 25, maxPages = targetForEffort(25), maxRounds = 2, linkDepth = 0)
        ResearchIntensity.RESEARCH -> copy(intensity = value, schemaVersion = 3, effort = 50, maxPages = targetForEffort(50), maxRounds = 4, linkDepth = 1)
        ResearchIntensity.EXHAUSTIVE -> copy(intensity = value, schemaVersion = 3, effort = 100, maxPages = targetForEffort(100), maxRounds = 8, linkDepth = 2, concurrency = 6, pageTimeoutSeconds = 30)
    }

    /** Effort presets and custom goals are independent of deprecated source caps. */
    fun forEffort(percent: Int): DeepResearchSettings {
        val depth = percent.coerceIn(0, 100)
        val tier = when {
            depth <= 25 -> ResearchIntensity.QUICK
            depth < 75 -> ResearchIntensity.RESEARCH
            else -> ResearchIntensity.EXHAUSTIVE
        }
        val base = preset(tier)
        return base.copy(
            effort = depth,
            schemaVersion = 3,
            maxPages = if (!enabled || depth == 0) 0 else customSourceTarget ?: targetForEffort(depth),
            migrationNotice = if (schemaVersion < 3) "The old research source cap has been removed. Your research goal is preserved." else migrationNotice
        ).normalized()
    }

    fun migrated(): DeepResearchSettings {
        require(schemaVersion in 1..3) { "Unsupported research settings version" }
        require(customSourceTarget == null || customSourceTarget > 0) { "Source goal must be positive" }
        userLimits.validate()
        return if (schemaVersion < 3) {
            copy(
                schemaVersion = 3,
                migrationNotice = "The old research source cap has been removed. Your research goal is preserved."
            ).normalized()
        } else {
            normalized()
        }
    }

    val goalDriven: Boolean get() = enabled && effort > 0 && schemaVersion == 3

    companion object {
        fun targetForEffort(percent: Int): Int {
            val depth = percent.coerceIn(0, 100)
            val anchors = listOf(0 to 0, 25 to 30, 50 to 90, 75 to 180, 100 to 300)
            val interval = anchors.zipWithNext().first { depth <= it.second.first }
            val (start, end) = interval
            return kotlin.math.ceil(start.second + (depth - start.first).toDouble() * (end.second - start.second) / (end.first - start.first)).toInt()
        }
    }
}
