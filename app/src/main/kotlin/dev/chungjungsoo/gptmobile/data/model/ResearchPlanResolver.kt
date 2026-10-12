package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
data class ResearchUserLimits(
    val maxRequests: Long? = null,
    val maxDownloadedBytes: Long? = null,
    val maxActiveMillis: Long? = null
) {
    fun validate() {
        require(listOfNotNull(maxRequests, maxDownloadedBytes, maxActiveMillis).all { it > 0 }) { "User limits must be positive" }
    }
}

data class EffectiveResearchPlan(val settings: DeepResearchSettings) {
    val active get() = settings.enabled && settings.effort > 0
    val sourceGoal get() = if (active) settings.customSourceTarget ?: DeepResearchSettings.targetForEffort(settings.effort) else 0
    val limits get() = settings.userLimits
    val fingerprint get() = researchHash(settings.copy(maxPages = sourceGoal, sourceSafetyCap = null, migrationNotice = "", requestCeiling = 2400, activeRunSeconds = 3600, downloadLimitMiB = 512, corpusLimitMiB = 128).toString())
}

fun effectiveResearchPlan(defaults: DeepResearchSettings): EffectiveResearchPlan = EffectiveResearchPlan(defaults.migrated())

fun effectiveResearchPlan(settings: AppFeatureSettings, profileUid: String, selection: MultiSearchResearchSelection? = null): EffectiveResearchPlan {
    val profile = settings.delegationFor(profileUid)
    return effectiveResearchPlan((selection?.applyTo(profile) ?: profile).deepResearch)
}
