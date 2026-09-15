package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenRouterCreditsResponse(
    val data: OpenRouterCreditsData? = null
)

@Serializable
data class OpenRouterCreditsData(
    @SerialName("total_credits")
    val totalCredits: Double = 0.0,
    @SerialName("total_usage")
    val totalUsage: Double = 0.0
) {
    val remaining: Double
        get() = (totalCredits - totalUsage).coerceAtLeast(0.0)

    val usagePercentage: Float
        get() = if (totalCredits > 0.0) {
            ((totalUsage / totalCredits) * 100.0).coerceIn(0.0, 100.0).toFloat()
        } else {
            0f
        }
}
