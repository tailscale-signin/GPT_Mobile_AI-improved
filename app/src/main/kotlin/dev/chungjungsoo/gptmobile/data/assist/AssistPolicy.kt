package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.Serializable

@Serializable(with = AssistModeSerializer::class)
enum class AssistMode(val label: String) {
    OFF("Off"),
    PREPARATION_ONLY("Preparation only"),
    ADAPTIVE("Adaptive"),
    LOCAL_FIRST("Local first")
}

/** Global disable always wins over narrower preferences. Legacy enabled means preparation. */
@Serializable
data class AssistPolicy(
    val mode: AssistMode = AssistMode.PREPARATION_ONLY,
    val extractEvidence: Boolean = false,
    val maxTasks: Int = 2,
    val deadlineMs: Long = 8_000,
    val maxSourceCharacters: Int = 12_000,
    val maxProjectionCharacters: Int = 4_000,
    val version: Int = 1
) {
    fun normalized() = copy(
        mode = if (version == 1) mode else AssistMode.OFF,
        maxTasks = maxTasks.coerceIn(1, 4),
        deadlineMs = deadlineMs.coerceIn(1_000, 15_000),
        maxSourceCharacters = maxSourceCharacters.coerceIn(2_000, 12_000),
        maxProjectionCharacters = maxProjectionCharacters.coerceIn(512, 4_000)
    )
}
