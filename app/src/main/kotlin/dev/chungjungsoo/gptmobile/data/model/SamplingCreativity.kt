package dev.chungjungsoo.gptmobile.data.model

data class SamplingSettings(
    val temperature: Float,
    val topP: Float
)

object SamplingCreativity {
    const val DEFAULT = 0.5f

    fun toSampling(value: Float): SamplingSettings {
        val creativity = value.coerceIn(0f, 1f)
        return SamplingSettings(
            temperature = 0.2f + (creativity * 1.0f),
            topP = 0.5f + (creativity * 0.5f)
        )
    }

    fun fromSampling(temperature: Float?, topP: Float?): Float {
        if (temperature == null && topP == null) return DEFAULT
        val temperatureScore = temperature
            ?.let { ((it - 0.2f) / 1.0f).coerceIn(0f, 1f) }
        val topPScore = topP
            ?.let { ((it - 0.5f) / 0.5f).coerceIn(0f, 1f) }
        return when {
            temperatureScore != null && topPScore != null -> (temperatureScore + topPScore) / 2f
            temperatureScore != null -> temperatureScore
            topPScore != null -> topPScore
            else -> DEFAULT
        }
    }
}
