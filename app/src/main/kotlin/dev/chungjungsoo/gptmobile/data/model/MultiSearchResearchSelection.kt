package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ResearchContentScope { WEB_AND_PAPERS, PAPERS_ONLY, WEB_ONLY }

/** Per-chat intent. The profile's existing strategy is the sole persistent default depth. */
@Serializable
data class MultiSearchResearchSelection(
    val researchToggle: Boolean = false,
    val depth: Int = 0,
    val scope: ResearchContentScope = ResearchContentScope.WEB_AND_PAPERS,
    val gatewayConnectionUid: String? = null,
    val allowNativeFallback: Boolean = false,
    val enabledProviders: Set<String> = emptySet()
) {
    val academicEligibleByUi: Boolean get() = researchToggle && depth > 0 && scope != ResearchContentScope.WEB_ONLY
    fun normalized() = copy(depth = depth.coerceIn(0, 100), enabledProviders = enabledProviders.filter { it.matches(Regex("[a-z0-9_-]{1,80}")) }.toSet())
    fun intensity(): ResearchIntensity? = when {
        !researchToggle || depth <= 0 -> null
        depth <= 34 -> ResearchIntensity.QUICK
        depth <= 74 -> ResearchIntensity.RESEARCH
        else -> ResearchIntensity.EXHAUSTIVE
    }
    fun applyTo(defaults: ModelDelegationSettings): ModelDelegationSettings {
        val tier = intensity() ?: return defaults
        val scaled = defaults.withStrategy(depth)
        return scaled.copy(
            deepResearch = defaults.deepResearch.forEffort(depth).copy(contentScope = scope),
            preparationTimeoutSeconds = if (depth == 100) defaults.deepResearch.activeRunSeconds else scaled.preparationTimeoutSeconds
        )
    }
}

fun researchStartBlockReason(selection: MultiSearchResearchSelection, settings: AppFeatureSettings, profileUid: String, networkAllowed: Boolean): String? = when {
    !selection.researchToggle -> "Research is off"
    selection.depth <= 0 -> "Increase Research Depth to unlock"
    !settings.delegationFor(profileUid).deepResearch.enabled -> "Deep Research is disabled for this AI profile"
    !settings.isToolPluginEnabled(ToolPluginId.DEEP_RESEARCH) -> "Enable Deep Research in Plugins & Tools"
    !settings.isToolPluginSelected(profileUid, ToolPluginId.DEEP_RESEARCH) -> "Enable Deep Research for this AI profile"
    !networkAllowed -> "Network tools are disabled for this profile"
    else -> null
}

fun researchReadinessReason(selection: MultiSearchResearchSelection, settings: AppFeatureSettings, profileUid: String, networkAllowed: Boolean): String? =
    researchStartBlockReason(selection, settings, profileUid, networkAllowed)
        ?: if (selection.scope != ResearchContentScope.WEB_ONLY && !settings.delegationFor(profileUid).deepResearch.academicSources) "Enable Papers and studies consent" else null

/** Academic execution remains blocked in Web only, even when web research is ready. */
fun academicUnlockReason(selection: MultiSearchResearchSelection, settings: AppFeatureSettings, profileUid: String, networkAllowed: Boolean): String? =
    if (selection.scope == ResearchContentScope.WEB_ONLY) "Web-only scope selected" else researchReadinessReason(selection, settings, profileUid, networkAllowed)
