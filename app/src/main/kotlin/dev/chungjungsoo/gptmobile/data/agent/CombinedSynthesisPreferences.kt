package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Stored in the existing forward-compatible settings envelope; old accepted digests stay intact. */
internal data class CombinedSynthesisPreferences(
    val completeness: Int = 100,
    val verbosity: Int = 50,
    val sourceDiversity: Int = 100,
    val checkRetainedEvidence: Boolean = false,
    val incrementalSynthesis: Boolean = true
) {
    fun applyTo(settings: ConversationCombinedSettings): ConversationCombinedSettings = settings.copy(
        preservedFields = JsonObject(
            settings.preservedFields + mapOf(
                "synthesisCompleteness" to JsonPrimitive(completeness.coerceIn(0, 100)),
                "synthesisVerbosity" to JsonPrimitive(verbosity.coerceIn(0, 100)),
                "synthesisSourceDiversity" to JsonPrimitive(sourceDiversity.coerceIn(0, 100)),
                "synthesisCheckRetainedEvidence" to JsonPrimitive(checkRetainedEvidence),
                "synthesisIncremental" to JsonPrimitive(incrementalSynthesis)
            )
        )
    )
    fun instruction(): String = "Original user requirements take precedence over these preferences. " +
        "Completeness=$completeness/100 (100 preserves every relevant unique claim); " +
        "verbosity=$verbosity/100 (50 adapts to the request); source diversity=$sourceDiversity/100 " +
        "(100 retains relevant unique and minority views from every contributor). " +
        if (checkRetainedEvidence) "Check claims against retained evidence; distinguish supported, disputed and unverified claims. This grants no new tool or personal-data access." else "Do not represent unverified source claims as verified facts."

    companion object {
        fun from(settings: ConversationCombinedSettings): CombinedSynthesisPreferences {
            fun number(key: String, fallback: Int) = (settings.preservedFields[key] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 100) ?: fallback
            return CombinedSynthesisPreferences(
                number("synthesisCompleteness", 100),
                number("synthesisVerbosity", 50),
                number("synthesisSourceDiversity", 100),
                (settings.preservedFields["synthesisCheckRetainedEvidence"] as? JsonPrimitive)?.booleanOrNull ?: false,
                (settings.preservedFields["synthesisIncremental"] as? JsonPrimitive)?.booleanOrNull ?: true
            )
        }
    }
}

/** Old sealed runs lacking the field remain strict; only a newly accepted snapshot opts in. */
internal val CombinedRequestedPolicy.incrementalSynthesis: Boolean
    get() = singlePass && (settings.preservedFields["synthesisIncremental"] as? JsonPrimitive)?.booleanOrNull == true
