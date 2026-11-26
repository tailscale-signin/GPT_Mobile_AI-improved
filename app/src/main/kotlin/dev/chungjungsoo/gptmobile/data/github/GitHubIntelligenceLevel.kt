package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.Serializable

/** Strategy is request-local. A level never increases a saved budget or grants a tool or destination. */
@Serializable
enum class GitHubIntelligenceLevel(val title: String, val description: String) {
    INSTANT("Instant", "Answer from the smallest set of verified reads."),
    INTELLIGENT("Intelligent", "Scale planning and source inspection to the task."),
    DEEP_ENGINEERING("Deep Engineering", "Trace dependencies and verify each implementation stage."),
    MAXIMUM_INTELLIGENCE("Maximum Intelligence", "Compare approaches and inspect broader regression risks within your budget.")
}

enum class GitHubReasoningEffort(val wire: String) { LOW("low"), MEDIUM("medium"), HIGH("high"), MAXIMUM("high") }

internal enum class GitHubTaskComplexity { LOOKUP, INVESTIGATION, ENGINEERING }

internal data class GitHubEngineeringStrategy(val level: GitHubIntelligenceLevel, val complexity: GitHubTaskComplexity) {
    val effort: GitHubReasoningEffort
        get() = when (level) {
            GitHubIntelligenceLevel.INSTANT -> GitHubReasoningEffort.LOW
            GitHubIntelligenceLevel.INTELLIGENT -> when (complexity) {
                GitHubTaskComplexity.LOOKUP -> GitHubReasoningEffort.LOW
                GitHubTaskComplexity.INVESTIGATION -> GitHubReasoningEffort.MEDIUM
                GitHubTaskComplexity.ENGINEERING -> GitHubReasoningEffort.HIGH
            }
            GitHubIntelligenceLevel.DEEP_ENGINEERING -> GitHubReasoningEffort.HIGH
            GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE -> GitHubReasoningEffort.MAXIMUM
        }

    fun explorationRole(task: String, adaptiveCreativity: Boolean): String? {
        if (level == GitHubIntelligenceLevel.INSTANT || complexity == GitHubTaskComplexity.LOOKUP) return null
        if (adaptiveCreativity) GitHubSamplingPolicy.explorationRole(task)?.let { return it }
        return if (complexity == GitHubTaskComplexity.ENGINEERING && level in setOf(GitHubIntelligenceLevel.DEEP_ENGINEERING, GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE)) {
            "github-architecture-options"
        } else {
            null
        }
    }

    companion object {
        fun predict(level: GitHubIntelligenceLevel, task: String): GitHubEngineeringStrategy {
            val request = GitHubTaskContinuity.request(task)
            val operations = GitHubOperationTool.taskTools(request)
            val complexity = when {
                GitHubStatusRequest.matches(request) || GitHubLookupRoute.from(request) != null -> GitHubTaskComplexity.LOOKUP
                GitHubTestChecklist.integration(request) ||
                    GitHubTaskCoverage.matches(request) ||
                    operations.any { it in setOf("github_stage_patch", "github_write", "github_release") } &&
                    !GitHubRequestCompletion.informational(request) -> GitHubTaskComplexity.ENGINEERING
                GitHubRequestCompletion.requirements(request).size > 1 ||
                    GitHubSamplingPolicy.explorationRole(request) != null ||
                    Regex("""(?i)\b(audit|diagnos\w*|investigat\w*|explain|compar\w*|review|trace|why|how)\b""").containsMatchIn(request) -> GitHubTaskComplexity.INVESTIGATION
                else -> GitHubTaskComplexity.LOOKUP
            }
            return GitHubEngineeringStrategy(level, complexity)
        }
    }
}
