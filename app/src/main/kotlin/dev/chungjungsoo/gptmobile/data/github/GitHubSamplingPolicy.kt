package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.openAIModelOmitsSampling
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
enum class GitHubCreativityMode { ADAPTIVE, MANUAL }

/** Frozen with each admitted request; never written into the selected provider profile. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GitHubSamplingSettings(
    @EncodeDefault val version: Int = 1,
    @EncodeDefault val mode: GitHubCreativityMode = GitHubCreativityMode.ADAPTIVE,
    @EncodeDefault val creativity: Float = 0.8f
) {
    init {
        require(version == 1 && creativity.isFinite() && creativity in 0f..1f)
    }
}

enum class GitHubSamplingStage {
    OPERATIONS,
    CODING,
    VERIFICATION,
    ARCHITECTURE,
    DESIGN,
    SUMMARY,
    DECISION_OPTIONS,
    DECISION_SELECTION
}

internal object GitHubSamplingPolicy {
    fun stage(role: String?): GitHubSamplingStage = when {
        role == "github-design-options" -> GitHubSamplingStage.DESIGN
        role == "github-architecture-options" -> GitHubSamplingStage.ARCHITECTURE
        role == "github-decision-options" -> GitHubSamplingStage.DECISION_OPTIONS
        role == "github-decision-selection" -> GitHubSamplingStage.DECISION_SELECTION
        role == "github-progress-summary" || role == "github-final-summary" -> GitHubSamplingStage.SUMMARY
        role == "github-coding" || role?.endsWith("repair") == true -> GitHubSamplingStage.CODING
        role == "github-claim-extraction" || role?.startsWith("github-review") == true -> GitHubSamplingStage.VERIFICATION
        else -> GitHubSamplingStage.OPERATIONS
    }

    fun temperature(settings: GitHubSamplingSettings, stage: GitHubSamplingStage): Float {
        val creativity = settings.creativity
        if (settings.mode == GitHubCreativityMode.MANUAL) return 0.2f + creativity
        return when (stage) {
            GitHubSamplingStage.OPERATIONS -> 0.05f
            GitHubSamplingStage.CODING -> 0.2f
            GitHubSamplingStage.VERIFICATION, GitHubSamplingStage.DECISION_SELECTION -> 0.1f
            GitHubSamplingStage.ARCHITECTURE -> 0.4f + 0.3f * creativity
            GitHubSamplingStage.DESIGN -> 0.7f + 0.3f * creativity
            GitHubSamplingStage.SUMMARY -> 0.25f + 0.25f * creativity
            GitHubSamplingStage.DECISION_OPTIONS -> 0.4f + 0.3f * creativity
        }
    }

    /** Only task text requests exploration. Repository/tool text cannot select a policy or grant authority. */
    fun explorationRole(task: String): String? {
        val text = task.take(16000).lowercase()
        return when {
            Regex("\\b(design|redesign|brainstorm)\\b.*\\b(ui|ux|interface|screen|layout|visual)\\b|\\b(ui|ux|interface|visual)\\b.*\\b(design|redesign|concept)\\b").containsMatchIn(text) -> "github-design-options"
            Regex("\\b(architecture|architectural)\\b").containsMatchIn(text) -> "github-architecture-options"
            Regex("\\b(compare|evaluate|choose|explore)\\b.*\\b(alternatives|approaches|options|tradeoffs|trade-offs)\\b").containsMatchIn(text) -> "github-decision-options"
            else -> null
        }
    }

    fun instruction(stage: GitHubSamplingStage): String =
        "Generation stage: ${stage.name.lowercase()}. " + when (stage) {
            GitHubSamplingStage.DESIGN, GitHubSamplingStage.ARCHITECTURE, GitHubSamplingStage.DECISION_OPTIONS ->
                "Explore distinct feasible options. Mark assumptions and proposals explicitly; neither is execution evidence or authorization."
            GitHubSamplingStage.DECISION_SELECTION ->
                "Select using requirements, available source evidence, tests, risk and maintenance cost. Unknown evidence remains unknown."
            GitHubSamplingStage.SUMMARY ->
                "Explain only observed progress and verified outcomes. Vary wording, never facts; retain blockers and uncertainty."
            else -> "Use exact authorized repository identities, revisions and schemas. Require receipts for outcomes; preserve approval and write fences."
        }

    /** Vary temperature alone. Existing serializers additionally enforce model-specific restrictions. */
    fun platform(profile: PlatformV2, settings: GitHubSamplingSettings?, role: String?): PlatformV2 {
        settings ?: return profile
        val supports = when (profile.compatibleType) {
            ClientType.OPENROUTER -> OpenRouterCatalogLimits.forRequest("OPENROUTER", profile.apiUrl, profile.model, profile.openRouterRouting)?.temperatureSupported == true
            ClientType.OPENAI, ClientType.CUSTOM -> !openAIModelOmitsSampling(profile.model)
            ClientType.LITERT_LM -> !profile.accelerator.equals("npu", ignoreCase = true)
            else -> true
        }
        return profile.copy(temperature = temperature(settings, stage(role)).takeIf { supports }, topP = null)
    }
}
