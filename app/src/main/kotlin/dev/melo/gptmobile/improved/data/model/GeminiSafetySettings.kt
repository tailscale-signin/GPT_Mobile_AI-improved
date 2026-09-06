package dev.melo.gptmobile.improved.data.model

enum class GeminiSafetySettings(val value: String) {
    BLOCK_NONE("BLOCK_NONE"),
    BLOCK_ONLY_HIGH("BLOCK_ONLY_HIGH"),
    BLOCK_MEDIUM_AND_ABOVE("BLOCK_MEDIUM_AND_ABOVE"),
    BLOCK_LOW_AND_ABOVE("BLOCK_LOW_AND_ABOVE"),
    HARM_BLOCK_THRESHOLD_UNSPECIFIED("HARM_BLOCK_THRESHOLD_UNSPECIFIED");

    companion object {
        fun fromString(value: String): GeminiSafetySettings {
            return entries.firstOrNull { it.value == value } ?: BLOCK_MEDIUM_AND_ABOVE
        }
    }
}
