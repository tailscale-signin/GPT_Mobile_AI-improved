package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.Serializable

@Serializable
enum class EngineeringMode { OFF, QUICK, STANDARD, DEEP, ENGINEERING_MAX, ADAPTIVE }

@Serializable
data class EngineeringProfileSettings(
    val mode: EngineeringMode = EngineeringMode.OFF,
    val contextTarget: Int = 65_536,
    val outputTarget: Int = 16_384,
    val reasoningTarget: Int = 16_384,
    val githubOnly: Boolean = true
)

/** Targets are bounded by existing user limits; physical admission remains authoritative. */
object EngineeringAdaptivePolicy {
    fun mode(config: EngineeringProfileSettings, task: String): EngineeringMode {
        if (config.mode != EngineeringMode.ADAPTIVE) return config.mode
        val text = task.lowercase(java.util.Locale.ROOT)
        return when {
            Regex("multi.file|architecture|dependencies|multiple.*fail|verification.*fail|failed.*test|rebase|migration").containsMatchIn(text) -> EngineeringMode.ENGINEERING_MAX
            Regex("implement|debug|fix|patch|refactor").containsMatchIn(text) -> EngineeringMode.DEEP
            Regex("list|status|show.*branch|find.*file").containsMatchIn(text) -> EngineeringMode.QUICK
            else -> EngineeringMode.STANDARD
        }
    }

    fun budgets(config: EngineeringProfileSettings, task: String, existing: TokenBudgetSettings): TokenBudgetSettings {
        if (config.mode == EngineeringMode.OFF) return existing
        if (config.githubOnly && !Regex("(?i)github|repository|\\brepo\\b|\\bcommit\\b|pull request|\\bci\\b").containsMatchIn(task)) return existing
        val selected = mode(config, task)
        val context = when (selected) {
            EngineeringMode.QUICK -> 8_192
            EngineeringMode.STANDARD -> 16_384
            EngineeringMode.DEEP -> 32_768
            else -> 65_536
        }
        val output = when (selected) {
            EngineeringMode.QUICK -> 2_048
            EngineeringMode.STANDARD -> 4_096
            EngineeringMode.DEEP -> 8_192
            else -> 16_384
        }
        val normalized = existing.normalized()
        return normalized.copy(
            contextTokens = minOf(normalized.contextTokens, config.contextTarget.coerceAtLeast(256), context),
            outputTokens = minOf(normalized.outputTokens.takeIf { it > 0 } ?: Int.MAX_VALUE, config.outputTarget.coerceAtLeast(256), output)
        )
    }
}
